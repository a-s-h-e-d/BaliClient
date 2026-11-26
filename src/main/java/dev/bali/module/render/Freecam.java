package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.NumberSetting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class Freecam extends Module {
    private final NumberSetting speed = number("Speed", 1.0, 0.1, 5.0, 0.1, "Blocks per tick");

    private double x, y, z, prevX, prevY, prevZ;
    private float yaw, pitch, prevYaw, prevPitch;

    public Freecam() {
        super("Freecam", "Fly the camera around; your player stays put", Category.RENDER);
    }

    public static Freecam active() {
        if (Bali.MODULES == null) return null;
        Freecam f = Bali.MODULES.get(Freecam.class);
        return f != null && f.isEnabled() && mc.player != null ? f : null;
    }

    @Override
    protected void onEnable() {
        if (nullCheck()) {
            toggle();
            return;
        }
        Vec3d eye = mc.gameRenderer.getCamera().getCameraPos();
        x = prevX = eye.x;
        y = prevY = eye.y;
        z = prevZ = eye.z;
        yaw = prevYaw = mc.player.getYaw();
        pitch = prevPitch = mc.player.getPitch();
        mc.player.input.playerInput = net.minecraft.util.PlayerInput.DEFAULT;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        prevX = x;
        prevY = y;
        prevZ = z;
        prevYaw = yaw;
        prevPitch = pitch;

        var o = mc.options;
        if (mc.currentScreen != null) return;
        double fwd = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        double side = (o.leftKey.isPressed() ? 1 : 0) - (o.rightKey.isPressed() ? 1 : 0);
        double up = (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0);
        double s = speed.getD() * (o.sprintKey.isPressed() ? 2 : 1);

        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double dx = -sin * fwd + cos * side;
        double dz = cos * fwd + sin * side;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1e-6) {
            x += dx / len * s;
            z += dz / len * s;
        }
        y += up * s;
    }

    @EventHandler
    private void onLeave(Events.Leave e) { setEnabled(false); }

    @EventHandler
    private void onDeath(Events.Death e) { if (e.player == mc.player) setEnabled(false); }

    public void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    public Vec3d pos(float delta) {
        return new Vec3d(MathHelper.lerp(delta, prevX, x), MathHelper.lerp(delta, prevY, y), MathHelper.lerp(delta, prevZ, z));
    }

    public float yaw(float delta) { return MathHelper.lerp(delta, prevYaw, yaw); }

    public float pitch(float delta) { return MathHelper.lerp(delta, prevPitch, pitch); }
}
