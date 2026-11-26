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

public final class RotationManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public record Request(float yaw, float pitch, int priority, Runnable callback) {}

    private Request pending;
    private Request active;
    private float realYaw, realPitch;
    private float serverYaw, serverPitch;
    private int ticksSinceRotate = 100;

    public void rotate(float yaw, float pitch, int priority, Runnable callback) {
        if (pending == null || priority >= pending.priority) {
            pending = new Request(yaw, MathHelper.clamp(pitch, -90f, 90f), priority, callback);
        } else if (callback != null) {
        }
    }

    public void rotate(Vec3d target, int priority, Runnable callback) {
        float[] r = RotationUtil.rotationsTo(mc.player.getEyePos(), target);
        rotate(r[0], r[1], priority, callback);
    }

    public boolean hasPending() { return pending != null; }

    public float serverYaw() { return serverYaw; }
    public float serverPitch() { return serverPitch; }

    public boolean rotatedRecently(int ticks) { return ticksSinceRotate <= ticks; }

    public float movementYaw() {
        if (pending == null || mc.player == null) return Float.NaN;
        Rotations r = Bali.MODULES.get(Rotations.class);
        if (!r.moveFix.on()) return Float.NaN;
        return fixedYaw(pending.yaw);
    }

    public float movementPitch() {
        if (pending == null || mc.player == null) return Float.NaN;
        Rotations r = Bali.MODULES.get(Rotations.class);
        if (!r.moveFix.on()) return Float.NaN;
        return fixedPitch(pending.pitch);
    }

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
        pending = null;
    }

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
