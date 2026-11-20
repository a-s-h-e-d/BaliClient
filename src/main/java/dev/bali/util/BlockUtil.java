package dev.bali.util;

import dev.bali.Bali;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class BlockUtil {
    private BlockUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** Where to click to place a block at a position: the neighbour, its face, and the hit vector. */
    public record PlaceInfo(BlockPos neighbor, Direction side, Vec3d hitVec, BlockPos target) {
        public BlockHitResult hitResult() { return new BlockHitResult(hitVec, side, neighbor, false); }
    }

    public static BlockState state(BlockPos pos) { return mc.world.getBlockState(pos); }

    public static boolean isReplaceable(BlockPos pos) { return state(pos).isReplaceable(); }

    public static boolean isAir(BlockPos pos) { return state(pos).isAir(); }

    /** No living entities (or anything but items/xp) inside the block space. */
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

    /**
     * Finds a legit placement for {@code pos}: a solid neighbour whose clicked face is facing the eyes
     * (Grim checks you can't click a face pointing away from you) and within reach.
     */
    public static PlaceInfo placeInfo(BlockPos pos, boolean strictDirection, double range) {
        Vec3d eye = mc.player.getEyePos();
        PlaceInfo best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState ns = state(neighbor);
            if (ns.isReplaceable() || !ns.getFluidState().isEmpty() && ns.isReplaceable()) continue;
            if (ns.getCollisionShape(mc.world, neighbor).isEmpty()) continue;
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

    /** True if the eye is on the outward side of the face plane (the face can be seen). */
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

    /**
     * Places a block. With rotate, the click happens in the rotation callback (after the server has
     * our new look), which is what Grim's RotationPlace check expects.
     */
    public static boolean place(BlockPos pos, int hotbarSlot, boolean rotate, boolean strictDirection, double range, int priority) {
        if (!canPlace(pos)) return false;
        PlaceInfo info = placeInfo(pos, strictDirection, range);
        if (info == null) return false;
        Runnable click = () -> InvUtil.silentSwap(hotbarSlot, () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, info.hitResult());
            mc.player.swingHand(Hand.MAIN_HAND);
        });
        if (rotate) Bali.ROTATIONS.rotate(info.hitVec(), priority, click);
        else click.run();
        return true;
    }
}
