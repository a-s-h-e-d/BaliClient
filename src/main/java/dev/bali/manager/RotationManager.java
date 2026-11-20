package dev.bali.manager;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.client.Rotations;
import dev.bali.util.RotationUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Server-side ("silent") rotations.
 *
 * Modules call {@link #rotate} during {@code TickPre}. The highest-priority request of the tick
 * is applied only while the movement packet is built, so the server sees the rotation but the
 * camera does not move. The request's callback runs right after that packet is sent, which is when
 * Grim considers the new rotation current: place/attack packets belong in the callback.
 *
 * With move-fix on, the player's movement for that tick is computed with the server yaw (and the
 * WASD input remapped to the closest matching direction) so Grim's movement simulation matches.
 */
public final class RotationManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public record Request(float yaw, float pitch, int priority, Runnable callback) {}

    private Request pending;
    private Request active;
    private float realYaw, realPitch;
    private float serverYaw, serverPitch;
    private int ticksSinceRotate = 100;

    // ---- API ----

    public void rotate(float yaw, float pitch, int priority, Runnable callback) {
        if (pending == null || priority >= pending.priority) {
            pending = new Request(yaw, MathHelper.clamp(pitch, -90f, 90f), priority, callback);
        } else if (callback != null) {
            // lower priority loses the rotation; let it know nothing happened by not running it
        }
    }

    public void rotate(Vec3d target, int priority, Runnable callback) {
        float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), target);
        rotate(r[0], r[1], priority, callback);
    }

    public boolean hasPending() { return pending != null; }

    /** Rotation the server currently believes we have. */
    public float serverYaw() { return serverYaw; }
    public float serverPitch() { return serverPitch; }

    /** True if a rotation was sent within the last {@code ticks} ticks. */
    public boolean rotatedRecently(int ticks) { return ticksSinceRotate <= ticks; }

    /** Yaw that the movement code should use this tick, or NaN to use the player's own. */
    public float movementYaw() {
        if (pending == null || mc.player == null) return Float.NaN;
        Rotations r = Bali.MODULES.get(Rotations.class);
        if (!r.moveFix.on()) return Float.NaN;
        return fixedYaw(pending.yaw);
    }

    /** Pitch the movement code (elytra physics) should use this tick, or NaN to use the player's own. */
    public float movementPitch() {
        if (pending == null || mc.player == null) return Float.NaN;
        Rotations r = Bali.MODULES.get(Rotations.class);
        if (!r.moveFix.on()) return Float.NaN;
        return fixedPitch(pending.pitch);
    }

    // ---- hooks (ClientPlayerEntityMixin) ----

    public void preSend() {
        if (pending == null || mc.player == null) return;
        active = pending;
        pending = null;
        realYaw = mc.player.getYaw();
        realPitch = mc.player.getPitch();
        mc.player.setYaw(fixedYaw(active.yaw));
        mc.player.setPitch(fixedPitch(active.pitch));
    }

    public void postSend() {
        if (active == null || mc.player == null) return;
        Request done = active;
        active = null;
        ticksSinceRotate = 0;
        // Run the action while the player still has the server rotation, so anything that reads
        // the player's look (item use packets, client raycasts) matches what the server has.
        if (done.callback != null) {
            try {
                done.callback.run();
            } catch (Throwable t) {
                Bali.LOG.error("Rotation callback failed", t);
            }
        }
        mc.player.setYaw(realYaw);
        mc.player.setPitch(realPitch);
    }

    @EventHandler(priority = 1000)
    private void onTickPre(Events.TickPre e) {
        ticksSinceRotate++;
        dev.bali.util.GrimBoost.tick(serverYaw, serverPitch);
    }

    @EventHandler(priority = -1000)
    private void onTickPost(Events.TickPost e) {
        // dropped (e.g. dead, spectating another entity) - don't carry it into next tick
        pending = null;
    }

    /**
     * A kick or server switch can land between a module queueing a rotation and the move packet that
     * sends it; without this the old click (aimed at a block in the previous world) fires on join.
     */
    @EventHandler(priority = 1000)
    private void onJoin(Events.Join e) {
        pending = null;
        active = null;
    }

    @EventHandler(priority = 1000)
    private void onLeave(Events.Leave e) {
        pending = null;
        active = null;
    }

    @EventHandler(priority = -1000)
    private void onSend(Events.PacketSend e) {
        if (e.packet instanceof PlayerMoveC2SPacket p && p.changesLook()) {
            serverYaw = p.getYaw(serverYaw);
            serverPitch = p.getPitch(serverPitch);
        }
    }

    // ---- sensitivity GCD ----

    /** Keep yaw continuous with the last sent yaw and snap the delta to the mouse sensitivity grid. */
    private float fixedYaw(float yaw) {
        float delta = MathHelper.wrapDegrees(yaw - serverYaw);
        return serverYaw + snap(delta);
    }

    private float fixedPitch(float pitch) {
        float delta = pitch - serverPitch;
        return MathHelper.clamp(serverPitch + snap(delta), -90f, 90f);
    }

    private float snap(float delta) {
        Rotations r = Bali.MODULES.get(Rotations.class);
        if (!r.gcdFix.on()) return delta;
        double sens = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd = sens * sens * sens * 8.0 * 0.15;
        return (float) (Math.round(delta / gcd) * gcd);
    }
}
