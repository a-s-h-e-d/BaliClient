package dev.bali.module.movement;

import dev.bali.manager.RotationPriority;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.InvUtil;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Packet: spoofs onGround while falling. Grim's NoFall check catches this and applies the damage
 *   anyway, so only for non-Grim servers.
 * Bucket: looks down and places water just before landing (legit "MLG"), safe on Grim.
 */
public final class NoFall extends Module {
    public enum Mode { Bucket, Packet }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Bucket, "Bucket is Grim-safe");
    private final NumberSetting minFall = number("Min Fall", 4, 3, 20, 0.5, "Only act on falls at least this high");

    public NoFall() {
        super("NoFall", "Prevents fall damage", Category.MOVEMENT);
    }

    @Override
    public String getInfo() { return mode.get().name(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (mc.player.isCreative() || mc.player.isGliding() || mc.player.fallDistance < minFall.getF()) return;
        if (mc.player.getVelocity().y >= 0) return;

        if (mode.is(Mode.Packet)) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(true, mc.player.horizontalCollision));
            mc.player.fallDistance = 0;
            return;
        }

        if (mc.world.getRegistryKey() == net.minecraft.world.World.NETHER) return; // water evaporates in the nether
        int slot = InvUtil.findHotbar(Items.WATER_BUCKET);
        if (slot == -1) return;
        // place when the ground is within reach of the next couple of ticks
        Vec3d pos = mc.player.getEntityPos();
        double lookAhead = Math.max(2.0, -mc.player.getVelocity().y * 2.5);
        var hit = mc.world.raycast(new RaycastContext(pos, pos.add(0, -lookAhead, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        if (pos.y - hit.getPos().y > 4.5) return;
        Bali.ROTATIONS.rotate(mc.player.getYaw(), 90f, RotationPriority.NO_FALL, () -> InvUtil.silentSwap(slot, () -> {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        }));
    }
}
