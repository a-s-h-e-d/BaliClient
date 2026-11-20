package dev.bali.util;

import dev.bali.Bali;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

public final class EntityUtil {
    private EntityUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public enum Kind { PLAYER, HOSTILE, PASSIVE, OTHER }

    public static Kind kind(Entity e) {
        if (e instanceof PlayerEntity) return Kind.PLAYER;
        if (e instanceof Monster || e instanceof HostileEntity) return Kind.HOSTILE;
        if (e instanceof Angerable a && a.getAngryAt() != null) return Kind.HOSTILE;
        if (e instanceof PassiveEntity || e instanceof LivingEntity) return Kind.PASSIVE;
        return Kind.OTHER;
    }

    public static boolean isFriend(Entity e) { return Bali.FRIENDS.isFriend(e); }

    public static boolean isAliveTarget(Entity e) {
        if (e == mc.player || e == mc.getCameraEntity()) return false;
        if (e.getId() == dev.bali.module.movement.Blink.GHOST_ID) return false;
        if (!(e instanceof LivingEntity l)) return false;
        if (!l.isAlive() || l.isDead() || l.getHealth() <= 0) return false;
        if (e instanceof PlayerEntity p && (p.isSpectator() || p.isCreative())) return false;
        return true;
    }

    public static BlockPos feet(Entity e) {
        return BlockPos.ofFloored(e.getX(), e.getY() + 0.2, e.getZ());
    }

    public static boolean isMoving() {
        var in = mc.player.input.getMovementInput();
        return in.x != 0 || in.y != 0;
    }
}
