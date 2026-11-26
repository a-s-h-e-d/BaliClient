package dev.bali.util;

import dev.bali.Bali;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class BlockUtil {
    private BlockUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public record PlaceInfo(BlockPos neighbor, Direction side, Vec3d hitVec, BlockPos target) {
        public BlockHitResult hitResult() { return new BlockHitResult(hitVec, side, neighbor, false); }
    }

    public static BlockState state(BlockPos pos) { return mc.world.getBlockState(pos); }

    public static boolean isReplaceable(BlockPos pos) { return state(pos).isReplaceable(); }

    public static boolean isAir(BlockPos pos) { return state(pos).isAir(); }

    public static boolean noEntityCollision(BlockPos pos) {
        Box box = new Box(pos);
        for (Entity e : mc.world.getOtherEntities(null, box)) {
            if (e instanceof ItemEntity || e instanceof ExperienceOrbEntity) continue;
            if (!e.isSpectator() && e.isAlive()) return false;
        }
        return true;
    }

    public static boolean canPlace(BlockPos pos) {
        return isReplaceable(pos) && noEntityCollision(pos);
    }

    public static PlaceInfo placeInfo(BlockPos pos, boolean strictDirection, double range) {
        Vec3d eye = mc.player.getEyePos();
        PlaceInfo best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState ns = state(neighbor);
            if (ns.isReplaceable() || !ns.getFluidState().isEmpty() && ns.isReplaceable()) continue;
            if (ns.getCollisionShape(mc.world, neighbor).isEmpty()) continue;
            if (isClickable(ns)) continue;
            Direction side = dir.getOpposite();
            Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(side.getVector()).multiply(0.5));
            if (strictDirection && !faceVisible(eye, neighbor, side)) continue;
            double d = eye.squaredDistanceTo(hit);
            if (d > range * range) continue;
            if (d < bestDist) {
                bestDist = d;
                best = new PlaceInfo(neighbor, side, hit, pos);
            }
        }
        return best;
    }

    public static boolean isClickable(BlockState s) {
        Block b = s.getBlock();
        return b instanceof BlockWithEntity
                || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof LoomBlock
                || b instanceof CartographyTableBlock || b instanceof GrindstoneBlock || b instanceof StonecutterBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof DoorBlock
                || b instanceof TrapdoorBlock || b instanceof FenceGateBlock || b instanceof NoteBlock
                || b instanceof AbstractRedstoneGateBlock || b instanceof CakeBlock || b instanceof RespawnAnchorBlock
                || b instanceof FlowerPotBlock || b instanceof ComposterBlock || b instanceof DragonEggBlock;
    }

    public static boolean faceVisible(Vec3d eye, BlockPos block, Direction side) {
        Vec3d c = Vec3d.ofCenter(block);
        return switch (side) {
            case UP -> eye.y > c.y + 0.5;
            case DOWN -> eye.y < c.y - 0.5;
            case NORTH -> eye.z < c.z - 0.5;
            case SOUTH -> eye.z > c.z + 0.5;
            case WEST -> eye.x < c.x - 0.5;
            case EAST -> eye.x > c.x + 0.5;
        };
    }

    public static boolean place(BlockPos pos, int hotbarSlot, boolean rotate, boolean strictDirection, double range, int priority) {
        return place(pos, hotbarSlot, rotate, strictDirection, range, priority, null);
    }

    public static boolean place(BlockPos pos, int hotbarSlot, boolean rotate, boolean strictDirection, double range, int priority, Runnable onClick) {
        if (!canPlace(pos)) return false;
        PlaceInfo info = placeInfo(pos, strictDirection, range);
        if (info == null) return false;
        Runnable click = () -> InvUtil.silentSwap(hotbarSlot, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, info.hitResult());
            mc.player.swingHand(Hand.MAIN_HAND);
            GrimTick.markOther();
            if (onClick != null) onClick.run();
        });
        if (rotate) Bali.ROTATIONS.rotate(info.hitVec(), priority, click);
        else click.run();
        return true;
    }

    public static List<EndCrystalEntity> blockingCrystals(BlockPos pos) {
        List<EndCrystalEntity> out = new ArrayList<>();
        for (Entity e : mc.world.getOtherEntities(null, new Box(pos))) {
            if (e instanceof ItemEntity || e instanceof ExperienceOrbEntity) continue;
            if (e.isSpectator() || !e.isAlive()) continue;
            if (e instanceof EndCrystalEntity c) out.add(c);
            else return null;
        }
        return out;
    }

    public static boolean placeBreakingCrystals(BlockPos pos, int hotbarSlot, boolean strictDirection, double range,
                                                int priority, float maxSelf, Runnable onClick) {
        if (!isReplaceable(pos)) return false;
        List<EndCrystalEntity> crystals = blockingCrystals(pos);
        if (crystals == null || crystals.isEmpty()) return false;
        PlaceInfo info = placeInfo(pos, strictDirection, range);
        if (info == null) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(info.hitVec().subtract(eye).normalize().multiply(3));
        float self = 0;
        for (EndCrystalEntity c : crystals) {
            Box b = c.getBoundingBox();
            if (!b.contains(eye) && b.raycast(eye, end).isEmpty()) return false;
            self += DamageUtil.crystalDamage(mc.player, c.getEntityPos());
        }
        if (self > maxSelf) return false;
        EndCrystalEntity first = crystals.get(0);
        boolean last = crystals.size() == 1;
        Bali.ROTATIONS.rotate(info.hitVec(), priority, () -> {
            mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(first, mc.player.isSneaking()));
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            GrimTick.markOther();
            if (!last) return;
            InvUtil.silentSwap(hotbarSlot, () -> {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, info.hitResult());
                mc.player.swingHand(Hand.MAIN_HAND);
                if (onClick != null) onClick.run();
            });
        });
        return true;
    }
}
