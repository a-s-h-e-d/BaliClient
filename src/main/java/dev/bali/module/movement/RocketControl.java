package dev.bali.module.movement;

import dev.bali.manager.RotationPriority;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.GrimBoost;
import dev.bali.util.MaceUtil;
import dev.bali.util.RotationUtil;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

public final class RocketControl extends Module {
    public enum Layout { Vertical, Standard }

    private final EnumSetting<Layout> layout = mode("Layout", Layout.Vertical, "Vertical: W up / S down / A left / D right / Space forward / Shift back");
    private final NumberSetting speed = number("Speed", 34, 5, 34, 1, "Flight speed in blocks/s (Grim's box allows up to 34 per axis)");
    private final BoolSetting hover = bool("Hover", true, "With no key held, brake and hold still while a rocket burns");
    private final BoolSetting autoRocket = bool("Auto Rocket", true, "Fire a rocket when a key is held and none is burning");
    private final BoolSetting hoverRockets = bool("Hover Rockets", false, "Also fire rockets just to keep hovering");
    private final NumberSetting rocketDelay = number("Rocket Delay", 10, 2, 40, 1, "Min ticks between rockets");

    private int rocketTimer;
    private volatile int setbackPause;
    private boolean steering;

    public RocketControl() {
        super("RocketControl", "Elytra + rockets: fly straight where the keys point (W up, A/D sideways)", Category.MOVEMENT);
    }

    @Override
    public String getInfo() { return layout.get().name(); }

    public boolean steering() { return isEnabled() && steering; }

    @EventHandler
    private void onTick(Events.TickPre e) {
        steering = false;
        if (nullCheck()) return;
        if (rocketTimer > 0) rocketTimer--;
        if (setbackPause > 0) {
            setbackPause--;
            return;
        }
        if (dev.bali.Bali.MODULES.get(dev.bali.module.combat.ElytraMace.class).busy()) return;
        if (!mc.player.isGliding() || !MaceUtil.wearingElytra() || mc.player.isTouchingWater() || mc.currentScreen != null) return;

        Vec3d dir = wantedDirection();
        boolean keys = dir.lengthSquared() > 1e-6;
        if (!keys && !hover.on()) return;
        steering = true;

        boolean rocket = GrimBoost.rocketAttached();
        boolean fire = !rocket && rocketTimer <= 0 && autoRocket.on() && (keys || hoverRockets.on());
        Runnable fireRun = fire ? () -> {
            if (GrimBoost.fireRocket()) rocketTimer = rocketDelay.getI();
        } : null;

        if (!rocket) {
            if (fireRun == null) return;
            float[] r = keys ? GrimBoost.lookAlong(dir) : new float[]{mc.player.getYaw(), mc.player.getPitch()};
            GrimBoost.face(r[0], r[1], RotationPriority.ROCKET_CONTROL, fireRun);
            return;
        }

        if (keys) {
            Vec3d want = dir.normalize().multiply(speed.getD() / 20);
            GrimBoost.requestVelocity(want);
            float[] r = GrimBoost.lookAlong(want);
            GrimBoost.face(r[0], r[1], RotationPriority.ROCKET_CONTROL, null);
        } else {
            GrimBoost.requestVelocity(Vec3d.ZERO);
        }
    }

    private Vec3d wantedDirection() {
        boolean w = mc.options.forwardKey.isPressed(), s = mc.options.backKey.isPressed();
        boolean a = mc.options.leftKey.isPressed(), d = mc.options.rightKey.isPressed();
        boolean up = mc.options.jumpKey.isPressed(), down = mc.options.sneakKey.isPressed();
        double fwd, vert;
        if (layout.is(Layout.Vertical)) {
            vert = (w ? 1 : 0) - (s ? 1 : 0);
            fwd = (up ? 1 : 0) - (down ? 1 : 0);
        } else {
            fwd = (w ? 1 : 0) - (s ? 1 : 0);
            vert = (up ? 1 : 0) - (down ? 1 : 0);
        }
        double side = (a ? 1 : 0) - (d ? 1 : 0);
        double yaw = Math.toRadians(mc.player.getYaw());
        double x = -Math.sin(yaw) * fwd + Math.cos(yaw) * side;
        double z = Math.cos(yaw) * fwd + Math.sin(yaw) * side;
        return new Vec3d(x, vert, z);
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (e.packet instanceof PlayerPositionLookS2CPacket) {
            setbackPause = 10;
            Recorder.log("RCTL", "setback");
        }
    }
}
