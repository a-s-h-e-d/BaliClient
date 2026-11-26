package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.EntityUtil;
import dev.bali.util.HoleUtil;
import dev.bali.util.Render3D;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Set;

public final class FootESP extends Module {
    private final NumberSetting range = number("Range", 16, 4, 64, 1, "Max distance to players");
    private final BoolSetting self = bool("Self", false, "Also show your own feet");
    private final BoolSetting friends = bool("Friends", true, "Show friends");
    private final NumberSetting height = number("Height", 0.1, 0.02, 1, 0.02, "Render height");
    private final ColorSetting safe = color("Safe", 0x5040FF40, "Surrounded by blast-proof blocks");
    private final ColorSetting unsafe = color("Unsafe", 0x50FF4040, "A gap at their feet");

    public FootESP() {
        super("FootESP", "Shows where players are standing and whether their feet are safe", Category.RENDER);
    }

    public boolean safe(PlayerEntity p) {
        return HoleUtil.surrounded(HoleUtil.footprint(p));
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        double rangeSq = range.getD() * range.getD();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player ? !self.on() : !EntityUtil.isAliveTarget(p)) continue;
            if (!friends.on() && EntityUtil.isFriend(p)) continue;
            if (mc.player.squaredDistanceTo(p) > rangeSq) continue;
            Set<BlockPos> feet = HoleUtil.footprint(p);
            ColorSetting col = HoleUtil.surrounded(feet) ? safe : unsafe;
            for (BlockPos f : feet) {
                Box b = new Box(f.getX(), f.getY(), f.getZ(), f.getX() + 1, f.getY() + height.getD(), f.getZ() + 1);
                Render3D.box(b, col.argb(), col.withAlpha(255), 1.5f);
            }
        }
    }
}
