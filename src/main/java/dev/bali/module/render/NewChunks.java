package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.ChatUtil;
import dev.bali.util.Render3D;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chunk trail finder for 2b2t. Three independent detectors:
 *
 * Palette (trail): tells chunks nobody has loaded before from chunks the server read back from
 *   its disk, i.e. somebody was here, at any time. The palette-order idea comes from etianl's
 *   Trouser-Streak (NewerNewChunks, "the origin of the Palette based tracing method for
 *   NewChunks"); this is our own implementation of it. Why it works: a chunk section's block and
 *   biome palettes go out in the order the server holds them. A section generated just now still
 *   has its palettes in generation order: the block palette starts with air (the empty section
 *   generation starts from) and the biome palette with plains (the biome every section starts
 *   as), even where neither is used any more. Saving a chunk rewrites each palette in position
 *   order with unused entries dropped, and that is what a chunk loaded from disk sends. So a
 *   block palette that starts with air while the section's first block is something else, or a
 *   biome entry no position uses, means freshly generated; a palette in position order means
 *   loaded from disk. Read from the raw packet bytes, before the client touches the chunk.
 *
 * Liquid (live): freshly generated chunks get fluid flow updates right after generation; old chunks
 *   already contain settled flowing fluid. Shows chunks being generated now (someone just passed).
 *
 * Version (history): 2b2t went from 1.12 to 1.19 in 2023. Chunks generated since then contain
 *   blocks 1.12 never generated: in the Nether nether gold ore, blackstone, basalt, soul soil, nylium;
 *   in the Overworld copper ore, tuff, calcite, amethyst, dripstone above y=0 (below 0 is new terrain
 *   even in old chunks). Shows every route traveled since the update.
 *
 * Purely reads chunk data the server sends; nothing is sent, so there's no anticheat exposure.
 * Results are saved per server and dimension in .minecraft/bali/chunks/.
 */
public final class NewChunks extends Module {
    private enum Liquid { NEW, OLD }
    private enum Version { NEW, OLD }
    public enum Palette { NEW, OLD }

    private final BoolSetting palette = bool("Palette", true, "Trail: new = nobody loaded it before, old = someone was here (palette order, idea from Trouser-Streak by etianl)");
    private final BoolSetting liquid = bool("Liquid", true, "Live: chunks generating right now (fluid flow)");
    private final BoolSetting version = bool("Version", true, "History: chunks generated since 2b2t's 1.19 update");
    private final BoolSetting showOld = bool("Show Old", true, "Also draw chunks detected as old");
    public enum RenderAt { Bedrock, Feet, Custom }

    private final dev.bali.setting.EnumSetting<RenderAt> renderAt = mode("Render At", RenderAt.Bedrock, "Bedrock = bottom of the world (out of the way)");
    private final NumberSetting yOffset = number("Y", 0, -64, 320, 1, "Offset for Feet, or the height for Custom").visibleWhen(() -> renderAt != null && !renderAt.is(RenderAt.Bedrock));
    private final NumberSetting renderDistance = number("Distance", 32, 4, 64, 1, "Max chunks away to draw");
    private final BoolSetting outline = bool("Outline", true, "Draw chunk borders");
    private final BoolSetting save = bool("Save", true, "Remember chunks per server/dimension");
    private final ColorSetting newColor = color("New", 0x40FF3030, "Liquid: new chunk");
    private final ColorSetting versionColor = color("Since 1.19", 0x38FFC020, "Version: generated since the update");
    private final ColorSetting oldColor = color("Old", 0x1C30FF30, "Old chunk");

    private final Map<Long, Palette> paletteState = new ConcurrentHashMap<>();
    private final Map<Long, Liquid> liquidState = new ConcurrentHashMap<>();
    private final Map<Long, Version> versionState = new ConcurrentHashMap<>();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bali-NewChunks");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final Set<Block> NETHER_NEW = Set.of(Blocks.NETHER_GOLD_ORE, Blocks.BLACKSTONE, Blocks.BASALT,
        Blocks.SOUL_SOIL, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.ANCIENT_DEBRIS, Blocks.CRIMSON_STEM,
        Blocks.WARPED_STEM, Blocks.SHROOMLIGHT, Blocks.GILDED_BLACKSTONE);
    private static final Set<Block> OVERWORLD_NEW = Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.TUFF,
        Blocks.CALCITE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.POINTED_DRIPSTONE, Blocks.DRIPSTONE_BLOCK,
        Blocks.SMOOTH_BASALT, Blocks.MOSS_BLOCK, Blocks.AZALEA, Blocks.GLOW_LICHEN, Blocks.RAW_COPPER_BLOCK);

    private String loadedKey;

    public NewChunks() {
        super("NewChunks", "Shows new/old chunks to follow trails", Category.RENDER);
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (isEnabled()) queueScan(chunk);
        });
    }

    @Override
    public String getInfo() {
        if (palette.on()) {
            int n = 0, o = 0;
            for (Palette p : paletteState.values()) if (p == Palette.NEW) n++; else o++;
            return n + " new/" + o + " old";
        }
        int n = 0, v = 0;
        for (Liquid l : liquidState.values()) if (l == Liquid.NEW) n++;
        for (Version x : versionState.values()) if (x == Version.NEW) v++;
        return n + "/" + v;
    }

    /**
     * What this module draws for a chunk, as {@link dev.bali.util.StashMap} NC_* (0 = nothing):
     * new / generated since 1.19 / old, decided exactly like the 3D overlay.
     */
    public byte mapVerdict(int chunkX, int chunkZ) {
        long k = ChunkPos.toLong(chunkX, chunkZ);
        Palette p = palette.on() ? paletteState.get(k) : null;
        Liquid l = liquidState.get(k);
        Version v = versionState.get(k);
        boolean isNew = p == Palette.NEW || p == null && l == Liquid.NEW;
        boolean isOld = p == Palette.OLD || p == null && l == Liquid.OLD || v == Version.OLD;
        if (isNew) return dev.bali.util.StashMap.NC_NEW;
        if (v == Version.NEW) return dev.bali.util.StashMap.NC_SINCE;
        if (isOld) return dev.bali.util.StashMap.NC_OLD;
        return dev.bali.util.StashMap.NC_NONE;
    }

    /** Overlay colors for the stash map, from this module's color settings (index = NC_*). */
    public dev.bali.util.StashMap.Overlay mapOverlay() {
        int[] c = new int[4];
        c[dev.bali.util.StashMap.NC_NEW] = 0x60000000 | (newColor.argb() & 0xFFFFFF);
        c[dev.bali.util.StashMap.NC_SINCE] = 0x50000000 | (versionColor.argb() & 0xFFFFFF);
        c[dev.bali.util.StashMap.NC_OLD] = showOld.on() ? 0x48000000 | (oldColor.argb() & 0xFFFFFF) : 0;
        return new dev.bali.util.StashMap.Overlay(c);
    }

    /** Palette verdict of a chunk, or null if not seen/undecided (tests, other modules). */
    public Palette paletteOf(int chunkX, int chunkZ) { return paletteState.get(ChunkPos.toLong(chunkX, chunkZ)); }

    @Override
    protected void onEnable() {
        if (nullCheck()) return;
        switchWorld();
        int view = mc.options.getClampedViewDistance() + 1;
        ChunkPos c = mc.player.getChunkPos();
        for (int x = c.x - view; x <= c.x + view; x++)
            for (int z = c.z - view; z <= c.z + view; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) queueScan(chunk);
            }
    }

    @Override
    protected void onDisable() {
        persist();
        paletteState.clear();
        liquidState.clear();
        versionState.clear();
        loadedKey = null;
    }

    @EventHandler
    private void onLeave(Events.Leave e) {
        persist();
        paletteState.clear();
        liquidState.clear();
        versionState.clear();
        loadedKey = null;
    }

    /** Dimension changes (portals) swap to that dimension's saved data. */
    @EventHandler
    private void onTick(Events.TickPost e) {
        if (!key().equals(loadedKey)) switchWorld();
        if (mc.player.age % 1200 == 0) persist(); // autosave every minute
    }

    // ------------------------------------------------------------------ liquid detector

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (e.packet instanceof net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket p) {
            if (palette.on()) queuePalette(p);
            return;
        }
        if (!liquid.on()) return;
        if (e.packet instanceof BlockUpdateS2CPacket p) fluidUpdate(p.getPos(), p.getState());
        else if (e.packet instanceof ChunkDeltaUpdateS2CPacket p) p.visitUpdates(this::fluidUpdate);
    }

    private void fluidUpdate(BlockPos pos, BlockState state) {
        FluidState f = state.getFluidState();
        if (f.isEmpty() || f.isStill()) return;
        long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        // a flow update in a chunk we already saw settled flowing fluid in = player-made flow, ignore
        if (liquidState.get(key) == Liquid.OLD) return;
        liquidState.put(key, Liquid.NEW);
    }

    // ------------------------------------------------------------------ palette detector

    /** Packet palettes up to these bits carry a local palette; above that the ids are global. */
    private static final int BLOCK_INDIRECT_MAX = 8, BIOME_INDIRECT_MAX = 3;
    private static final int BLOCKS = 4096, BIOMES = 64;
    private static volatile boolean parseWarned;
    /** Tests: keep the evidence behind each verdict. */
    public static volatile boolean debugReasons;
    private final Map<Long, String> reasons = new ConcurrentHashMap<>();

    public String reasonOf(int chunkX, int chunkZ) { return reasons.get(ChunkPos.toLong(chunkX, chunkZ)); }

    private void queuePalette(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket p) {
        if (mc.world == null) return;
        long key = ChunkPos.toLong(p.getChunkX(), p.getChunkZ());
        if (paletteState.containsKey(key)) return; // first verdict sticks (a chunk we generated stays new)
        int sections = mc.world.countVerticalSections();
        int plains;
        try {
            var biomes = mc.world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            plains = biomes.getRawId(biomes.getValueOrThrow(net.minecraft.world.biome.BiomeKeys.PLAINS));
        } catch (Exception ex) {
            plains = -1;
        }
        // a fresh wrapper over the packet's bytes: safe to read on our thread
        net.minecraft.network.PacketByteBuf buf = p.getChunkData().getSectionsDataBuf();
        int plainsId = plains;
        scanner.execute(() -> {
            try {
                StringBuilder why = debugReasons ? new StringBuilder() : null;
                Palette verdict = classify(buf, sections, plainsId, why);
                if (verdict != null) paletteState.putIfAbsent(key, verdict);
                if (why != null) reasons.put(key, verdict + " " + why);
            } catch (Throwable t) {
                if (!parseWarned) {
                    parseWarned = true;
                    Bali.LOG.warn("NewChunks: couldn't read chunk palettes (packet format changed?)", t);
                }
            }
        });
    }

    /** One paletted container as sent: palette (raw ids; null = single value or global ids) and packed storage. */
    private record Container(int bits, int single, int[] palette, long[] data) {
        /** Palette index (or raw id for global storage) at storage index i. */
        int at(int i) {
            if (bits == 0) return 0;
            int per = 64 / bits;
            return (int) ((data[i / per] >>> ((i % per) * bits)) & ((1L << bits) - 1));
        }
    }

    private static Container read(net.minecraft.network.PacketByteBuf buf, int size, int indirectMax) {
        int bits = buf.readUnsignedByte();
        int single = -1;
        int[] pal = null;
        if (bits == 0) single = buf.readVarInt();
        else if (bits <= indirectMax) {
            pal = new int[buf.readVarInt()];
            for (int i = 0; i < pal.length; i++) pal[i] = buf.readVarInt();
        }
        long[] data = new long[bits == 0 ? 0 : (size + 64 / bits - 1) / (64 / bits)];
        for (int i = 0; i < data.length; i++) data[i] = buf.readLong();
        return new Container(bits, single, pal, data);
    }

    /** NEW / OLD vote of one section's container, or 0 for no opinion. */
    private static final int NO_VOTE = 0, VOTE_NEW = 1, VOTE_OLD = 2;

    /**
     * Blocks: generation starts from an all-air section, so a fresh palette starts with air. On disk
     * the palette is rebuilt in position order, so it starts with the block at the section's first
     * position. Air first while that block is not air = fresh; that block first (not air) = from disk.
     */
    private static int blockVote(Container c) {
        if (c.palette == null || c.palette.length < 2 || c.at(0) >= c.palette.length) return NO_VOTE;
        BlockState first = Block.STATE_IDS.get(c.palette[0]);
        BlockState atZero = Block.STATE_IDS.get(c.palette[c.at(0)]);
        if (first == null || atZero == null) return NO_VOTE;
        if (first.isOf(Blocks.AIR) && !atZero.isAir()) return VOTE_NEW;
        if (!first.isAir() && c.at(0) == 0) return VOTE_OLD;
        return NO_VOTE;
    }

    /**
     * Biomes (untouched by anti-xray): every section starts as plains and generation overwrites it, so
     * a fresh palette can hold entries no position uses; a saved one never does and is in position
     * order. Unused entry = fresh. Position order = from disk, unless plains sits first (that is
     * where the starting plains would be, so it proves nothing).
     */
    private static int biomeVote(Container c, int plainsId) {
        if (c.palette == null || c.palette.length < 2) return NO_VOTE;
        int n = c.palette.length;
        int[] first = new int[n];
        java.util.Arrays.fill(first, -1);
        for (int i = 0; i < BIOMES; i++) {
            int id = c.at(i);
            if (id >= n) return NO_VOTE; // not what we think it is
            if (first[id] < 0) first[id] = i;
        }
        for (int f : first) if (f < 0) return VOTE_NEW; // an entry no position uses
        for (int i = 1; i < n; i++) if (first[i] < first[i - 1]) return VOTE_NEW; // not position order
        return c.palette[0] == plainsId ? NO_VOTE : VOTE_OLD;
    }

    /**
     * Whole chunk: the lowest non-empty section is the best witness (its first position is bedrock
     * in the Overworld and Nether, so the block rule always has an answer there), and an unused
     * biome entry is proof of a fresh chunk. Otherwise the majority decides; unclear chunks stay
     * undecided and are judged again the next time they are sent.
     */
    private static Palette classify(net.minecraft.network.PacketByteBuf buf, int sections, int plainsId, StringBuilder why) {
        int newVotes = 0, oldVotes = 0, bottomVote = NO_VOTE;
        boolean staleBiome = false, seenBlocks = false;
        for (int s = 0; s < sections; s++) {
            int nonEmpty = buf.readShort();
            Container blocks = read(buf, BLOCKS, BLOCK_INDIRECT_MAX);
            Container biomes = read(buf, BIOMES, BIOME_INDIRECT_MAX);
            int bv = nonEmpty > 0 ? blockVote(blocks) : NO_VOTE;
            if (nonEmpty > 0 && !seenBlocks) {
                seenBlocks = true;
                bottomVote = bv;
            }
            int biv = biomeVote(biomes, plainsId);
            if (why != null && (bv != NO_VOTE || biv != NO_VOTE || s < 2)) {
                why.append(" s").append(s).append(":b").append("-NO".charAt(bv)).append(blocks.palette == null ? "" : "[" + blocks.palette.length + " first=" + Block.STATE_IDS.get(blocks.palette[0]).getBlock().getTranslationKey().replace("block.minecraft.", "") + "]")
                    .append("/bi").append("-NO".charAt(biv)).append(biomes.palette == null ? "(" + biomes.bits + ")" : java.util.Arrays.toString(biomes.palette));
            }
            if (biv == VOTE_NEW && hasUnused(biomes)) staleBiome = true;
            if (bv == VOTE_NEW) newVotes++;
            if (bv == VOTE_OLD) oldVotes++;
            if (biv == VOTE_NEW) newVotes++;
            if (biv == VOTE_OLD) oldVotes++;
        }
        boolean strongNew = bottomVote == VOTE_NEW || staleBiome;
        if (newVotes > oldVotes && (newVotes >= 2 || strongNew)) return Palette.NEW;
        if (oldVotes > 0 && oldVotes > newVotes * 3 && bottomVote != VOTE_NEW && !staleBiome) return Palette.OLD;
        return null;
    }

    private static boolean hasUnused(Container c) {
        boolean[] used = new boolean[c.palette.length];
        for (int i = 0; i < BIOMES; i++) {
            int id = c.at(i);
            if (id < used.length) used[id] = true;
        }
        for (boolean u : used) if (!u) return true;
        return false;
    }

    // ------------------------------------------------------------------ chunk scan (liquid, version)

    private void queueScan(WorldChunk chunk) {
        boolean nether = mc.world != null && mc.world.getRegistryKey() == World.NETHER;
        boolean overworld = mc.world != null && mc.world.getRegistryKey() == World.OVERWORLD;
        scanner.execute(() -> {
            try {
                scan(chunk, nether, overworld);
            } catch (Throwable t) {
                Bali.LOG.debug("NewChunks scan failed", t);
            }
        });
    }

    private void scan(WorldChunk chunk, boolean nether, boolean overworld) {
        long key = chunk.getPos().toLong();
        ChunkSection[] sections = chunk.getSectionArray();

        if (liquid.on() && !liquidState.containsKey(key)) {
            for (ChunkSection s : sections) {
                if (s == null || s.isEmpty()) continue;
                if (s.hasAny(st -> { FluidState f = st.getFluidState(); return !f.isEmpty() && !f.isStill(); })) {
                    liquidState.put(key, Liquid.OLD); // already-settled flowing fluid
                    break;
                }
            }
        }

        if (version.on() && (nether || overworld) && !versionState.containsKey(key)) {
            Set<Block> markers = nether ? NETHER_NEW : OVERWORLD_NEW;
            boolean sawTerrain = false, isNew = false;
            for (int i = 0; i < sections.length; i++) {
                ChunkSection s = sections[i];
                if (s == null || s.isEmpty()) continue;
                // overworld: below y=0 is 1.18+ terrain even in upgraded old chunks, so skip it
                if (overworld && chunk.sectionIndexToCoord(i) < 0) continue;
                sawTerrain = true;
                if (s.hasAny(st -> markers.contains(st.getBlock()))) {
                    isNew = true;
                    break;
                }
            }
            if (isNew) versionState.put(key, Version.NEW);
            else if (sawTerrain) versionState.put(key, Version.OLD);
        }
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Events.Render3D e) {
        double y = switch (renderAt.get()) {
            case Bedrock -> mc.world.getBottomY();
            case Feet -> Math.floor(mc.player.getY()) + yOffset.getD();
            case Custom -> yOffset.getD();
        };
        ChunkPos me = mc.player.getChunkPos();
        int max = renderDistance.getI();
        java.util.Set<Long> keys = new java.util.HashSet<>(paletteState.keySet());
        keys.addAll(liquidState.keySet());
        keys.addAll(versionState.keySet());
        for (long k : keys) {
            int cx = ChunkPos.getPackedX(k), cz = ChunkPos.getPackedZ(k);
            if (Math.abs(cx - me.x) > max || Math.abs(cz - me.z) > max) continue;
            Palette p = palette.on() ? paletteState.get(k) : null;
            Liquid l = liquidState.get(k);
            Version v = versionState.get(k);
            // the palette verdict is the reliable one; liquid only decides chunks it has no verdict for
            boolean isNew = p == Palette.NEW || p == null && l == Liquid.NEW;
            boolean isOld = p == Palette.OLD || p == null && l == Liquid.OLD || v == Version.OLD;
            ColorSetting col;
            if (isNew) col = newColor;
            else if (v == Version.NEW) col = versionColor;
            else if (showOld.on() && isOld) col = oldColor;
            else continue;
            Box box = new Box(cx * 16, y, cz * 16, cx * 16 + 16, y + 0.02, cz * 16 + 16);
            Render3D.boxFill(box, col.argb());
            if (outline.on()) Render3D.boxOutline(box, col.withAlpha(Math.min(255, col.a() * 3)), 1f);
        }
    }

    // ------------------------------------------------------------------ persistence

    private String key() {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        return server.replaceAll("[^A-Za-z0-9._-]", "_") + "/" + mc.world.getRegistryKey().getValue().getPath();
    }

    private Path file(String key) {
        return mc.runDirectory.toPath().resolve("bali").resolve("chunks").resolve(key + ".txt");
    }

    private void switchWorld() {
        persist();
        paletteState.clear();
        liquidState.clear();
        versionState.clear();
        loadedKey = key();
        if (!save.on()) return;
        Path f = file(loadedKey);
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.split(" ");
                if (p.length != 3) continue;
                long k = ChunkPos.toLong(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                switch (p[0]) {
                    case "P" -> paletteState.put(k, Palette.NEW);
                    case "p" -> paletteState.put(k, Palette.OLD);
                    case "N" -> liquidState.put(k, Liquid.NEW);
                    case "O" -> liquidState.put(k, Liquid.OLD);
                    case "V" -> versionState.put(k, Version.NEW);
                    case "v" -> versionState.put(k, Version.OLD);
                    default -> {}
                }
            }
        } catch (IOException | NumberFormatException ex) {
            ChatUtil.warn("NewChunks: couldn't read saved chunks (" + ex.getMessage() + ")");
        }
    }

    private void persist() {
        if (!save.on() || loadedKey == null || (paletteState.isEmpty() && liquidState.isEmpty() && versionState.isEmpty())) return;
        StringBuilder sb = new StringBuilder();
        paletteState.forEach((k, v) -> sb.append(v == Palette.NEW ? "P " : "p ").append(ChunkPos.getPackedX(k)).append(' ').append(ChunkPos.getPackedZ(k)).append('\n'));
        liquidState.forEach((k, v) -> sb.append(v == Liquid.NEW ? "N " : "O ").append(ChunkPos.getPackedX(k)).append(' ').append(ChunkPos.getPackedZ(k)).append('\n'));
        versionState.forEach((k, v) -> sb.append(v == Version.NEW ? "V " : "v ").append(ChunkPos.getPackedX(k)).append(' ').append(ChunkPos.getPackedZ(k)).append('\n'));
        try {
            Path f = file(loadedKey);
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Bali.LOG.warn("NewChunks save failed", ex);
        }
    }
}
