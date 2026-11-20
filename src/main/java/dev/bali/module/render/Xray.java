package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BlockListSetting;
import dev.bali.setting.BoolSetting;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

import java.util.List;
import java.util.Set;

/**
 * Only the listed blocks are meshed; everything else is skipped, cave culling is disabled so hidden
 * sections still render, and listed blocks are drawn on every side at full brightness.
 * Chunk meshing runs on worker threads, so the mixins read the immutable {@link #ACTIVE} snapshot.
 */
public final class Xray extends Module {
    /** Blocks to render while Xray is on, or null when off. Read from chunk builder threads. */
    public static volatile Set<Block> ACTIVE;
    public static volatile boolean SHOW_FLUIDS;

    public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks that stay visible (click to edit)", List.of(
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.NETHER_QUARTZ_ORE,
        Blocks.SPAWNER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
        Blocks.NETHER_PORTAL, Blocks.END_PORTAL_FRAME)));
    private final BoolSetting fluids = bool("Fluids", false, "Also render water and lava");

    private int appliedVersion = -1;
    private boolean appliedFluids;

    public Xray() {
        super("Xray", "See only chosen blocks", Category.RENDER);
    }

    @Override
    protected void onEnable() { apply(); }

    @Override
    protected void onDisable() {
        ACTIVE = null;
        reloadChunks();
    }

    /** Re-mesh when the block list or fluid option changes while enabled. */
    @EventHandler
    private void onTick(Events.TickPost e) {
        if (blocks.version() != appliedVersion || fluids.on() != appliedFluids) apply();
    }

    private void apply() {
        appliedVersion = blocks.version();
        appliedFluids = fluids.on();
        SHOW_FLUIDS = fluids.on();
        ACTIVE = Set.copyOf(blocks.get());
        reloadChunks();
    }

    private static void reloadChunks() {
        if (mc.worldRenderer != null && mc.world != null) mc.worldRenderer.reload();
    }

    public static boolean hides(Block b) {
        Set<Block> s = ACTIVE;
        return s != null && !s.contains(b);
    }

    public static boolean shows(Block b) {
        Set<Block> s = ACTIVE;
        return s != null && s.contains(b);
    }
}
