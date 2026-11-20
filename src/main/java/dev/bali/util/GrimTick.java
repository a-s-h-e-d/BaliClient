package dev.bali.util;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Keeps clicks from different modules out of the same Grim tick.
 *
 * Grim's per-tick checks (MultiPlace: a second block click in a tick at another spot or face) end a
 * tick at each movement packet; on 1.21.2+ the tick-end packet only counts when no movement packet
 * went out that tick. A rotated click is sent right after the movement packet, so it shares a Grim
 * tick with whatever is clicked before the next movement packet, which is the start of the next
 * client tick. If Surround (or HoleFiller/AutoTrap) clicks after the movement packet and AutoCrystal
 * clicks at the start of the next tick, Grim sees two placements in one tick.
 *
 * {@link BlockUtil} marks its clicks here; AutoCrystal checks {@link #otherClicked()} and waits for
 * the next Grim tick.
 */
public final class GrimTick {
    public static final GrimTick INSTANCE = new GrimTick();

    private static boolean otherClicked;
    private static boolean movedThisTick;

    private GrimTick() {}

    /** A block click or entity hit that is not AutoCrystal's went out in this Grim tick. */
    public static void markOther() { otherClicked = true; }

    public static boolean otherClicked() { return otherClicked; }

    @EventHandler(priority = -1000)
    private void onSend(Events.PacketSend e) {
        if (e.packet instanceof PlayerMoveC2SPacket) {
            otherClicked = false;
            movedThisTick = true;
        } else if (e.packet instanceof ClientTickEndC2SPacket) {
            if (!movedThisTick) otherClicked = false;
            movedThisTick = false;
        }
    }

    @EventHandler
    private void onJoin(Events.Join e) {
        otherClicked = false;
        movedThisTick = false;
    }
}
