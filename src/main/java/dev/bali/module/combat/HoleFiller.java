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
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HoleFiller extends Module {
    private final NumberSetting targetRange = number("Target Range", 3, 1, 8, 0.5, "Fill holes within this distance of an enemy");
    private final BoolSetting allHoles = bool("All Holes", false, "Fill every hole in reach, not just those near enemies");
    private final BoolSetting doubles = bool("Doubles", true, "Also fill 2x1 holes");
    private final NumberSetting bpt = number("Blocks/Tick", 2, 1, 8, 1, "Placements per tick (one with Rotate)");
    private final BoolSetting rotate = bool("Rotate", true, "Rotate to each placement (Grim RotationPlace)");
    private final BoolSetting strict = bool("Strict Direction", true, "Only click visible faces (Grim)");
    private final BoolSetting enderChests = bool("Ender Chests", true, "Use ender chests if out of obsidian");
    private final NumberSetting range = number("Range", 4.5, 1, 6, 0.1, "Placement reach");
    private final ColorSetting color = color("Color", 0x40FF5050, "Render color");

    private final Placer placer = new Placer();
    private List<BlockPos> pending = List.of();

    public HoleFiller() {
        super("HoleFiller", "Fills holes near enemies so they can't hide in them", Category.COMBAT);
        setKey(GLFW.GLFW_KEY_X);
    }

    @Override
    protected void onDisable() {
        pending = List.of();
        placer.clear();
    }

    @EventHandler
    private void onJoin(Events.Join e) { placer.clear(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        int slot = InvUtil.findHotbar(Items.OBSIDIAN);
        if (slot == -1 && enderChests.on()) slot = InvUtil.findHotbar(Items.ENDER_CHEST);
        if (slot == -1) {
            pending = List.of();
            return;
        }
        pending = placer.placeAll(targets(), slot, rotate.on(), strict.on(), range.getD(), bpt.getI(), RotationPriority.HOLE_FILLER);
    }

    private List<BlockPos> targets() {
        List<PlayerEntity> enemies = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers())
            if (EntityUtil.isAliveTarget(p) && !EntityUtil.isFriend(p)) enemies.add(p);
        if (enemies.isEmpty() && !allHoles.on()) return List.of();

        Set<BlockPos> mine = HoleUtil.footprint(mc.player);
        Vec3d eye = mc.player.getEyePos();
        double reachSq = (range.getD() + 1) * (range.getD() + 1);
        int r = (int) Math.ceil(range.getD());
        BlockPos c = mc.player.getBlockPos();
        Set<BlockPos> seen = new LinkedHashSet<>();
        Map<BlockPos, Double> enemyDist = new HashMap<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = c.add(x, y, z);
                    if (seen.contains(p) || eye.squaredDistanceTo(Vec3d.ofCenter(p)) > reachSq) continue;
                    List<BlockPos> cells = HoleUtil.holeCells(p, doubles.on());
                    if (cells.isEmpty()) continue;
                    seen.addAll(cells);
                    if (cells.stream().anyMatch(mine::contains) || cells.stream().anyMatch(q -> !BlockUtil.noEntityCollision(q))) continue;
                    double d = Double.MAX_VALUE;
                    for (BlockPos q : cells) {
                        Vec3d center = Vec3d.ofBottomCenter(q);
                        for (PlayerEntity en : enemies) d = Math.min(d, en.getEntityPos().distanceTo(center));
                    }
                    if (!allHoles.on() && d > targetRange.getD()) continue;
                    for (BlockPos q : cells) enemyDist.put(q, d);
                }
            }
        }
        List<BlockPos> out = new ArrayList<>(enemyDist.keySet());
        out.sort(Comparator.comparingDouble(enemyDist::get));
        return out;
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        for (BlockPos p : pending) Render3D.box(new Box(p), color.argb(), color.withAlpha(255), 1f);
    }
}
