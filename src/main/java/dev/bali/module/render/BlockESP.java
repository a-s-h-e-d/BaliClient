package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BlockListSetting;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.Render3D;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds every block from a configurable list in all loaded chunks. Chunks are scanned on a
 * background thread when they load (skipping sections that can't contain a match), kept up to date
 * from block update packets, and dropped when they unload.
 */
public final class BlockESP extends Module {
    public enum Mode { Box, Outline, Fill }
    public enum ColorMode { MapColor, Custom }

    public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks to highlight (click to edit)",
        List.of(Blocks.ANCIENT_DEBRIS, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.SPAWNER)));
    private final EnumSetting<Mode> mode = mode("Mode", Mode.Box, "Box = fill + outline");
    private final EnumSetting<ColorMode> colorMode = mode("Color Mode", ColorMode.MapColor, "Per-block map color or one custom color");
    private final ColorSetting customColor = color("Color", 0xFF2BB5C9, "Custom color").visibleWhen(() -> colorMode != null && colorMode.is(ColorMode.Custom));
    private final NumberSetting fillAlpha = number("Fill Alpha", 60, 0, 255, 1, "Fill transparency");
    private final NumberSetting lineWidth = number("Line Width", 1.5, 0.5, 5, 0.1, "Outline width");
    private final BoolSetting tracers = bool("Tracers", false, "Lines to found blocks");
    private final NumberSetting maxRender = number("Max Render", 1500, 50, 10000, 50, "Closest N blocks are drawn");
    private final NumberSetting minY = number("Min Y", -64, -64, 320, 1, "Ignore blocks below this Y");
    private final NumberSetting maxY = number("Max Y", 320, -64, 320, 1, "Ignore blocks above this Y");

    private final Map<Long, Set<BlockPos>> found = new ConcurrentHashMap<>();
    private final Map<Block, Integer> colorCache = new ConcurrentHashMap<>();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bali-BlockESP");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Map colors are often useless (ores are "stone gray"), so common search targets get real colors. */
    private static final Map<Block, Integer> KNOWN_COLORS = Map.ofEntries(
        Map.entry(Blocks.DIAMOND_ORE, 0x33EBCB), Map.entry(Blocks.DEEPSLATE_DIAMOND_ORE, 0x33EBCB),
        Map.entry(Blocks.ANCIENT_DEBRIS, 0xB0603A),
        Map.entry(Blocks.EMERALD_ORE, 0x17DD62), Map.entry(Blocks.DEEPSLATE_EMERALD_ORE, 0x17DD62),
        Map.entry(Blocks.GOLD_ORE, 0xFCEE4B), Map.entry(Blocks.DEEPSLATE_GOLD_ORE, 0xFCEE4B), Map.entry(Blocks.NETHER_GOLD_ORE, 0xFCEE4B),
        Map.entry(Blocks.IRON_ORE, 0xD8AF93), Map.entry(Blocks.DEEPSLATE_IRON_ORE, 0xD8AF93),
        Map.entry(Blocks.LAPIS_ORE, 0x2546BD), Map.entry(Blocks.DEEPSLATE_LAPIS_ORE, 0x2546BD),
        Map.entry(Blocks.REDSTONE_ORE, 0xFF2020), Map.entry(Blocks.DEEPSLATE_REDSTONE_ORE, 0xFF2020),
        Map.entry(Blocks.COAL_ORE, 0x404040), Map.entry(Blocks.DEEPSLATE_COAL_ORE, 0x404040),
        Map.entry(Blocks.COPPER_ORE, 0xE07A45), Map.entry(Blocks.DEEPSLATE_COPPER_ORE, 0xE07A45),
        Map.entry(Blocks.NETHER_QUARTZ_ORE, 0xEEE6DE),
        Map.entry(Blocks.SPAWNER, 0xFF40FF), Map.entry(Blocks.TRIAL_SPAWNER, 0xFF40FF), Map.entry(Blocks.VAULT, 0xFF40FF),
        Map.entry(Blocks.NETHER_PORTAL, 0xA040FF), Map.entry(Blocks.END_PORTAL_FRAME, 0x3C8C6E), Map.entry(Blocks.END_GATEWAY, 0x3C8C6E),
        Map.entry(Blocks.BEDROCK, 0x707070), Map.entry(Blocks.OBSIDIAN, 0x5A2A8A), Map.entry(Blocks.CRYING_OBSIDIAN, 0x8A2AD0),
        Map.entry(Blocks.BUDDING_AMETHYST, 0xB070FF), Map.entry(Blocks.SPONGE, 0xE5E54F), Map.entry(Blocks.WET_SPONGE, 0xC8C84A),
        Map.entry(Blocks.BEACON, 0x7FFFFF), Map.entry(Blocks.CONDUIT, 0x7FFFFF), Map.entry(Blocks.DRAGON_EGG, 0x300040),
        Map.entry(Blocks.ENDER_CHEST, 0xA020F0), Map.entry(Blocks.CHEST, 0xFFA500), Map.entry(Blocks.TRAPPED_CHEST, 0xFF6000),
        Map.entry(Blocks.BARREL, 0x9A6B3A), Map.entry(Blocks.HOPPER, 0x808080), Map.entry(Blocks.CRAFTER, 0x808080)
    );

    private int scannedVersion = -1;
    private int total;

    public BlockESP() {
        super("BlockESP", "Highlights chosen blocks (search)", Category.RENDER);
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (isEnabled()) queueScan(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> found.remove(chunk.getPos().toLong()));
    }

    @Override
    public String getInfo() { return String.valueOf(total); }

    @Override
    protected void onEnable() { rescanAll(); }

    @Override
    protected void onDisable() { found.clear(); }

    // ------------------------------------------------------------------ scanning

    private void rescanAll() {
        found.clear();
        colorCache.clear();
        scannedVersion = blocks.version();
        if (nullCheck()) return;
        int view = mc.options.getClampedViewDistance() + 1;
        ChunkPos c = mc.player.getChunkPos();
        for (int x = c.x - view; x <= c.x + view; x++)
            for (int z = c.z - view; z <= c.z + view; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) queueScan(chunk);
            }
    }

    private void queueScan(WorldChunk chunk) {
        Set<Block> targets = Set.copyOf(blocks.get());
        if (targets.isEmpty()) return;
        scanner.execute(() -> {
            try {
                scanChunk(chunk, targets);
            } catch (Throwable t) {
                Bali.LOG.debug("BlockESP scan failed", t); // chunk unloaded/changed mid-scan, harmless
            }
        });
    }

    private void scanChunk(WorldChunk chunk, Set<Block> targets) {
        Set<BlockPos> out = ConcurrentHashMap.newKeySet();
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(st -> targets.contains(st.getBlock()))) continue;
            int baseY = chunk.sectionIndexToCoord(i) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (targets.contains(s.getBlockState(x, y, z).getBlock())) out.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
        }
        long key = chunk.getPos().toLong();
        if (out.isEmpty()) found.remove(key);
        else found.put(key, out);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (blocks.version() != scannedVersion) rescanAll();
        int t = 0;
        for (Set<BlockPos> s : found.values()) t += s.size();
        total = t;
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (e.packet instanceof BlockUpdateS2CPacket p) update(p.getPos(), p.getState());
        else if (e.packet instanceof ChunkDeltaUpdateS2CPacket p) p.visitUpdates(this::update);
    }

    private void update(BlockPos pos, BlockState state) {
        long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (blocks.contains(state.getBlock())) {
            found.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(pos.toImmutable());
        } else {
            Set<BlockPos> s = found.get(key);
            if (s != null) s.remove(pos);
        }
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Events.Render3D e) {
        Vec3d eye = e.cameraPos;
        List<BlockPos> list = new ArrayList<>();
        int lo = minY.getI(), hi = maxY.getI();
        for (Set<BlockPos> s : found.values())
            for (BlockPos p : s)
                if (p.getY() >= lo && p.getY() <= hi) list.add(p);
        int limit = maxRender.getI();
        if (list.size() > limit) {
            list.sort((a, b) -> Double.compare(a.getSquaredDistance(eye), b.getSquaredDistance(eye)));
            list = list.subList(0, limit);
        }

        Camera cam = mc.gameRenderer.getCamera();
        Vec3d tracerStart = dev.bali.util.Render3D.crosshair();
        for (BlockPos p : list) {
            BlockState st = mc.world.getBlockState(p);
            if (!blocks.contains(st.getBlock())) continue; // stale entry
            int color = colorFor(st, p);
            int fill = (color & 0x00FFFFFF) | (fillAlpha.getI() << 24);
            Box box = boxFor(st, p);
            switch (mode.get()) {
                case Box -> Render3D.box(box, fill, color, lineWidth.getF());
                case Outline -> Render3D.boxOutline(box, color, lineWidth.getF());
                case Fill -> Render3D.boxFill(box, fill);
            }
            if (tracers.on()) Render3D.lineFromCamera(tracerStart, box.getCenter(), color, lineWidth.getF());
        }
    }

    private Box boxFor(BlockState st, BlockPos p) {
        VoxelShape shape = st.getOutlineShape(mc.world, p);
        if (shape.isEmpty()) return new Box(p);
        return shape.getBoundingBox().offset(p);
    }

    private int colorFor(BlockState st, BlockPos p) {
        if (colorMode.is(ColorMode.Custom)) return customColor.argb() | 0xFF000000;
        return colorCache.computeIfAbsent(st.getBlock(), b -> {
            Integer known = KNOWN_COLORS.get(b);
            if (known != null) return 0xFF000000 | known;
            int rgb = st.getMapColor(mc.world, p).color;
            if (rgb == 0) rgb = 0xFFFFFF;
            return 0xFF000000 | rgb;
        });
    }
}
