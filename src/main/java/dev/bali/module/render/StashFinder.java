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
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags chunks that contain player-made builds while you travel: every loaded chunk is scanned on a
 * background thread over its full height (bedrock to build limit), each kind of player block adds to
 * a score, and chunks over the threshold raise an on-screen alert, a chat message with coordinates,
 * a waypoint, and a line in .minecraft/bali/stashes/<server>/<dimension>.txt.
 *
 * Natural structures (villages, dungeons, strongholds, bastions, end cities, trial chambers...) also
 * contain chests, beds and furnaces, so in a chunk with a structure marker block those only count
 * when they can't be natural (shulkers, beacons, concrete, sky builds...).
 *
 * "Sky builds": nothing generates near the build limit (Overworld y >= Sky Y, the Nether above the
 * bedrock roof, the End above y 220), so any block up there was placed by a player.
 *
 * Only reads chunk data the server already sends; nothing is sent, so no anticheat exposure.
 */
public final class StashFinder extends Module {
    private final NumberSetting threshold = number("Threshold", 5, 1, 40, 0.5, "Score a chunk needs to be flagged (1 shulker = 5)");
    private final BoolSetting skyBuilds = bool("Sky Builds", true, "Count any block near the build limit / on the Nether roof");
    private final NumberSetting skyY = number("Sky Y", 300, 200, 319, 1, "Overworld: blocks at or above this Y are player-made");
    private final NumberSetting ignoreRadius = number("Ignore Radius", 1000, 0, 50000, 100, "Ignore chunks this close to 0,0 (this dimension's coords)");
    private final BoolSetting banner = bool("Banner", true, "Big on-screen notification");
    private final BoolSetting chat = bool("Chat", true, "Chat message with coordinates");
    private final BoolSetting sound = bool("Sound", true, "Ping when something is found (Minecraft sound, follows its volume)");
    private final BoolSetting loudAlert = bool("Loud Alert", true, "Alarm through the system audio: plays even with Minecraft's master volume muted");
    private final NumberSetting alertVolume = number("Alert Volume", 80, 5, 100, 5, "Loudness of the alarm, % of full scale (your system volume still applies)").visibleWhen(() -> loudAlert != null && loudAlert.on());
    private final NumberSetting alertRepeats = number("Alert Repeats", 2, 1, 5, 1, "How many times the three beeps play").visibleWhen(() -> loudAlert != null && loudAlert.on());
    private final BoolSetting waypoints = bool("Waypoints", true, "Beam at every found stash");
    private final BoolSetting tracers = bool("Tracers", false, "Lines to found stashes");
    private final NumberSetting waypointRange = number("Waypoint Range", 2000, 100, 30000, 100, "Only draw waypoints this close");
    private final ColorSetting color = color("Color", 0xFFFF40C0, "Waypoint color");
    private final BoolSetting save = bool("Save", true, "Log finds per server/dimension (never re-alert a saved stash)");

    // ------------------------------------------------------------------ signals

    /** natural = can generate in structures, so it is ignored in chunks with a structure marker. */
    enum Signal {
        SHULKER("shulker", 5, 20, false),
        BEACON("beacon", 5, 3, false),
        VALUABLE("valuable block", 2, 6, false),
        ANCHOR("respawn anchor", 2, 3, false),
        ENCHANT("enchant table", 2, 2, false),
        ANVIL("anvil", 1.5, 3, false),
        SIGN("sign", 1, 5, false),
        CONCRETE("concrete", 0.15, 30, false),
        PORTAL("portal", 0.2, 21, false),
        SKY("sky block", 0.4, 25, false),
        CHEST("chest", 1.5, 30, true),
        BARREL("barrel", 1.5, 20, true),
        ECHEST("ender chest", 2, 6, true),
        HOPPER("hopper", 1, 8, true),
        BED("bed", 0.5, 4, true),
        WORKSTATION("workstation", 0.5, 4, true),
        OBSIDIAN("obsidian", 0.08, 50, true);

        final String label;
        final double weight;
        final int cap;
        final boolean natural;

        Signal(String label, double weight, int cap, boolean natural) {
            this.label = label;
            this.weight = weight;
            this.cap = cap;
            this.natural = natural;
        }
    }

    private static volatile Map<Block, Signal> SIGNALS;
    private static volatile Set<Block> STRUCTURE;

    private static void initTables() {
        if (SIGNALS != null) return;
        Map<Block, Signal> m = new HashMap<>();
        for (Block b : Registries.BLOCK) {
            String id = Registries.BLOCK.getId(b).getPath();
            if (b instanceof ShulkerBoxBlock) m.put(b, Signal.SHULKER);
            else if (b instanceof BedBlock) m.put(b, Signal.BED);
            else if (b instanceof AbstractSignBlock) m.put(b, Signal.SIGN);
            else if (id.endsWith("_concrete") || id.endsWith("_concrete_powder")) m.put(b, Signal.CONCRETE);
        }
        m.put(Blocks.BEACON, Signal.BEACON);
        for (Block b : List.of(Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.EMERALD_BLOCK)) m.put(b, Signal.VALUABLE);
        m.put(Blocks.RESPAWN_ANCHOR, Signal.ANCHOR);
        m.put(Blocks.ENCHANTING_TABLE, Signal.ENCHANT);
        for (Block b : List.of(Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL)) m.put(b, Signal.ANVIL);
        m.put(Blocks.NETHER_PORTAL, Signal.PORTAL);
        m.put(Blocks.CHEST, Signal.CHEST);
        m.put(Blocks.TRAPPED_CHEST, Signal.CHEST);
        m.put(Blocks.BARREL, Signal.BARREL);
        m.put(Blocks.ENDER_CHEST, Signal.ECHEST);
        m.put(Blocks.HOPPER, Signal.HOPPER);
        m.put(Blocks.DROPPER, Signal.HOPPER);
        for (Block b : List.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND))
            m.put(b, Signal.WORKSTATION);
        m.put(Blocks.OBSIDIAN, Signal.OBSIDIAN);

        STRUCTURE = Set.of(Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.BELL, Blocks.MOSSY_COBBLESTONE,
            Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS,
            Blocks.INFESTED_CHISELED_STONE_BRICKS, Blocks.END_PORTAL_FRAME, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL,
            Blocks.CHISELED_SANDSTONE, Blocks.REINFORCED_DEEPSLATE, Blocks.GILDED_BLACKSTONE,
            Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.CRYING_OBSIDIAN,
            Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.END_ROD);
        SIGNALS = m;
    }

    // ------------------------------------------------------------------ state

    /** Result of scanning one chunk (built off-thread). */
    private record ChunkResult(long key, EnumMap<Signal, Integer> counts, double score, BlockPos best, boolean structure) {}

    /** A found stash: one or more neighbouring flagged chunks. */
    private static final class Stash {
        long id; // first chunk: stays the same while neighbouring chunks merge in
        boolean structure;
        BlockPos pos;
        double bestWeight;
        final EnumMap<Signal, Integer> counts = new EnumMap<>(Signal.class);
        final Set<Long> chunks = new HashSet<>();
        double score;
        String time;

        String summary() {
            List<Map.Entry<Signal, Integer>> e = new ArrayList<>(counts.entrySet());
            e.sort((a, b) -> Double.compare(b.getKey().weight, a.getKey().weight)); // most telling first: shulkers, beacons...
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < e.size() && i < 4; i++) {
                if (i > 0) sb.append(", ");
                sb.append(e.get(i).getValue()).append(' ').append(e.get(i).getKey().label);
            }
            return sb.toString();
        }

        /** Why this was flagged: every signal with its share of the score, most telling first. */
        List<String> details(double threshold) {
            List<String> l = new ArrayList<>();
            l.add(Formatting.YELLOW + "Score " + String.format(Locale.ROOT, "%.1f", score) + Formatting.GRAY
                + " (flags at " + String.format(Locale.ROOT, "%.1f", threshold) + ")");
            List<Map.Entry<Signal, Integer>> e = new ArrayList<>(counts.entrySet());
            e.sort((a, b) -> Double.compare(b.getKey().weight * Math.min(b.getValue(), b.getKey().cap),
                a.getKey().weight * Math.min(a.getValue(), a.getKey().cap)));
            for (var en : e) {
                Signal sig = en.getKey();
                int n = en.getValue();
                String part = Formatting.WHITE + "" + n + "× " + sig.label
                    + Formatting.GRAY + String.format(Locale.ROOT, "  +%.1f", sig.weight * Math.min(n, sig.cap));
                if (n > sig.cap) part += " (counts up to " + sig.cap + ")";
                l.add(part);
            }
            if (structure) l.add(Formatting.GRAY + "Structure nearby: natural chests/beds/etc. not counted");
            l.add(Formatting.GRAY + (chunks.size() == 1 ? "1 chunk" : chunks.size() + " chunks") + (time != null ? ", found " + time : ""));
            return l;
        }
    }

    /** A found stash as other modules see it (StashFinderBot's map). */
    public record StashInfo(long id, BlockPos pos, double score, List<String> details) {}

    /** All stashes known in this dimension, with the reasons they were flagged. */
    public List<StashInfo> snapshot() {
        List<StashInfo> out = new ArrayList<>();
        double th = threshold.getD();
        synchronized (stashes) {
            for (Stash s : stashes) out.add(new StashInfo(s.id, s.pos, s.score, List.copyOf(s.details(th))));
        }
        return out;
    }

    private final List<Stash> stashes = Collections.synchronizedList(new ArrayList<>());
    private final Set<Long> flaggedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ChunkResult> results = new ConcurrentLinkedQueue<>();
    private final Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bali-StashFinder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private String loadedKey;
    private int foundThisSession;

    // banner
    private Stash bannerStash;
    private long bannerStart;
    private static final long BANNER_MS = 8000;

    public StashFinder() {
        super("StashFinder", "Alerts when a chunk has a possible stash/base", Category.RENDER);
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (isEnabled()) queueScan(chunk);
        });
    }

    @Override
    public String getInfo() { return String.valueOf(foundThisSession); }

    @Override
    protected void onEnable() {
        if (nullCheck()) return;
        switchWorld();
        rescanLoaded();
    }

    @Override
    protected void onDisable() {
        persist();
        clearState();
    }

    @EventHandler
    private void onLeave(Events.Leave e) {
        persist();
        clearState();
    }

    private void clearState() {
        stashes.clear();
        flaggedChunks.clear();
        results.clear();
        dirty.clear();
        loadedKey = null;
        bannerStash = null;
    }

    private void rescanLoaded() {
        int view = mc.options.getClampedViewDistance() + 1;
        ChunkPos c = mc.player.getChunkPos();
        for (int x = c.x - view; x <= c.x + view; x++)
            for (int z = c.z - view; z <= c.z + view; z++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z);
                if (chunk != null) queueScan(chunk);
            }
    }

    // ------------------------------------------------------------------ scanning

    private void queueScan(WorldChunk chunk) {
        if (mc.world == null) return;
        ChunkPos cp = chunk.getPos();
        double r = ignoreRadius.getD();
        if (r > 0 && Math.abs(cp.getCenterX()) < r && Math.abs(cp.getCenterZ()) < r) return;
        var dim = mc.world.getRegistryKey();
        int sky = !skyBuilds.on() ? Integer.MAX_VALUE
            : dim == World.NETHER ? 128
            : dim == World.END ? 220
            : skyY.getI();
        scanner.execute(() -> {
            try {
                ChunkResult res = scan(chunk, sky);
                if (res != null) results.add(res);
            } catch (Throwable t) {
                Bali.LOG.debug("StashFinder scan failed", t); // chunk unloaded/changed mid-scan, harmless
            }
        });
    }

    private ChunkResult scan(WorldChunk chunk, int skyY) {
        initTables();
        Map<Block, Signal> signals = SIGNALS;
        Set<Block> structure = STRUCTURE;
        EnumMap<Signal, Integer> counts = new EnumMap<>(Signal.class);
        boolean isStructure = false;
        BlockPos best = null;
        double bestW = -1;
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection s = sections[i];
            if (s == null || s.isEmpty()) continue;
            int baseY = chunk.sectionIndexToCoord(i) << 4;
            boolean skySection = baseY + 15 >= skyY;
            if (!skySection && !s.hasAny(st -> signals.containsKey(st.getBlock()) || structure.contains(st.getBlock()))) continue;
            for (int y = 0; y < 16; y++) {
                boolean sky = baseY + y >= skyY;
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState st = s.getBlockState(x, y, z);
                        if (st.isAir()) continue;
                        Block b = st.getBlock();
                        if (structure.contains(b)) isStructure = true;
                        Signal sig = signals.get(b);
                        if (sig == null && sky) sig = Signal.SKY;
                        else if (sky) counts.merge(Signal.SKY, 1, Integer::sum); // e.g. a shulker at the limit counts as both
                        if (sig == null) continue;
                        counts.merge(sig, 1, Integer::sum);
                        if (sig.weight > bestW) {
                            bestW = sig.weight;
                            best = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        }
                    }
            }
        }
        if (counts.isEmpty()) return null;
        double score = 0;
        for (var e : counts.entrySet()) {
            Signal sig = e.getKey();
            if (isStructure && sig.natural) continue;
            score += sig.weight * Math.min(e.getValue(), sig.cap);
        }
        if (isStructure) counts.keySet().removeIf(sig -> sig.natural);
        return new ChunkResult(chunk.getPos().toLong(), counts, score, best, isStructure);
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        // someone placing storage while we watch, or anti-xray revealing hidden chests: rescan that chunk
        if (e.packet instanceof BlockUpdateS2CPacket p) blockChanged(p.getPos(), p.getState());
        else if (e.packet instanceof ChunkDeltaUpdateS2CPacket p) p.visitUpdates(this::blockChanged);
    }

    private void blockChanged(BlockPos pos, BlockState state) {
        Map<Block, Signal> signals = SIGNALS;
        if (signals == null || !signals.containsKey(state.getBlock())) return;
        dirty.add(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    // ------------------------------------------------------------------ results (main thread)

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (!key().equals(loadedKey)) {
            switchWorld();
            rescanLoaded();
        }
        if (mc.player.age % 20 == 0 && !dirty.isEmpty()) {
            for (Long k : List.copyOf(dirty)) {
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k));
                if (c != null) queueScan(c);
            }
            dirty.clear();
        }
        ChunkResult r;
        while ((r = results.poll()) != null) handle(r);
    }

    private void handle(ChunkResult r) {
        if (r.score() < threshold.getD()) return;
        int cx = ChunkPos.getPackedX(r.key()), cz = ChunkPos.getPackedZ(r.key());
        Stash near = null;
        synchronized (stashes) {
            for (Stash s : stashes) {
                for (long k : s.chunks)
                    if (Math.abs(ChunkPos.getPackedX(k) - cx) <= 2 && Math.abs(ChunkPos.getPackedZ(k) - cz) <= 2) {
                        near = s;
                        break;
                    }
                if (near != null) break;
            }
        }
        if (near != null) {
            // part of a stash we already know about: merge silently (rescans replace the chunk's numbers)
            near.structure |= r.structure();
            if (near.chunks.add(r.key()) || r.score() > near.score) {
                for (var e : r.counts().entrySet()) near.counts.merge(e.getKey(), e.getValue(), Math::max);
                near.score = Math.max(near.score, r.score());
                if (r.best() != null) {
                    Signal sig = SIGNALS.getOrDefault(mc.world.getBlockState(r.best()).getBlock(), Signal.SKY);
                    if (sig.weight > near.bestWeight) {
                        near.bestWeight = sig.weight;
                        near.pos = r.best();
                    }
                }
            }
            flaggedChunks.add(r.key());
            return;
        }

        Stash s = new Stash();
        s.id = r.key();
        s.structure = r.structure();
        s.chunks.add(r.key());
        s.counts.putAll(r.counts());
        s.score = r.score();
        s.pos = r.best() != null ? r.best() : new BlockPos(cx * 16 + 8, mc.player.getBlockY(), cz * 16 + 8);
        s.bestWeight = r.best() != null ? SIGNALS.getOrDefault(mc.world.getBlockState(r.best()).getBlock(), Signal.SKY).weight : 0;
        s.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        stashes.add(s);
        flaggedChunks.add(r.key());
        foundThisSession++;
        alert(s);
        persist();
    }

    private void alert(Stash s) {
        int dist = (int) Math.sqrt(mc.player.getBlockPos().getSquaredDistance(s.pos));
        if (chat.on()) {
            ChatUtil.info(Formatting.LIGHT_PURPLE + "Possible stash " + Formatting.WHITE + s.pos.getX() + " " + s.pos.getY() + " " + s.pos.getZ()
                + Formatting.GRAY + " (" + dist + "m) " + Formatting.YELLOW + s.summary());
        }
        if (banner.on()) {
            bannerStash = s;
            bannerStart = System.currentTimeMillis();
        }
        if (sound.on()) {
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.6f));
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_TOAST_IN, 1.0f, 1.0f));
        }
        if (loudAlert.on()) dev.bali.util.SystemSound.alarm(alertVolume.getD() / 100.0, alertRepeats.getI());
        if (dev.bali.module.client.Recorder.active())
            dev.bali.module.client.Recorder.log("STASH", s.pos.toShortString() + " score=" + String.format(Locale.ROOT, "%.1f", s.score) + " " + s.summary());
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender2D(Events.Render2D e) {
        Stash s = bannerStash;
        if (s == null) return;
        long age = System.currentTimeMillis() - bannerStart;
        if (age > BANNER_MS) {
            bannerStash = null;
            return;
        }
        float alpha = age < 200 ? age / 200f : age > BANNER_MS - 600 ? (BANNER_MS - age) / 600f : 1f;
        int a = Math.max(8, (int) (alpha * 255));
        DrawContext ctx = e.context;
        TextRenderer tr = mc.textRenderer;
        int dist = (int) Math.sqrt(mc.player.getBlockPos().getSquaredDistance(s.pos));
        String title = "POSSIBLE STASH FOUND";
        String line1 = s.summary();
        String line2 = s.pos.getX() + ", " + s.pos.getY() + ", " + s.pos.getZ() + "  •  " + dist + "m";
        int w = Math.max(tr.getWidth(title) * 2, Math.max(tr.getWidth(line1), tr.getWidth(line2))) + 24;
        int sw = ctx.getScaledWindowWidth();
        int x = sw / 2 - w / 2, y = 34, h = 50;
        int accent = color.argb() & 0xFFFFFF;
        ctx.fill(x, y, x + w, y + h, ((int) (a * 0.75f) << 24));
        ctx.fill(x, y, x + w, y + 2, (a << 24) | accent);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(sw / 2f - tr.getWidth(title), y + 6);
        ctx.getMatrices().scale(2f, 2f);
        ctx.drawTextWithShadow(tr, title, 0, 0, (a << 24) | accent);
        ctx.getMatrices().popMatrix();
        ctx.drawTextWithShadow(tr, line1, sw / 2 - tr.getWidth(line1) / 2, y + 26, (a << 24) | 0xFFFF55);
        ctx.drawTextWithShadow(tr, line2, sw / 2 - tr.getWidth(line2) / 2, y + 38, (a << 24) | 0xFFFFFF);
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (!waypoints.on() && !tracers.on()) return;
        Vec3d eye = e.cameraPos;
        double range = waypointRange.getD();
        int col = color.argb() | 0xFF000000;
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d tracerStart = dev.bali.util.Render3D.crosshair();
        int bottom = mc.world.getBottomY(), top = mc.world.getTopYInclusive() + 1;
        List<Stash> copy;
        synchronized (stashes) { copy = List.copyOf(stashes); }
        for (Stash s : copy) {
            Vec3d c = Vec3d.ofCenter(s.pos);
            double dx = c.x - eye.x, dz = c.z - eye.z;
            if (dx * dx + dz * dz > range * range) continue;
            if (waypoints.on()) {
                Render3D.box(new Box(s.pos), (col & 0xFFFFFF) | 0x50000000, col, 2f);
                Render3D.line(new Vec3d(c.x, bottom, c.z), new Vec3d(c.x, top, c.z), col, 2f);
            }
            if (tracers.on()) Render3D.lineFromCamera(tracerStart, c, col, 1.5f);
        }
    }

    // ------------------------------------------------------------------ commands / persistence

    /** .stashes [clear] */
    public void command(String[] a) {
        if (nullCheck()) return;
        if (a.length > 0 && a[0].equalsIgnoreCase("clear")) {
            stashes.clear();
            flaggedChunks.clear();
            if (loadedKey != null) {
                try { Files.deleteIfExists(file(loadedKey)); } catch (IOException ignored) {}
            }
            ChatUtil.info("Cleared saved stashes for this dimension");
            return;
        }
        List<Stash> copy;
        synchronized (stashes) { copy = new ArrayList<>(stashes); }
        if (copy.isEmpty()) {
            ChatUtil.info(isEnabled() ? "No stashes found in this dimension yet" : "Enable StashFinder first");
            return;
        }
        BlockPos me = mc.player.getBlockPos();
        copy.sort((x, y) -> Double.compare(x.pos.getSquaredDistance(me), y.pos.getSquaredDistance(me)));
        ChatUtil.info(copy.size() + " stashes in this dimension (closest first):");
        for (int i = 0; i < copy.size() && i < 10; i++) {
            Stash s = copy.get(i);
            ChatUtil.info(Formatting.WHITE + "" + s.pos.getX() + " " + s.pos.getY() + " " + s.pos.getZ() + Formatting.GRAY
                + " (" + (int) Math.sqrt(s.pos.getSquaredDistance(me)) + "m) " + Formatting.YELLOW + s.summary());
        }
    }

    private String key() {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        return server.replaceAll("[^A-Za-z0-9._-]", "_") + "/" + mc.world.getRegistryKey().getValue().getPath();
    }

    private Path file(String key) {
        return mc.runDirectory.toPath().resolve("bali").resolve("stashes").resolve(key + ".txt");
    }

    private static final Pattern LINE = Pattern.compile("^(\\S+ \\S+)\\s+x=(-?\\d+) y=(-?\\d+) z=(-?\\d+)\\s+score=([\\d.]+)\\s+(.*?)\\s*\\| chunks (.*)$");

    private void switchWorld() {
        persist();
        stashes.clear();
        flaggedChunks.clear();
        bannerStash = null;
        loadedKey = key();
        if (!save.on()) return;
        Path f = file(loadedKey);
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) continue;
                Stash s = new Stash();
                s.time = m.group(1);
                s.pos = new BlockPos(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
                s.score = Double.parseDouble(m.group(5));
                s.bestWeight = 99; // keep the saved position
                for (String part : m.group(6).split(", ")) {
                    String[] p = part.split(" ", 2);
                    if (p.length != 2) continue;
                    for (Signal sig : Signal.values())
                        if (sig.label.equals(p[1])) s.counts.put(sig, Integer.parseInt(p[0]));
                }
                for (String c : m.group(7).split(";")) {
                    String[] xz = c.split(",");
                    if (xz.length == 2) {
                        long k = ChunkPos.toLong(Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim()));
                        if (s.chunks.isEmpty()) s.id = k;
                        s.chunks.add(k);
                    }
                }
                if (s.chunks.isEmpty()) s.id = ChunkPos.toLong(s.pos.getX() >> 4, s.pos.getZ() >> 4);
                stashes.add(s);
                flaggedChunks.addAll(s.chunks);
            }
        } catch (IOException | RuntimeException ex) {
            ChatUtil.warn("StashFinder: couldn't read saved stashes (" + ex.getMessage() + ")");
        }
    }

    private void persist() {
        if (!save.on() || loadedKey == null) return;
        StringBuilder sb = new StringBuilder();
        synchronized (stashes) {
            if (stashes.isEmpty()) return;
            for (Stash s : stashes) {
                sb.append(s.time).append("  x=").append(s.pos.getX()).append(" y=").append(s.pos.getY()).append(" z=").append(s.pos.getZ())
                    .append("  score=").append(String.format(Locale.ROOT, "%.1f", s.score)).append("  ").append(s.summary()).append(" | chunks ");
                boolean first = true;
                for (long k : s.chunks) {
                    if (!first) sb.append(';');
                    first = false;
                    sb.append(ChunkPos.getPackedX(k)).append(',').append(ChunkPos.getPackedZ(k));
                }
                sb.append('\n');
            }
        }
        try {
            Path f = file(loadedKey);
            Files.createDirectories(f.getParent());
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Bali.LOG.warn("StashFinder save failed", ex);
        }
    }
}
