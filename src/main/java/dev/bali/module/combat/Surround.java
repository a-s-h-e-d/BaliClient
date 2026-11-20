package dev.bali.module.combat;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.BlockUtil;
import dev.bali.util.InvUtil;
import dev.bali.util.Render3D;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Places obsidian around your feet. Works for 1x1 and when standing across multiple blocks. */
public final class Surround extends Module {
    private final NumberSetting bpt = number("Blocks/Tick", 4, 1, 8, 1, "Placements per tick");
    private final BoolSetting rotate = bool("Rotate", true, "Rotate to each placement (Grim RotationPlace)");
    private final BoolSetting strict = bool("Strict Direction", true, "Only click visible faces (Grim)");
    private final BoolSetting center = bool("Center", false, "Snap to block center on enable");
    private final BoolSetting disableOnJump = bool("Jump Disable", true, "Turn off when you jump/leave the hole");
    private final BoolSetting enderChests = bool("Ender Chests", true, "Use ender chests if out of obsidian");
    private final BoolSetting face = bool("Face", true, "Also fill the spots on top of your surround (head height), so crystals cannot sit at your face");
    private final NumberSetting range = number("Range", 4.5, 1, 6, 0.1, "Placement reach");
    private final ColorSetting color = color("Color", 0x402BB5C9, "Render color");

    /** NaN until we have a player: enabled from the config at launch, onEnable runs before any world exists. */
    private double startY = Double.NaN;
    private final Set<BlockPos> rendered = new LinkedHashSet<>();

    public Surround() {
        super("Surround", "Surrounds your feet with obsidian", Category.COMBAT);
    }

    @Override
    protected void onEnable() {
        startY = Double.NaN;
        if (nullCheck()) return;
        start();
    }

    private void start() {
        startY = mc.player.getY();
        if (center.on()) {
            double cx = Math.floor(mc.player.getX()) + 0.5, cz = Math.floor(mc.player.getZ()) + 0.5;
            mc.player.setPosition(cx, mc.player.getY(), cz);
        }
    }

    /** New server: measure jumps from where we are now, not from the last world. */
    @EventHandler
    private void onJoin(Events.Join e) { startY = Double.NaN; }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (Double.isNaN(startY)) start();
        if (disableOnJump.on() && mc.player.getY() > startY + 0.5) {
            toggle();
            return;
        }
        rendered.clear();
        int slot = InvUtil.findHotbar(Items.OBSIDIAN);
        if (slot == -1 && enderChests.on()) slot = InvUtil.findHotbar(Items.ENDER_CHEST);
        if (slot == -1) return;

        int placed = 0;
        for (BlockPos pos : positions()) {
            if (!BlockUtil.isReplaceable(pos)) continue;
            rendered.add(pos);
            if (placed >= bpt.getI()) continue;
            // only one rotation per tick can be sent, so with rotate on we place one block per tick
            if (rotate.on() && placed >= 1) continue;
            if (BlockUtil.place(pos, slot, rotate.on(), strict.on(), range.getD(), 80)) placed++;
        }
    }

    /** Positions around the blocks the player's hitbox occupies, plus support blocks if needed. */
    private List<BlockPos> positions() {
        List<BlockPos> out = new ArrayList<>();
        Box box = mc.player.getBoundingBox();
        int y = (int) Math.floor(mc.player.getY() + 0.2);
        Set<BlockPos> feet = new LinkedHashSet<>();
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1e-6); x++)
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1e-6); z++)
                feet.add(new BlockPos(x, y, z));
        for (BlockPos f : feet) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos p = f.offset(d);
                if (feet.contains(p)) continue;
                // need something to place against: add support block below if the spot is floating
                if (BlockUtil.placeInfo(p, strict.on(), range.getD()) == null && BlockUtil.isReplaceable(p.down())) out.add(p.down());
                out.add(p);
            }
        }
        if (face.on()) {
            // after the feet ring, so each face block has the surround block under it to be placed on
            List<BlockPos> heads = new ArrayList<>();
            for (BlockPos p : out) if (p.getY() == y) heads.add(p.up());
            out.addAll(heads);
        }
        return out;
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        for (BlockPos p : rendered) {
            if (mc.world.getBlockState(p).isOf(Blocks.OBSIDIAN)) continue;
            Render3D.box(new Box(p), color.argb(), color.withAlpha(255), 1f);
        }
    }
}
