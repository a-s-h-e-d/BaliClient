package dev.bali.util;

import dev.bali.Bali;
import dev.bali.module.misc.StashFinderBot;
import dev.bali.module.movement.ElytraFly;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Rocket flight inside what Grim's simulation accepts, shared by ElytraFly Boost and StashFinderBot.
 *
 * While a rocket is attached Grim does not simulate the boost. UncertaintyHandler.tickFireworksBox
 * and PredictionEngine.handleStartingVelocityUncertainty accept, per axis, anything from the plain
 * glide result v1 out to v1 + (box - v0), where box = 1.7 * (look + lastLook) clamped to +-1.7 and
 * never tighter than +-0.17. So a burn allows 1.7 b/t on every axis the look points along: 34 b/s
 * on an axis, 48 b/s on a diagonal, and a climb of up to 1.7 b/t for almost no loss of speed.
 *
 * A module calls {@link #request} in TickPre on each tick it wants that velocity; {@link #velocity}
 * is then applied to the player's gliding step by LivingEntityMixin.
 */
public final class GrimBoost {
    private GrimBoost() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    /** Kept inside Grim's box on every side; its flag threshold is 0.001 blocks per tick. */
    private static final double MARGIN = 0.005;

    private static boolean active;
    private static double targetVy, maxSpeed;
    /** When set, {@link #velocity} returns the point of Grim's box closest to this instead of flying along the heading. */
    private static Vec3d targetVel;
    private static float look1Yaw, look1Pitch, look2Yaw, look2Pitch; // rotations sent 1 and 2 ticks ago

    /** Start of every client tick (RotationManager): forget last tick's request, age the look history. */
    public static void tick(float serverYaw, float serverPitch) {
        active = false;
        targetVel = null;
        look2Yaw = look1Yaw;
        look2Pitch = look1Pitch;
        look1Yaw = serverYaw;
        look1Pitch = serverPitch;
    }

    /** Take Grim's box this tick: fly along the look heading at up to maxSpeed, with this vertical speed (blocks/tick). */
    public static void request(double vy, double maxHorizontalSpeed) {
        active = true;
        targetVy = vy;
        maxSpeed = maxHorizontalSpeed;
        targetVel = null;
    }

    /**
     * Take Grim's box this tick and fly as close to this exact velocity (blocks/tick) as it allows,
     * axis by axis. The box only opens along the look, so send a look pointing along {@code want}.
     */
    public static void requestVelocity(Vec3d want) {
        active = true;
        targetVel = want;
    }

    /** True if a module asked for a burn velocity this tick. */
    public static boolean requested() { return active; }

    /**
     * Silent rotation for gliding: with move-fix on the glide physics use it too, so the camera
     * stays free; without move-fix it has to be a real rotation or Grim's simulation won't match.
     */
    public static void face(float yaw, float pitch, int priority, Runnable afterSend) {
        if (Bali.MODULES.get(dev.bali.module.client.Rotations.class).moveFix.on()) {
            Bali.ROTATIONS.rotate(yaw, pitch, priority, afterSend);
        } else {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            if (afterSend != null) afterSend.run();
        }
    }

    /** {yaw, pitch} looking along {@code v}; keeps the current yaw when {@code v} is (nearly) vertical. */
    public static float[] lookAlong(Vec3d v) {
        float[] r = RotationUtil.rotationsTo(Vec3d.ZERO, v);
        if (v.horizontalLengthSquared() < 1e-6) r[0] = mc.player.getYaw();
        return r;
    }

    /** Fires a rocket from the hotbar (slot swap) or the main inventory (swap click). False if none. */
    public static boolean fireRocket() {
        Runnable use = () -> mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
        int hot = InvUtil.findHotbar(net.minecraft.item.Items.FIREWORK_ROCKET);
        if (hot != -1) return InvUtil.silentSwap(hot, use);
        int inv = InvUtil.canClickInventory() ? InvUtil.findInventory(net.minecraft.item.Items.FIREWORK_ROCKET) : -1;
        return inv != -1 && InvUtil.invSwap(inv, use);
    }

    /** A rocket that is attached to us and has ticked at least once (its shooter is resolved then). */
    public static boolean rocketAttached() {
        return !mc.world.getEntitiesByClass(FireworkRocketEntity.class, mc.player.getBoundingBox().expand(8),
            r -> r.shooter == mc.player && !r.isRemoved()).isEmpty();
    }

    /** True while a module flies rockets itself: the vanilla client-side rocket push must not also apply. */
    public static boolean ownsRocket() {
        return active || Bali.MODULES != null
            && (Bali.MODULES.get(ElytraFly.class).ownsRocketBoost() || Bali.MODULES.get(StashFinderBot.class).ownsRocketBoost());
    }

    /** Pitch to send for a burn: Grim's box reaches 3.4 * look.y vertically, so only tilt as far as the climb needs. */
    public static float burnPitch(double vy) {
        if (Math.abs(vy) <= 0.12) return 0;
        return (float) (-Math.signum(vy) * Math.toDegrees(Math.asin(Math.min(0.5, (Math.abs(vy) + 0.1) / 3.4))));
    }

    /** Vanilla LivingEntity.calcGlidingVelocity for an arbitrary rotation. */
    public static Vec3d glideStep(Vec3d v, float yaw, float pitch) {
        Vec3d l = Vec3d.fromPolar(pitch, yaw);
        float f = pitch * (float) (Math.PI / 180.0);
        double d = Math.sqrt(l.x * l.x + l.z * l.z);
        double e = v.horizontalLength();
        double h = MathHelper.square(Math.cos(f));
        double x = v.x, y = v.y + 0.08 * (-1.0 + h * 0.75), z = v.z;
        if (y < 0 && d > 0) {
            double i = y * -0.1 * h;
            x += l.x * i / d;
            y += i;
            z += l.z * i / d;
        }
        if (f < 0 && d > 0) {
            double i = e * -MathHelper.sin(f) * 0.04;
            x -= l.x * i / d;
            y += i * 3.2;
            z -= l.z * i / d;
        }
        if (d > 0) {
            x += (l.x / d * e - x) * 0.1;
            z += (l.z / d * e - z) * 0.1;
        }
        return new Vec3d(x * 0.99F, y * 0.98F, z * 0.99F);
    }

    /** Pitch (within +-30) whose next unpowered glide tick comes closest to the wanted vertical speed. */
    public static float glidePitch(Vec3d vel, float yaw, double wantVy) {
        float best = 0;
        double bestErr = Double.MAX_VALUE;
        for (float p = -30; p <= 30; p += 0.5f) {
            double err = Math.abs(glideStep(vel, yaw, p).y - wantVy);
            if (err < bestErr) {
                bestErr = err;
                best = p;
            }
        }
        return best;
    }

    /**
     * Our player's gliding tick: velocity before ({@code v0}) and after ({@code v1}) vanilla glide
     * physics. With a request pending, returns the fastest point of Grim's accepted range along
     * the heading; otherwise v1 untouched.
     */
    public static Vec3d velocity(Vec3d v0, Vec3d v1) {
        if (!active || mc.player == null) return v1;
        float yaw = Bali.ROTATIONS.movementYaw(), p = Bali.ROTATIONS.movementPitch();
        if (Float.isNaN(yaw)) yaw = mc.player.getYaw();
        if (Float.isNaN(p)) p = mc.player.getPitch();
        Vec3d cur = Vec3d.fromPolar(p, yaw), last = Vec3d.fromPolar(look1Pitch, look1Yaw), last2 = Vec3d.fromPolar(look2Pitch, look2Yaw);

        // whichever tick Grim pairs the looks on, stay inside both readings
        double hiX = v1.x + reach(Math.min(boxMax(cur.x, last.x), boxMax(last.x, last2.x)) - v0.x);
        double hiY = v1.y + reach(Math.min(boxMax(cur.y, last.y), boxMax(last.y, last2.y)) - v0.y);
        double hiZ = v1.z + reach(Math.min(boxMax(cur.z, last.z), boxMax(last.z, last2.z)) - v0.z);
        double loX = v1.x - reach(v0.x - Math.max(boxMin(cur.x, last.x), boxMin(last.x, last2.x)));
        double loY = v1.y - reach(v0.y - Math.max(boxMin(cur.y, last.y), boxMin(last.y, last2.y)));
        double loZ = v1.z - reach(v0.z - Math.max(boxMin(cur.z, last.z), boxMin(last.z, last2.z)));

        if (targetVel != null) {
            return new Vec3d(MathHelper.clamp(targetVel.x, loX, hiX), MathHelper.clamp(targetVel.y, loY, hiY), MathHelper.clamp(targetVel.z, loZ, hiZ));
        }

        double hl = Math.sqrt(cur.x * cur.x + cur.z * cur.z);
        if (hl < 1e-3) return v1;
        double dx = cur.x / hl, dz = cur.z / hl;
        double s = maxSpeed;
        if (dx > 1e-4) s = Math.min(s, hiX / dx);
        else if (dx < -1e-4) s = Math.min(s, loX / dx);
        if (dz > 1e-4) s = Math.min(s, hiZ / dz);
        else if (dz < -1e-4) s = Math.min(s, loZ / dz);
        s = Math.max(s, 0);
        double x = MathHelper.clamp(dx * s, loX, hiX), z = MathHelper.clamp(dz * s, loZ, hiZ);
        if (x * x + z * z < v1.x * v1.x + v1.z * v1.z) { // already faster than the box (dive, bounce): keep it
            x = v1.x;
            z = v1.z;
        }
        return new Vec3d(x, MathHelper.clamp(targetVy, loY, hiY), z);
    }

    private static double boxMax(double a, double b) { return Math.min(1.7, 1.7 * (Math.max(0.05, a) + Math.max(0.05, b))); }
    private static double boxMin(double a, double b) { return Math.max(-1.7, 1.7 * (Math.min(-0.05, a) + Math.min(-0.05, b))); }
    /** How far past v1 we may go on one side: Grim's extension minus the safety margin. */
    private static double reach(double extension) { return Math.max(0, extension - MARGIN); }
}
