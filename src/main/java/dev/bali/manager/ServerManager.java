package dev.bali.manager;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

/** Tracks TPS, ping, server lag and setbacks (useful for tuning against Grim). */
public final class ServerManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final float[] tpsSamples = new float[20];
    private int sampleIndex;
    private int samples;
    private long lastTimeUpdate = -1;
    private volatile long lastPacket = System.currentTimeMillis();
    private volatile long lastSetback;
    private volatile int setbacks;

    @EventHandler(priority = 10000)
    private void onReceive(Events.PacketReceive e) {
        long now = System.currentTimeMillis();
        lastPacket = now;
        if (e.packet instanceof WorldTimeUpdateS2CPacket) {
            if (lastTimeUpdate != -1) {
                float seconds = (now - lastTimeUpdate) / 1000f;
                float tps = Math.max(0f, Math.min(20f, 20f / Math.max(seconds, 0.001f)));
                synchronized (tpsSamples) {
                    tpsSamples[sampleIndex] = tps;
                    sampleIndex = (sampleIndex + 1) % tpsSamples.length;
                    samples = Math.min(samples + 1, tpsSamples.length);
                }
            }
            lastTimeUpdate = now;
        } else if (e.packet instanceof PlayerPositionLookS2CPacket) {
            lastSetback = now;
            setbacks++;
        }
    }

    @EventHandler
    private void onJoin(Events.Join e) {
        synchronized (tpsSamples) {
            samples = 0;
            sampleIndex = 0;
        }
        lastTimeUpdate = -1;
        setbacks = 0;
    }

    public float tps() {
        synchronized (tpsSamples) {
            if (samples == 0) return 20f;
            float sum = 0;
            for (int i = 0; i < samples; i++) sum += tpsSamples[i];
            return sum / samples;
        }
    }

    public int ping() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        PlayerListEntry e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return e == null ? 0 : e.getLatency();
    }

    /** Milliseconds since the last packet from the server. */
    public long sinceLastPacket() { return System.currentTimeMillis() - lastPacket; }

    /** Milliseconds since the server last teleported (set back) us. */
    public long sinceSetback() { return System.currentTimeMillis() - lastSetback; }

    public int setbacks() { return setbacks; }
}
