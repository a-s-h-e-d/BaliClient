package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.HoleUtil;
import dev.bali.util.Render3D;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

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
                    Boolean type = HoleUtil.holeType(p);
                    if (type != null) found.add(new Hole(p, type));
                }
            }
        }
        holes = found;
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
