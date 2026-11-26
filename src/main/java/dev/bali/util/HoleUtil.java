package dev.bali.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HoleUtil {
    private HoleUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public static boolean blastProof(BlockState s) {
        return s.isOf(Blocks.BEDROCK) || s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.CRYING_OBSIDIAN)
                || s.isOf(Blocks.RESPAWN_ANCHOR) || s.isOf(Blocks.ENDER_CHEST) || s.isOf(Blocks.NETHERITE_BLOCK);
    }

    public static boolean blastProof(BlockPos p) { return blastProof(mc.world.getBlockState(p)); }

    public static boolean open(BlockPos p) {
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty();
    }

    public static Boolean holeType(BlockPos p) {
        if (!open(p) || !open(p.up()) || !open(p.up(2))) return null;
        boolean allBedrock = true;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState s = mc.world.getBlockState(p.offset(d));
            if (s.isOf(Blocks.BEDROCK)) continue;
            if (!blastProof(s)) return null;
            allBedrock = false;
        }
        return allBedrock;
    }

    public static List<BlockPos> holeCells(BlockPos p, boolean doubles) {
        if (holeType(p) != null) return List.of(p);
        if (!doubles || !open(p) || !open(p.up()) || !blastProof(p.down())) return List.of();
        BlockPos partner = null;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos q = p.offset(d);
            if (blastProof(q)) continue;
            if (partner != null) return List.of();
            partner = q;
        }
        if (partner == null || !open(partner) || !open(partner.up()) || !blastProof(partner.down())) return List.of();
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos q = partner.offset(d);
            if (!q.equals(p) && !blastProof(q)) return List.of();
        }
        return List.of(p, partner);
    }

    public static Set<BlockPos> footprint(Entity e) {
        Box box = e.getBoundingBox();
        int y = (int) Math.floor(e.getY() + 0.2);
        Set<BlockPos> feet = new LinkedHashSet<>();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1e-6); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1e-6); z++)
                feet.add(new BlockPos(x, y, z));
        return feet;
    }

    public static List<BlockPos> ring(Set<BlockPos> feet) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos f : feet) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos p = f.offset(d);
                if (!feet.contains(p) && !out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    public static boolean surrounded(Set<BlockPos> feet) {
        for (BlockPos p : ring(feet)) if (!blastProof(p)) return false;
        return true;
    }
}
