package dev.bali.module.combat;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.EnumSetting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Packet: classic offset packets. Grim's simulation flags these, so it's for non-Grim servers.
 * Jump: KillAura jumps and waits until falling, which is a real crit and is safe everywhere.
 */
public final class Criticals extends Module {
    public enum Mode { Jump, Packet }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Jump, "Jump is Grim-safe; Packet is not");

    public Criticals() {
        super("Criticals", "Land critical hits", Category.COMBAT);
    }

    @Override
    public String getInfo() { return mode.get().name(); }

    /** KillAura asks this before swinging. */
    public boolean shouldWait() {
        if (!mode.is(Mode.Jump)) return false;
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing() || mc.player.hasVehicle()) return false;
        if (mc.player.isOnGround()) {
            mc.player.jump();
            return true;
        }
        // wait until we're falling so the hit counts as a crit
        return mc.player.fallDistance <= 0 || mc.player.getVelocity().y > 0;
    }

    @EventHandler
    private void onAttack(Events.Attack e) {
        if (!mode.is(Mode.Packet) || !(e.target instanceof LivingEntity)) return;
        if (!mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isInLava()) return;
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        boolean hc = mc.player.horizontalCollision;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625, z, false, hc));
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, hc));
    }
}
