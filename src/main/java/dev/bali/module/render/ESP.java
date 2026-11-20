package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.EntityUtil;
import dev.bali.util.Render3D;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class ESP extends Module {
    public enum Mode { Box, Outline, Fill }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Box, "Box = fill + outline");
    private final NumberSetting lineWidth = number("Line Width", 1.5, 0.5, 5, 0.1, "Outline width");
    private final NumberSetting fillAlpha = number("Fill Alpha", 40, 0, 255, 1, "Fill transparency");
    private final BoolSetting players = bool("Players", true, "Show players");
    private final BoolSetting hostiles = bool("Hostiles", false, "Show hostile mobs");
    private final BoolSetting passives = bool("Passives", false, "Show passive mobs");
    private final BoolSetting items = bool("Items", false, "Show dropped items");
    private final BoolSetting crystals = bool("Crystals", false, "Show end crystals");
    private final ColorSetting playerColor = color("Player Color", 0xFFFF4040, "Players");
    private final ColorSetting friendColor = color("Friend Color", 0xFF40FFFF, "Friends");
    private final ColorSetting hostileColor = color("Hostile Color", 0xFFFF8020, "Hostile mobs");
    private final ColorSetting passiveColor = color("Passive Color", 0xFF40FF40, "Passive mobs");
    private final ColorSetting itemColor = color("Item Color", 0xFFFFFF40, "Items");
    private final ColorSetting crystalColor = color("Crystal Color", 0xFFC040FF, "Crystals");

    public ESP() {
        super("ESP", "See entities through walls", Category.RENDER);
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        for (Entity ent : mc.world.getEntities()) {
            if (ent == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
            Integer color = colorFor(ent);
            if (color == null) continue;
            Vec3d lerp = ent.getLerpedPos(e.tickDelta);
            Box box = ent.getBoundingBox().offset(lerp.subtract(ent.getEntityPos()));
            int fill = (color & 0x00FFFFFF) | (fillAlpha.getI() << 24);
            switch (mode.get()) {
                case Box -> Render3D.box(box, fill, color, lineWidth.getF());
                case Outline -> Render3D.boxOutline(box, color, lineWidth.getF());
                case Fill -> Render3D.boxFill(box, fill);
            }
        }
    }

    private Integer colorFor(Entity ent) {
        if (ent instanceof ItemEntity) return items.on() ? itemColor.argb() : null;
        if (ent instanceof EndCrystalEntity) return crystals.on() ? crystalColor.argb() : null;
        if (ent instanceof PlayerEntity p) {
            if (!players.on() || p.isSpectator()) return null;
            return EntityUtil.isFriend(p) ? friendColor.argb() : playerColor.argb();
        }
        return switch (EntityUtil.kind(ent)) {
            case HOSTILE -> hostiles.on() ? hostileColor.argb() : null;
            case PASSIVE -> passives.on() ? passiveColor.argb() : null;
            default -> null;
        };
    }
}
