package dev.bali.module.movement;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import org.lwjgl.glfw.GLFW;

public final class YawLock extends Module {
    private float locked;

    public YawLock() {
        super("YawLock", "Locks your yaw to the nearest 45 degrees", Category.MOVEMENT);
        setKey(GLFW.GLFW_KEY_BACKSLASH);
    }

    public static boolean blocksMouseYaw() {
        YawLock y = Bali.MODULES == null ? null : Bali.MODULES.get(YawLock.class);
        return y != null && y.isEnabled();
    }

    @Override
    protected void onEnable() {
        if (nullCheck()) return;
        lock();
    }

    private void lock() {
        locked = Math.round(mc.player.getYaw() / 45f) * 45f;
        mc.player.setYaw(locked);
        mc.player.lastYaw = locked;
        mc.player.setHeadYaw(locked);
    }

    @EventHandler
    private void onJoin(Events.Join e) { if (!nullCheck()) lock(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (mc.player.getYaw() != locked) {
            if (Math.abs(mc.player.getYaw() - locked) > 22.5f) lock();
            else mc.player.setYaw(locked);
        }
    }

    @Override
    public String getInfo() {
        int a = Math.floorMod(Math.round(locked), 360);
        return switch (a) {
            case 0 -> "+Z";
            case 45 -> "-X +Z";
            case 90 -> "-X";
            case 135 -> "-X -Z";
            case 180 -> "-Z";
            case 225 -> "+X -Z";
            case 270 -> "+X";
            case 315 -> "+X +Z";
            default -> a + "°";
        };
    }
}
