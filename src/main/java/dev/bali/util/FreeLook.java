package dev.bali.util;

import net.minecraft.util.math.MathHelper;

public final class FreeLook {
    private FreeLook() {}

    private static boolean active;
    private static float yaw, pitch;

    public static void start(float startYaw, float startPitch) {
        if (active) return;
        yaw = startYaw;
        pitch = startPitch;
        active = true;
    }

    public static void stop() { active = false; }

    public static boolean isActive() { return active; }

    public static void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    public static float yaw() { return yaw; }

    public static float pitch() { return pitch; }
}
