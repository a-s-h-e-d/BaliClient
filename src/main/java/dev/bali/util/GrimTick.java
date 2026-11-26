package dev.bali.util;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public final class GrimTick {
    public static final GrimTick INSTANCE = new GrimTick();

    private static boolean otherClicked;
    private static boolean movedThisTick;

    private GrimTick() {}

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
