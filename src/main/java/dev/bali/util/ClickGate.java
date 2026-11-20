package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.PlayerInput;

/**
 * Grim's MultiActionsC flags and cancels an inventory click if the last input packet had a movement
 * key, jump or sneak held, or while sprinting (2b2t runs it). Before a blind click, call
 * {@link #hold()} every tick: that tick's input goes out with no keys and sprint off. Click once
 * {@link #ready()} says the input the server last got was clean (normally the next tick).
 *
 * While gliding the keys do nothing, so this costs no flight speed. On foot it is one tick standing still.
 */
public final class ClickGate {
    private ClickGate() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static boolean holdThisTick, heldLastTick;
    /** The last input we sent (after every filter) had no movement keys, jump or sneak. */
    private static boolean lastClean;

    /** Call in TickPre: this tick's input is sent with no keys, and sprint stops. */
    public static void hold() {
        holdThisTick = true;
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    /**
     * True when a click sent now passes MultiActionsC: the last input the server got was clean and
     * the last sprint state it got was "not sprinting" (another module flipping sprint on this tick
     * doesn't matter, that packet goes out after the click).
     */
    public static boolean ready() {
        return lastClean && mc.player != null && !((dev.bali.mixin.ClientPlayerEntityAccessor) mc.player).bali$lastSprinting();
    }

    /** True for one tick after hold(): Sprint keeps off so the gate can open. */
    public static boolean holding() {
        return holdThisTick || heldLastTick;
    }

    /**
     * For modules that click when ready: true means click now. Otherwise this tick's input is
     * released and they should try again next tick (normally ready then).
     */
    public static boolean tryClick() {
        if (ready()) return true;
        hold();
        return false;
    }

    /** Input mixin, after the keyboard and every other filter: blank it if held, remember what goes out. */
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
