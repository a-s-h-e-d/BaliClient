package dev.bali.module.combat;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Cancel/Reduce: plain knockback removal (Grim will set you back).
 * Wall: only removes knockback while you are inside a block (burrowed/phased), where Grim
 *   can't predict collisions well - the commonly used crystal PvP mode.
 * Grim: old bypass (cancel + position + STOP_DESTROY_BLOCK). May be patched; test it.
 */
public final class Velocity extends Module {
    public enum Mode { Cancel, Reduce, Wall, Grim }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Wall, "How knockback is handled");
    private final NumberSetting horizontal = number("Horizontal", 0, 0, 100, 1, "Horizontal % kept (Reduce)").visibleWhen(() -> isMode(Mode.Reduce));
    private final NumberSetting vertical = number("Vertical", 0, 0, 100, 1, "Vertical % kept (Reduce)").visibleWhen(() -> isMode(Mode.Reduce));
    private final BoolSetting explosions = bool("Explosions", true, "Also handle explosion knockback");

    private volatile boolean grimPending;

    public Velocity() {
        super("Velocity", "Modifies knockback", Category.COMBAT);
    }

    private boolean isMode(Mode m) { return mode != null && mode.is(m); }

    @Override
    public String getInfo() { return mode.get().name(); }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (mc.player == null) return;
        if (e.packet instanceof EntityVelocityUpdateS2CPacket p && p.getEntityId() == mc.player.getId()) {
            if (!active()) return;
            e.cancel();
            switch (mode.get()) {
                case Reduce -> {
                    Vec3d v = p.getVelocity();
                    double h = horizontal.getD() / 100.0, vv = vertical.getD() / 100.0;
                    mc.execute(() -> { if (mc.player != null) mc.player.setVelocityClient(new Vec3d(v.x * h, v.y * vv, v.z * h)); });
                }
                case Grim -> grimPending = true;
                default -> {}
            }
        } else if (explosions.on() && e.packet instanceof ExplosionS2CPacket p && p.playerKnockback().isPresent()) {
            if (!active()) return;
            e.cancel();
            Optional<Vec3d> kb = Optional.empty();
            if (mode.is(Mode.Reduce)) {
                Vec3d v = p.playerKnockback().get();
                kb = Optional.of(new Vec3d(v.x * horizontal.getD() / 100.0, v.y * vertical.getD() / 100.0, v.z * horizontal.getD() / 100.0));
            }
            ExplosionS2CPacket copy = new ExplosionS2CPacket(p.center(), p.radius(), p.blockCount(), kb, p.explosionParticle(), p.explosionSound(), p.blockParticles());
            mc.execute(() -> { if (mc.getNetworkHandler() != null) copy.apply(mc.getNetworkHandler()); });
        }
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (!grimPending) return;
        grimPending = false;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            BlockPos.ofFloored(mc.player.getEntityPos()), Direction.DOWN));
    }

    private boolean active() {
        if (mode.is(Mode.Wall)) return insideBlock();
        return true;
    }

    private boolean insideBlock() {
        Box box = mc.player.getBoundingBox().contract(0.01);
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (!mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) return true;
        }
        return false;
    }
}
