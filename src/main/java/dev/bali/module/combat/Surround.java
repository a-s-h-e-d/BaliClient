package dev.bali.module.combat;

import dev.bali.manager.RotationPriority;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.BlockUtil;
import dev.bali.util.HoleUtil;
import dev.bali.util.InvUtil;
import dev.bali.util.Placer;
import dev.bali.util.Render3D;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Surround extends Module {
    private final NumberSetting bpt = number("Blocks/Tick", 4, 1, 8, 1, "Placements per tick");
    private final BoolSetting rotate = bool("Rotate", true, "Rotate to each placement (Grim RotationPlace)");
    private final BoolSetting strict = bool("Strict Direction", true, "Only click visible faces (Grim)");
    private final BoolSetting center = bool("Center", false, "Snap to block center on enable");
    private final BoolSetting disableOnJump = bool("Jump Disable", true, "Turn off when you jump/leave the hole");
    private final BoolSetting enderChests = bool("Ender Chests", true, "Use ender chests if out of obsidian");
    private final BoolSetting face = bool("Face", true, "Also fill the spots on top of your surround (head height), so crystals cannot sit at your face");
    private final NumberSetting range = number("Range", 4.5, 1, 6, 0.1, "Placement reach");
    private final BoolSetting breakCrystals = bool("Break Crystals", true, "Break a crystal sitting in a surround spot and fill the spot in the same tick (anti-city). Skipped if the blast would kill you");
    private final ColorSetting color = color("Color", 0x402BB5C9, "Render color");

    private double startY = Double.NaN;
    private final Set<BlockPos> rendered = new LinkedHashSet<>();
    private final Placer placer = new Placer();

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

    @EventHandler
    private void onJoin(Events.Join e) {
        startY = Double.NaN;
        placer.clear();
    }

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
        boolean rotated = false;
        List<BlockPos> wanted = positions();
        placer.tick(wanted);
        for (BlockPos pos : wanted) {
            if (!BlockUtil.isReplaceable(pos)) {
                placer.filled(pos);
                continue;
            }
            rendered.add(pos);
            if (placed >= bpt.getI()) continue;
            if (rotate.on() && placed >= 1) continue;
            if (!placer.ready(pos)) continue;
            Runnable clicked = placer.clicked(pos);
            if (!BlockUtil.noEntityCollision(pos)) {
                if (breakCrystals.on() && !rotated
                        && BlockUtil.placeBreakingCrystals(pos, slot, strict.on(), range.getD(), RotationPriority.SURROUND, maxSelf(), clicked)) {
                    placed++;
                    rotated = true;
                }
                continue;
            }
            if (BlockUtil.place(pos, slot, rotate.on(), strict.on(), range.getD(), RotationPriority.SURROUND, clicked)) {
                placed++;
                rotated |= rotate.on();
            }
        }
    }

    private float maxSelf() {
        return dev.bali.util.DamageUtil.health(mc.player) - 1f;
    }

    private List<BlockPos> positions() {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> feet = HoleUtil.footprint(mc.player);
        int y = feet.iterator().next().getY();
        for (BlockPos p : HoleUtil.ring(feet)) {
            if (BlockUtil.placeInfo(p, strict.on(), range.getD()) == null && BlockUtil.isReplaceable(p.down())) out.add(p.down());
            out.add(p);
        }
        if (face.on()) {
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
