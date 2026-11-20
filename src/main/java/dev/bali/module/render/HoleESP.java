package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.Render3D;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Highlights 1x1 safe holes: green = all bedrock, yellow = obsidian/mixed. */
public final class HoleESP extends Module {
    private final NumberSetting range = number("Range", 8, 2, 16, 1, "Horizontal scan radius");
    private final NumberSetting height = number("Height", 0.1, 0, 1, 0.05, "Render height");
    private final ColorSetting bedrock = color("Bedrock", 0x5040FF40, "Bedrock holes");
    private final ColorSetting obsidian = color("Obsidian", 0x50FFC040, "Obsidian holes");

    private record Hole(BlockPos pos, boolean bedrock) {}

    private List<Hole> holes = new ArrayList<>();
    private int timer;

    public HoleESP() {
        super("HoleESP", "Shows safe holes", Category.RENDER);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (timer-- > 0) return;
        timer = 4;
        List<Hole> found = new ArrayList<>();
        BlockPos c = mc.player.getBlockPos();
        int r = range.getI();
        for (int x = -r; x <= r; x++) {
            for (int y = -r / 2 - 1; y <= r / 2; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = c.add(x, y, z);
                    Boolean type = holeType(p);
                    if (type != null) found.add(new Hole(p, type));
                }
            }
        }
        holes = found;
    }

    /** null if not a hole, true if bedrock-only, false if obsidian/mixed. */
    private Boolean holeType(BlockPos p) {
        if (!air(p) || !air(p.up()) || !air(p.up(2))) return null;
        boolean allBedrock = true;
        for (Direction d : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState s = mc.world.getBlockState(p.offset(d));
            if (s.isOf(Blocks.BEDROCK)) continue;
            if (s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.CRYING_OBSIDIAN) || s.isOf(Blocks.RESPAWN_ANCHOR) || s.isOf(Blocks.ENDER_CHEST) || s.isOf(Blocks.NETHERITE_BLOCK)) {
                allBedrock = false;
                continue;
            }
            return null;
        }
        return allBedrock;
    }

    private boolean air(BlockPos p) {
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty();
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        for (Hole h : holes) {
            ColorSetting col = h.bedrock ? bedrock : obsidian;
            Box b = new Box(h.pos.getX(), h.pos.getY(), h.pos.getZ(), h.pos.getX() + 1, h.pos.getY() + height.getD(), h.pos.getZ() + 1);
            Render3D.box(b, col.argb(), col.withAlpha(255), 1.5f);
        }
    }
}
