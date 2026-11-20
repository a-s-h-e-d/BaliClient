package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.Render3D;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

public final class StorageESP extends Module {
    private final BoolSetting chests = bool("Chests", true, "Chests and trapped chests");
    private final BoolSetting enderChests = bool("Ender Chests", true, "Ender chests");
    private final BoolSetting shulkers = bool("Shulkers", true, "Shulker boxes");
    private final BoolSetting barrels = bool("Barrels", true, "Barrels");
    private final BoolSetting other = bool("Other", false, "Hoppers, dispensers, droppers, furnaces");
    private final NumberSetting fillAlpha = number("Fill Alpha", 30, 0, 255, 1, "Fill transparency");
    private final BoolSetting tracers = bool("Tracers", false, "Lines from your crosshair to containers");
    private final NumberSetting tracerWidth = number("Tracer Width", 1.5, 0.5, 5, 0.1, "Tracer line width").visibleWhen(() -> tracers != null && tracers.on());
    private final ColorSetting chestColor = color("Chest Color", 0xFFFFA500, "Chests");
    private final ColorSetting echestColor = color("EChest Color", 0xFFA020F0, "Ender chests");
    private final ColorSetting shulkerColor = color("Shulker Color", 0xFFFF60B0, "Shulkers");
    private final ColorSetting barrelColor = color("Barrel Color", 0xFF9A6B3A, "Barrels");
    private final ColorSetting otherColor = color("Other Color", 0xFFA0A0A0, "Other");

    private int count;

    public StorageESP() {
        super("StorageESP", "See containers through walls", Category.RENDER);
    }

    @Override
    public String getInfo() { return String.valueOf(count); }

    @EventHandler
    private void onRender(Events.Render3D e) {
        var cam = mc.gameRenderer.getCamera();
        net.minecraft.util.math.Vec3d tracerStart = dev.bali.util.Render3D.crosshair();
        int view = mc.options.getClampedViewDistance();
        ChunkPos center = mc.player.getChunkPos();
        int c = 0;
        for (int x = center.x - view; x <= center.x + view; x++) {
            for (int z = center.z - view; z <= center.z + view; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    Integer color = colorFor(be);
                    if (color == null) continue;
                    c++;
                    Box box = new Box(be.getPos());
                    if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) box = box.contract(0.0625, 0, 0.0625).shrink(0, 0.125, 0);
                    Render3D.box(box, (color & 0x00FFFFFF) | (fillAlpha.getI() << 24), color, 1.5f);
                    if (tracers.on()) Render3D.lineFromCamera(tracerStart, box.getCenter(), color, tracerWidth.getF());
                }
            }
        }
        count = c;
    }

    private Integer colorFor(BlockEntity be) {
        if (be instanceof ChestBlockEntity) return chests.on() ? chestColor.argb() : null;
        if (be instanceof EnderChestBlockEntity) return enderChests.on() ? echestColor.argb() : null;
        if (be instanceof ShulkerBoxBlockEntity) return shulkers.on() ? shulkerColor.argb() : null;
        if (be instanceof BarrelBlockEntity) return barrels.on() ? barrelColor.argb() : null;
        if (be instanceof HopperBlockEntity || be instanceof DispenserBlockEntity || be instanceof AbstractFurnaceBlockEntity)
            return other.on() ? otherColor.argb() : null;
        return null;
    }
}
