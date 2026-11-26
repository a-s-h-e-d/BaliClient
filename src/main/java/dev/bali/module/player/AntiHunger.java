package dev.bali.module.player;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.mixin.PlayerMoveC2SPacketAccessor;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public final class AntiHunger extends Module {
    private final BoolSetting ground = bool("Ground", true, "Report not-on-ground while walking (kills jump + sprint hunger)");
    private final NumberSetting safeFall = number("Safe Fall", 2.0, 0.5, 2.9, 0.1, "Safety: never hide on-ground once the server's fall distance passes this");
    private final BoolSetting sprint = bool("Sprint", false, "Hide sprinting from the server (Grim will set you back)");

    private double serverFall;
    private boolean lastRealGround;
    private double lastSentY = Double.NaN;
    private int miningCooldown;

    public AntiHunger() {
        super("AntiHunger", "Lose less hunger", Category.PLAYER);
    }

    @Override
    public String getInfo() { return sprint.on() ? "Ground+Sprint" : "Ground"; }

    @Override
    protected void onEnable() {
        serverFall = 0;
        lastSentY = Double.NaN;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (mc.interactionManager.isBreakingBlock()) miningCooldown = 5;
        else if (miningCooldown > 0) miningCooldown--;
    }

    @EventHandler(priority = -5_000)
    private void onSend(Events.PacketSend e) {
        if (e.isCancelled()) return;
        if (e.packet instanceof ClientCommandC2SPacket c && sprint.on() && c.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING) {
            e.cancel();
            return;
        }
        if (!(e.packet instanceof PlayerMoveC2SPacket p) || mc.player == null) return;

        double dy = 0;
        if (p.changesPosition()) {
            double y = p.getY(mc.player.getY());
            if (!Double.isNaN(lastSentY)) dy = y - lastSentY;
            lastSentY = y;
        }
        boolean wasOnGround = lastRealGround;
        lastRealGround = p.isOnGround();

        boolean spoof = ground.on() && p.isOnGround() && wasOnGround && Math.abs(dy) < 1e-4 && canSpoof();
        if (spoof) ((PlayerMoveC2SPacketAccessor) p).bali$setOnGround(false);

        boolean sentGround = p.isOnGround();
        if (sentGround) serverFall = 0;
        else if (dy < 0) serverFall -= dy;
        if (mc.player.isGliding() && mc.player.getVelocity().y > -0.5) serverFall = Math.min(serverFall, 1.0);
        if (mc.player.isTouchingWater()) serverFall = 0;
    }

    private boolean canSpoof() {
        if (serverFall >= safeFall.getD()) return false;
        if (miningCooldown > 0) return false;
        return !mc.player.isTouchingWater() && !mc.player.isClimbing() && !mc.player.hasVehicle() && !mc.player.isGliding();
    }
}
