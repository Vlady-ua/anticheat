package com.example.guard;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents; // unused, safe to delete
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class GuardAnticheat implements ModInitializer {

    // ---- Look & feel ------------------------------------------------------
    private static final String PREFIX = "Guard"; // change to whatever you want shown
    // ---- Tunables ---------------------------------------------------------
    private static final double MAX_REACH = 3.0;
    private static final double REACH_TOLERANCE = 0.35;   // lag / hitbox leniency
    private static final double FLICK_DEGREES = 70.0;     // snap size to count as a flick
    private static final double SNAP_DEGREES = 45.0;      // snap in + snap out = silent aim
    private static final double AIM_LOCK_DEGREES = 3.0;   // "perfectly on target"
    private static final double KILLAURA_FOV = 100.0;     // hit target > this far off crosshair
    private static final int MULTI_TARGET_WINDOW = 8;     // ticks
    private static final int MULTI_TARGET_COUNT = 3;
    private static final double FASTBREAK_BALANCE_FLAG = 150.0; // ms

    public static final Logger LOG = LoggerFactory.getLogger("guard-anticheat");
    private static MinecraftServer server;
    private static final Map<UUID, Data> DATA = new HashMap<>();

    private static class Data {
        final Map<String, Double> vl = new HashMap<>();
        // movement
        double lastY, lastDy;
        int airTicks;
        int flyBuffer;
        // rotation
        float lastYaw, lastPitch;
        double lastTickSnap;           // size of rotation change this tick
        // attack tracking
        final ArrayDeque<int[]> recentTargets = new ArrayDeque<>(); // {tick, entityId}
        boolean pendingAimCheck;
        double pendingAimError, pendingSnapIn;
        // fastbreak
        long lastBreakMs;
        double breakBalance;
    }

    private static Data data(ServerPlayer p) {
        return DATA.computeIfAbsent(p.getUUID(), k -> new Data());
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { server = null; DATA.clear(); });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> DATA.remove(handler.getPlayer().getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        AttackEntityCallback.EVENT.register((player, world, hand, target, hit) -> {
            if (player instanceof ServerPlayer sp && !sp.isCreative() && !sp.isSpectator()) {
                onAttack(sp, target);
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayer sp) || sp.isCreative() || sp.isSpectator()) return;
            Data d = data(sp);
            long now = System.currentTimeMillis();

            float progressPerTick = state.getDestroyProgress(sp, world, pos);
            if (progressPerTick >= 1.0f || progressPerTick <= 0.0f) { // instamine / unbreakable
                d.lastBreakMs = now;
                return;
            }
            double requiredMs = Math.ceil(1.0 / progressPerTick) * 50.0;

            if (d.lastBreakMs != 0) {
                double diff = now - d.lastBreakMs;
                // A legit player can't start the next block before the last one broke,
                // so diff should always be >= requiredMs. Any shortfall accumulates.
                d.breakBalance = Math.max(0, Math.min(2000, d.breakBalance + (requiredMs - diff)));
                if (d.breakBalance > FASTBREAK_BALANCE_FLAG) {
                    String type = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    flag(sp, "FastBreak", 1.0,
                            String.format("diff=%.1fms, balance=%.1fms, type=%s", diff, d.breakBalance, type));
                }
            }
            d.lastBreakMs = now;
        });
    }

    // ======================================================================
    //  Per-tick: fly + rotation bookkeeping + silent aim follow-up + VL decay
    // ======================================================================
    private void onTick(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            Data d = data(p);
            tickFly(p, d);
            tickRotation(p, d);
            if (s.getTickCount() % 1200 == 0) { // decay violations every minute
                d.vl.replaceAll((k, v) -> v * 0.8);
                d.vl.values().removeIf(v -> v < 0.5);
            }
        }
    }

    private void tickFly(ServerPlayer p, Data d) {
        double y = p.getY();
        double dy = y - d.lastY;

        boolean exempt = p.isCreative() || p.isSpectator() || p.getAbilities().mayfly
                || p.isFallFlying() || p.isPassenger() || p.isInWater() || p.isInLava()
                || p.onClimbable() || p.hasEffect(MobEffects.LEVITATION)
                || p.hasEffect(MobEffects.SLOW_FALLING) || p.hurtTime > 0
                || p.isAutoSpinAttack() || p.tickCount < 40 || Math.abs(dy) > 3.0; // teleports

        if (exempt || p.onGround()) {
            d.airTicks = 0;
            d.flyBuffer = Math.max(0, d.flyBuffer - 1);
        } else {
            d.airTicks++;
            double predicted = (d.lastDy - 0.08) * 0.98; // vanilla gravity + drag
            boolean tooHigh = d.airTicks >= 4 && dy > predicted + 0.05;
            boolean hovering = d.airTicks >= 10 && Math.abs(dy) < 0.02;
            if (tooHigh || hovering) {
                if (++d.flyBuffer >= 5) {
                    flag(p, "Fly", 1.0, String.format("dy=%.3f, predicted=%.3f, air=%d", dy, predicted, d.airTicks));
                    d.flyBuffer = 3;
                }
            } else {
                d.flyBuffer = Math.max(0, d.flyBuffer - 1);
            }
        }
        d.lastY = y;
        d.lastDy = dy;
    }

    private void tickRotation(ServerPlayer p, Data d) {
        float yaw = p.getYRot(), pitch = p.getXRot();
        double snap = angleBetween(look(d.lastYaw, d.lastPitch), look(yaw, pitch));

        // Silent aim: rotation snapped onto the target for the hit, then snapped back.
        if (d.pendingAimCheck) {
            if (d.pendingSnapIn > SNAP_DEGREES && snap > SNAP_DEGREES && d.pendingAimError < AIM_LOCK_DEGREES) {
                flag(p, "SilentAim", 2.0,
                        String.format("in=%.1f, out=%.1f, err=%.2f", d.pendingSnapIn, snap, d.pendingAimError));
            }
            d.pendingAimCheck = false;
        }
        d.lastTickSnap = snap;
        d.lastYaw = yaw;
        d.lastPitch = pitch;
    }

    // ======================================================================
    //  Attack: reach, killaura, flick, silent aim (arming)
    // ======================================================================
    private void onAttack(ServerPlayer p, Entity target) {
        Data d = data(p);
        int tick = p.tickCount;
        Vec3 eye = p.getEyePosition();
        AABB box = target.getBoundingBox();

        // --- Reach: eye -> closest point of target hitbox
        Vec3 closest = new Vec3(
                clamp(eye.x, box.minX, box.maxX),
                clamp(eye.y, box.minY, box.maxY),
                clamp(eye.z, box.minZ, box.maxZ));
        double reach = eye.distanceTo(closest);
        if (reach > MAX_REACH + REACH_TOLERANCE) {
            flag(p, "Reach", 1.0, String.format("dist=%.2f, max=%.2f", reach, MAX_REACH));
        }

        // --- Aim error: angle between crosshair and target center
        Vec3 toTarget = box.getCenter().subtract(eye).normalize();
        Vec3 look = look(p.getYRot(), p.getXRot());
        double aimError = angleBetween(look, toTarget);

        // --- Killaura: hitting far outside the field of view
        if (aimError > KILLAURA_FOV) {
            flag(p, "KillAura", 1.5, String.format("type=angle, off=%.1f", aimError));
        }

        // --- Killaura: hitting through walls
        HitResult wall = p.level().clip(new ClipContext(eye, closest,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (wall.getType() == HitResult.Type.BLOCK && reach > 1.0) {
            flag(p, "KillAura", 1.5, "type=wall");
        }

        // --- Killaura: many distinct targets in a few ticks
        d.recentTargets.removeIf(e -> tick - e[0] > MULTI_TARGET_WINDOW);
        d.recentTargets.add(new int[]{tick, target.getId()});
        long distinct = d.recentTargets.stream().map(e -> e[1]).distinct().count();
        if (distinct >= MULTI_TARGET_COUNT) {
            flag(p, "KillAura", 2.0, "type=multi, targets=" + distinct);
            d.recentTargets.clear();
        }

        // --- Flick: huge rotation change landing exactly on the target
        if (d.lastTickSnap > FLICK_DEGREES && aimError < AIM_LOCK_DEGREES) {
            flag(p, "AimFlick", 1.0, String.format("snap=%.1f, err=%.2f", d.lastTickSnap, aimError));
        }

        // --- Arm the silent-aim follow-up (checked next tick)
        d.pendingAimCheck = true;
        d.pendingAimError = aimError;
        d.pendingSnapIn = d.lastTickSnap;
    }

    // ======================================================================
    //  Flagging / alert formatting:  Guard » name failed Check (x10) info
    // ======================================================================
    private static void flag(ServerPlayer p, String check, double weight, String info) {
        Data d = data(p);
        double vl = d.vl.merge(check, weight, Double::sum);

        MutableComponent msg = Component.literal(PREFIX).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(p.getGameProfile().name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" failed ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(check).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (x").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf((int) Math.round(vl))).withStyle(ChatFormatting.RED))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" " + info).withStyle(ChatFormatting.GRAY));

        LOG.info("{} failed {} (x{}) {}", p.getGameProfile().name(), check, Math.round(vl), info);
        if (server == null) return;
        for (ServerPlayer op : server.getPlayerList().getPlayers()) {
            if (isOperator(op)) op.sendSystemMessage(msg);
        }
    }

    /** If this line fails to compile on 26.2, this is the one to adjust (permission API changed in recent versions). */
    private static boolean isOperator(ServerPlayer p) {
        return server.getPlayerList().isOp(new NameAndId(p.getGameProfile()));
    }

    // ---- math helpers ------------------------------------------------------
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static Vec3 look(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(-yawDeg), pitch = Math.toRadians(-pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * cp, Math.sin(pitch), Math.cos(yaw) * cp);
    }

    private static double angleBetween(Vec3 a, Vec3 b) {
        double dot = clamp(a.dot(b), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }
}
