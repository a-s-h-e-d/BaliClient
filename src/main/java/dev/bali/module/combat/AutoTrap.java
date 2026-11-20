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
import dev.bali.util.EntityUtil;
import dev.bali.util.HoleUtil;
import dev.bali.util.InvUtil;
import dev.bali.util.Placer;
import dev.bali.util.Render3D;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Traps the nearest enemy: obsidian around the 1x1, 2-tall space they stand in (feet and head) plus
 * a roof. Standing across two or four blocks, the trap covers all of them.
 *
 * The roof has nothing under it (their head is there), so it goes against a helper block on top of
 * the head ring. Grim only lets you click a face you can see, so the helper goes on a side whose face
 * toward the roof points at us, and the roof (and helper) need our eyes above the top of the ring.
 */
public final class AutoTrap extends Module {
    private final NumberSetting targetRange = number("Target Range", 5, 1, 8, 0.5, "Max distance to the target");
    private final BoolSetting feet = bool("Feet", true, "Also fill the ring at their feet (already there if they're in a hole)");
    private final BoolSetting top = bool("Top", true, "Put a roof over their head (needs you higher than their head)");
    private final NumberSetting bpt = number("Blocks/Tick", 4, 1, 8, 1, "Placements per tick (one with Rotate)");
    private final BoolSetting rotate = bool("Rotate", true, "Rotate to each placement (Grim RotationPlace)");
    private final BoolSetting strict = bool("Strict Direction", true, "Only click visible faces (Grim)");
    private final BoolSetting enderChests = bool("Ender Chests", true, "Use ender chests if out of obsidian");
    private final NumberSetting range = number("Range", 4.5, 1, 6, 0.1, "Placement reach");
    private final ColorSetting color = color("Color", 0x40B050FF, "Render color");

    private final Placer placer = new Placer();
    private PlayerEntity target;
    private List<BlockPos> pending = List.of();

    public AutoTrap() {
        super("AutoTrap", "Traps the nearest player in obsidian", Category.COMBAT);
    }

    @Override
    public String getInfo() { return target == null ? null : target.getGameProfile().name(); }

    @Override
    protected void onDisable() {
        target = null;
        pending = List.of();
        placer.clear();
    }

    @EventHandler
    private void onJoin(Events.Join e) { placer.clear(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        target = nearest();
        int slot = InvUtil.findHotbar(Items.OBSIDIAN);
        if (slot == -1 && enderChests.on()) slot = InvUtil.findHotbar(Items.ENDER_CHEST);
        if (target == null || slot == -1) {
            pending = List.of();
            return;
        }
        pending = placer.placeAll(positions(target), slot, rotate.on(), strict.on(), range.getD(), bpt.getI(), RotationPriority.AUTO_TRAP);
    }

    private PlayerEntity nearest() {
        PlayerEntity best = null;
        double bestSq = targetRange.getD() * targetRange.getD();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!EntityUtil.isAliveTarget(p) || EntityUtil.isFriend(p)) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d <= bestSq) {
                bestSq = d;
                best = p;
            }
        }
        return best;
    }

    /** In placing order: feet ring (with supports), head ring, then helper and roof per cell. */
    private List<BlockPos> positions(PlayerEntity t) {
        Set<BlockPos> cells = HoleUtil.footprint(t);
        List<BlockPos> ring = HoleUtil.ring(cells);
        List<BlockPos> out = new ArrayList<>();
        if (feet.on()) {
            for (BlockPos p : ring) {
                if (BlockUtil.placeInfo(p, strict.on(), range.getD()) == null && BlockUtil.isReplaceable(p.down())) out.add(p.down());
                out.add(p);
            }
        }
        for (BlockPos p : ring) out.add(p.up());
        if (top.on()) {
            List<BlockPos> roofs = new ArrayList<>();
            for (BlockPos c : cells) {
                BlockPos roof = c.up(2);
                BlockPos helper = needsHelper(roof, roofs) ? helper(roof) : null;
                if (helper != null) out.add(helper);
                out.add(roof);
                roofs.add(roof);
            }
        }
        return out;
    }

    /**
     * Nothing solid next to the roof to click. A roof cell placed earlier in the list counts: across
     * two blocks, the first roof gets the helper and the second goes against the first.
     */
    private boolean needsHelper(BlockPos roof, List<BlockPos> earlierRoofs) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            BlockPos n = roof.offset(d);
            if (!BlockUtil.isReplaceable(n) || earlierRoofs.contains(n)) return false;
        }
        return true;
    }

    /** The spot beside the roof, on top of the head ring, whose face toward the roof we can see. */
    private BlockPos helper(BlockPos roof) {
        Vec3d eye = mc.player.getEyePos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos h = roof.offset(d);
            if (strict.on() && !BlockUtil.faceVisible(eye, h, d.getOpposite())) continue;
            double sq = eye.squaredDistanceTo(Vec3d.ofCenter(h));
            if (sq < bestSq) {
                bestSq = sq;
                best = h;
            }
        }
        return best;
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        for (BlockPos p : pending) Render3D.box(new Box(p), color.argb(), color.withAlpha(255), 1f);
    }
}
