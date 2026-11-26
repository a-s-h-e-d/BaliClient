package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class FlightGuard {
    private FlightGuard() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double MARGIN_H = 0.15, MARGIN_V = 0.05;
    private static final double STEP = 1.0;
    private static final double WALL_SAFE_LOSS = 0.3;

    private static final float[] YAW_OFFSETS = {0, 25, -25, 50, -50, 80, -80, 115, -115, 150, -150, 180};

    public static Box boxAtEye(Vec3d eye) {
        return mc.player.getBoundingBox().offset(eye.subtract(mc.player.getEyePos()));
    }

    public static double freeDistance(Box box, Vec3d dir, double maxDist) {
        double len = dir.length();
        if (len < 1e-9 || maxDist <= 0) return maxDist;
        Vec3d unit = dir.multiply(1 / len);
        Box b = box.expand(MARGIN_H, MARGIN_V, MARGIN_H);
        if (!mc.world.isSpaceEmpty(mc.player, box)) return 0;
        double d = 0;
        while (d < maxDist) {
            double step = Math.min(STEP, maxDist - d);
            Vec3d move = unit.multiply(step);
            Vec3d got = Entity.adjustMovementForCollisions(mc.player, move, b, mc.world, List.of());
            if (got.squaredDistanceTo(move) > 1e-8) return d + Math.max(0, got.dotProduct(unit));
            b = b.offset(move);
            d += step;
        }
        return maxDist;
    }

    public static boolean clear(Box box, Vec3d dir, double dist) {
        return freeDistance(box, dir, dist) >= dist - 1e-6;
    }

    public static Vec3d steer(Vec3d want, double lookahead, boolean climb, Vec3d lean) {
        double speed = want.length();
        if (speed < 1e-3 || mc.player == null) return want;
        Vec3d dir = want.multiply(1 / speed);
        Box box = mc.player.getBoundingBox();
        if (clear(box, dir, lookahead)) return want;

        float[] base = RotationUtil.rotationsTo(Vec3d.ZERO, dir);
        if (dir.horizontalLengthSquared() < 1e-6) base[0] = mc.player.getYaw();
        float side = 1;
        if (lean != null && lean.horizontalLengthSquared() > 1e-6) {
            float leanYaw = RotationUtil.rotationsTo(Vec3d.ZERO, lean)[0];
            side = MathHelper.wrapDegrees(leanYaw - base[0]) < 0 ? -1 : 1;
        }
        float b = base[1];
        float[] pitches = climb ? new float[]{b, b - 15, b - 30, b - 45, b - 60, b - 75, -90, b + 15, b + 30}
            : new float[]{b, 0, b - 15, b + 15, -30, 30};
        List<Vec3d> cands = new ArrayList<>();
        if (climb) {
            for (float off : YAW_OFFSETS) for (float p : pitches) addCandidate(cands, p, base[0] + side * off);
        } else {
            for (float p : pitches) for (float off : YAW_OFFSETS) addCandidate(cands, p, base[0] + side * off);
        }

        Vec3d freest = null;
        double most = -1;
        for (Vec3d d : cands) {
            double free = freeDistance(box, d, lookahead);
            if (free >= lookahead - 1e-6) return d.multiply(speed);
            if (free > most) {
                most = free;
                freest = d;
            }
        }
        return freest.multiply(Math.min(speed, Math.max(0, (most - 1.0) / 3)));
    }

    private static void addCandidate(List<Vec3d> out, float pitch, float yaw) {
        Vec3d c = Vec3d.fromPolar(MathHelper.clamp(pitch, -90, 90), yaw);
        for (Vec3d o : out) if (o.squaredDistanceTo(c) < 1e-4) return;
        out.add(c);
    }

    public static int glideHurtTick(float yaw, float pitch, int ticks) {
        Box b = mc.player.getBoundingBox();
        Vec3d v = mc.player.getVelocity();
        double fall = mc.player.fallDistance;
        for (int t = 1; t <= ticks; t++) {
            if (v.y > -0.5 && fall > 1) fall = 1;
            double before = v.horizontalLength();
            v = GrimBoost.glideStep(v, yaw, pitch);
            Vec3d got = Entity.adjustMovementForCollisions(mc.player, v, b, mc.world, List.of());
            boolean hitX = !MathHelper.approximatelyEquals(v.x, got.x), hitZ = !MathHelper.approximatelyEquals(v.z, got.z);
            if (hitX || hitZ) {
                double after = Math.hypot(hitX ? 0 : got.x, hitZ ? 0 : got.z);
                if (before - after > WALL_SAFE_LOSS) return t;
            }
            if (got.y < 0) fall -= got.y;
            if (v.y < 0 && got.y != v.y) return fall > 3 ? t : -1;
            b = b.offset(got);
            v = new Vec3d(hitX ? 0 : v.x, got.y != v.y ? 0 : v.y, hitZ ? 0 : v.z);
        }
        return -1;
    }

    public static float[] safeGlide(float yaw, float pitch, int ticks) {
        if (glideHurtTick(yaw, pitch, ticks) < 0) return null;
        float[] bestR = null;
        double bestScore = -1e9;
        for (float p : new float[]{pitch, -10, -25, -40, 0, 15}) {
            for (float off : YAW_OFFSETS) {
                int hurt = glideHurtTick(yaw + off, p, ticks);
                double score = hurt < 0 ? 1000 - Math.abs(off) - Math.abs(p - pitch) * 0.5 : hurt;
                if (score > bestScore) {
                    bestScore = score;
                    bestR = new float[]{yaw + off, p};
                }
            }
        }
        return bestR;
    }

    public record Fall(List<Vec3d> path, int impact, double fallAtImpact) {}

    public static Fall fall(int ticks) {
        Box b = mc.player.getBoundingBox();
        Vec3d pos = mc.player.getEntityPos(), v = mc.player.getVelocity();
        double fall = mc.player.fallDistance;
        List<Vec3d> path = new ArrayList<>();
        path.add(pos);
        for (int t = 1; t <= ticks; t++) {
            Vec3d got = Entity.adjustMovementForCollisions(mc.player, v, b, mc.world, List.of());
            boolean hitX = !MathHelper.approximatelyEquals(v.x, got.x), hitZ = !MathHelper.approximatelyEquals(v.z, got.z);
            boolean hitY = got.y != v.y;
            if (got.y < 0) fall -= got.y;
            pos = pos.add(got);
            b = b.offset(got);
            path.add(pos);
            if (hitY && v.y < 0) return new Fall(path, t, fall);
            v = new Vec3d(hitX ? 0 : v.x * 0.91, hitY ? 0 : (v.y - 0.08) * 0.98, hitZ ? 0 : v.z * 0.91);
        }
        return new Fall(path, -1, fall);
    }
}
