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

    public dev.bali.util.StashMap.Overlay mapOverlay() {
        int[] c = new int[4];
        c[dev.bali.util.StashMap.NC_NEW] = 0x60000000 | (newColor.argb() & 0xFFFFFF);
        c[dev.bali.util.StashMap.NC_SINCE] = 0x50000000 | (versionColor.argb() & 0xFFFFFF);
        c[dev.bali.util.StashMap.NC_OLD] = showOld.on() ? 0x48000000 | (oldColor.argb() & 0xFFFFFF) : 0;
        return new dev.bali.util.StashMap.Overlay(c);
    }

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

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (!key().equals(loadedKey)) switchWorld();
        if (mc.player.age % 1200 == 0) persist();
    }

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
        if (liquidState.get(key) == Liquid.OLD) return;
        liquidState.put(key, Liquid.NEW);
    }

    private static final int BLOCK_INDIRECT_MAX = 8, BIOME_INDIRECT_MAX = 3;
    private static final int BLOCKS = 4096, BIOMES = 64;
    private static volatile boolean parseWarned;
    public static volatile boolean debugReasons;
    private final Map<Long, String> reasons = new ConcurrentHashMap<>();

    public String reasonOf(int chunkX, int chunkZ) { return reasons.get(ChunkPos.toLong(chunkX, chunkZ)); }

    private void queuePalette(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket p) {
        if (mc.world == null) return;
        long key = ChunkPos.toLong(p.getChunkX(), p.getChunkZ());
        if (paletteState.containsKey(key)) return;
        int sections = mc.world.countVerticalSections();
        int plains;
        try {
            var biomes = mc.world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME);
            plains = biomes.getRawId(biomes.getValueOrThrow(net.minecraft.world.biome.BiomeKeys.PLAINS));
        } catch (Exception ex) {
            plains = -1;
        }
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

    private record Container(int bits, int single, int[] palette, long[] data) {
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

    private static final int NO_VOTE = 0, VOTE_NEW = 1, VOTE_OLD = 2;

    private static int blockVote(Container c) {
        if (c.palette == null || c.palette.length < 2 || c.at(0) >= c.palette.length) return NO_VOTE;
        BlockState first = Block.STATE_IDS.get(c.palette[0]);
        BlockState atZero = Block.STATE_IDS.get(c.palette[c.at(0)]);
        if (first == null || atZero == null) return NO_VOTE;
        if (first.isOf(Blocks.AIR) && !atZero.isAir()) return VOTE_NEW;
        if (!first.isAir() && c.at(0) == 0) return VOTE_OLD;
        return NO_VOTE;
    }

    private static int biomeVote(Container c, int plainsId) {
        if (c.palette == null || c.palette.length < 2) return NO_VOTE;
        int n = c.palette.length;
        int[] first = new int[n];
        java.util.Arrays.fill(first, -1);
        for (int i = 0; i < BIOMES; i++) {
            int id = c.at(i);
            if (id >= n) return NO_VOTE;
            if (first[id] < 0) first[id] = i;
        }
        for (int f : first) if (f < 0) return VOTE_NEW;
        for (int i = 1; i < n; i++) if (first[i] < first[i - 1]) return VOTE_NEW;
        return c.palette[0] == plainsId ? NO_VOTE : VOTE_OLD;
    }

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
                    liquidState.put(key, Liquid.OLD);
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
