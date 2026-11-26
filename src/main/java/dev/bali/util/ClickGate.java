package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.PlayerInput;

public final class ClickGate {
    private ClickGate() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static boolean holdThisTick, heldLastTick;
    private static boolean lastClean;

    public static void hold() {
        holdThisTick = true;
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    public static void cancelHold() {
        holdThisTick = false;
    }

    public static boolean ready() {
        return lastClean && mc.player != null && !((dev.bali.mixin.ClientPlayerEntityAccessor) mc.player).bali$lastSprinting();
    }

    public static boolean holding() {
        return holdThisTick || heldLastTick;
    }

    public static boolean tryClick() {
        if (ready()) return true;
        hold();
        return false;
    }

    public static PlayerInput filter(PlayerInput in) {
        PlayerInput out = in;
        heldLastTick = holdThisTick;
        if (holdThisTick) {
            holdThisTick = false;
            out = new PlayerInput(false, false, false, false, false, false, false);
        }
        lastClean = isClean(out);
        return out;
    }

    public static boolean isClean(PlayerInput in) {
        return !in.forward() && !in.backward() && !in.left() && !in.right() && !in.jump() && !in.sneak();
    }
}
