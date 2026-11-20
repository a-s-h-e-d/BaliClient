package dev.bali.manager;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Counts totem pops per player (entity status 35) and posts pop/death events on the main thread. */
public final class PopManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private final Map<UUID, Integer> pops = new ConcurrentHashMap<>();

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!(e.packet instanceof EntityStatusS2CPacket p)) return;
        byte status = p.getStatus();
        if (status != 35 && status != 3) return;
        mc.execute(() -> {
            if (mc.world == null) return;
            Entity entity = p.getEntity(mc.world);
            if (!(entity instanceof PlayerEntity player)) return;
            if (status == 35) {
                int count = pops.merge(player.getUuid(), 1, Integer::sum);
                Bali.EVENTS.post(new Events.TotemPop(player, count));
            } else {
                int count = pops.getOrDefault(player.getUuid(), 0);
                pops.remove(player.getUuid());
                Bali.EVENTS.post(new Events.Death(player, count));
            }
        });
    }

    @EventHandler
    private void onJoin(Events.Join e) { pops.clear(); }

    public int get(PlayerEntity player) { return pops.getOrDefault(player.getUuid(), 0); }
}
