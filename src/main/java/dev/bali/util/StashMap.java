package dev.bali.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class StashMap {
    public static final int TILE = 64;
    public static final int SUB = 2;
    private static final int TS = TILE * SUB;
    private static final int MAGIC = 0x424D4150;
    private static final int FORMAT = 1;

    public static final byte NC_NONE = 0, NC_NEW = 1, NC_SINCE = 2, NC_OLD = 3;
    private static final byte SEEN_EMPTY = 1;

    public static final int UNSEEN = 0xFF0E0E12, EMPTY = 0xFF1C1C22;
    public static final int PATH = 0xFF38D8FF, ORIGIN = 0xFFFFD040, STASH = 0xFFFF40C0, PREDICT = 0xFFFFE860;

    private static final class Tile {
        final byte[] color = new byte[TS * TS];
        final byte[] nc = new byte[TILE * TILE];
    }

    public record Mark(long id, int x, int y, int z, double score, List<String> lines) {}

    public record View(double centerX, double centerZ, double bpp) {
        public double toPxX(double x, int w) { return (x - centerX) / bpp + w / 2.0; }
        public double toPxZ(double z, int h) { return (z - centerZ) / bpp + h / 2.0; }
        public double toWorldX(double px, int w) { return centerX + (px - w / 2.0) * bpp; }
        public double toWorldZ(double py, int h) { return centerZ + (py - h / 2.0) * bpp; }
    }

    public interface Canvas {
        int width();
        int height();
        int get(int x, int y);
        void set(int x, int y, int argb);
    }

    public final String server, dimension, name, pattern;
    public final double originX, originZ;
    public final long created;

    private final Map<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final Map<Long, Mark> marks = new ConcurrentHashMap<>();
    private final List<Long> markOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile float[] path = new float[2048];
    private volatile int pathLen;
    private double lastX = Double.NaN, lastZ = Double.NaN;
    private volatile double minX, minZ, maxX, maxZ;
    private volatile boolean hasBounds;
    private volatile long version;

    public StashMap(String server, String dimension, String name, String pattern, double originX, double originZ, long created) {
        this.server = server;
        this.dimension = dimension;
        this.name = name;
        this.pattern = pattern;
        this.originX = originX;
        this.originZ = originZ;
        this.created = created;
        include(originX, originZ);
    }

    public long version() { return version; }

    public void sampleChunk(WorldChunk chunk, boolean nether) {
        ChunkPos cp = chunk.getPos();
        Tile t = tile(cp.x, cp.z, true);
        int bottom = chunk.getBottomY();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int sz = 0; sz < SUB; sz++)
            for (int sx = 0; sx < SUB; sx++) {
                int lx = sx * (16 / SUB) + 16 / SUB / 2, lz = sz * (16 / SUB) + 16 / SUB / 2;
                int bx = cp.getStartX() + lx, bz = cp.getStartZ() + lz;
                int h = surface(chunk, bx, bz, nether, bottom, m);
                byte c;
                if (h < bottom) c = SEEN_EMPTY;
                else {
                    BlockState st = chunk.getBlockState(m.set(bx, h, bz));
                    MapColor mc = st.getMapColor(chunk, m);
                    if (mc == MapColor.CLEAR) c = SEEN_EMPTY;
                    else {
                        MapColor.Brightness b;
                        FluidState f = st.getFluidState();
                        if (!f.isEmpty() && f.isIn(FluidTags.WATER)) {
                            int depth = 0;
                            while (depth < 10 && h - depth > bottom && chunk.getBlockState(m.set(bx, h - depth - 1, bz)).getFluidState().isIn(FluidTags.WATER)) depth++;
                            b = depth < 2 ? MapColor.Brightness.HIGH : depth < 5 ? MapColor.Brightness.NORMAL : MapColor.Brightness.LOW;
                        } else {
                            int north = surface(chunk, bx, bz - 1, nether, bottom, m);
                            b = h > north ? MapColor.Brightness.HIGH : h < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                        }
                        c = mc.getRenderColorByte(b);
                        if (c == 0 || c == SEEN_EMPTY) c = SEEN_EMPTY;
                    }
                }
                t.color[sampleIndex(cp.x, cp.z, sx, sz)] = c;
            }
        include(cp.getStartX(), cp.getStartZ());
        include(cp.getEndX() + 1, cp.getEndZ() + 1);
        version++;
    }

    private static int surface(WorldChunk chunk, int bx, int bz, boolean nether, int bottom, BlockPos.Mutable m) {
        if (nether) {
            int y = Math.min(chunk.getTopYInclusive(), 122);
            while (y > bottom && !chunk.getBlockState(m.set(bx, y, bz)).isAir()) y--;
            while (y > bottom && chunk.getBlockState(m.set(bx, y, bz)).isAir()) y--;
            return y;
        }
        int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, bx & 15, bz & 15);
        for (int i = 0; i < 16 && y >= bottom && chunk.getBlockState(m.set(bx, y, bz)).isAir(); i++) y--;
        return y;
    }

    public void setOverlay(int cx, int cz, byte state) {
        Tile t = tile(cx, cz, true);
        int i = (cz & (TILE - 1)) * TILE + (cx & (TILE - 1));
        if (t.nc[i] != state) {
            t.nc[i] = state;
            version++;
        }
    }

    public void addPathPoint(double x, double z) {
        if (!Double.isNaN(lastX)) {
            double d = Math.hypot(x - lastX, z - lastZ);
            if (d < 6) return;
            if (d > 300) breakPath();
        }
        append((float) x, (float) z);
        lastX = x;
        lastZ = z;
        include(x, z);
        version++;
    }

    public void breakPath() {
        if (pathLen >= 2 && !Float.isNaN(path[pathLen - 2])) append(Float.NaN, Float.NaN);
        lastX = lastZ = Double.NaN;
    }

    private void append(float x, float z) {
        float[] p = path;
        if (pathLen + 2 > p.length) {
            p = java.util.Arrays.copyOf(p, p.length * 2);
            path = p;
        }
        p[pathLen] = x;
        p[pathLen + 1] = z;
        pathLen += 2;
    }

    public void putMark(Mark m) {
        Mark old = marks.put(m.id(), m);
        if (old == null) markOrder.add(m.id());
        if (!m.equals(old)) {
            include(m.x(), m.z());
            version++;
        }
    }

    public List<Mark> marks() {
        List<Long> ids;
        synchronized (markOrder) { ids = new ArrayList<>(markOrder); }
        List<Mark> l = new ArrayList<>(ids.size());
        for (long id : ids) {
            Mark m = marks.get(id);
            if (m != null) l.add(m);
        }
        return l;
    }

    public boolean seen(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        if (t == null) return false;
        return t.color[sampleIndex(cx, cz, 0, 0)] != 0;
    }

    public byte overlayAt(int cx, int cz) {
        Tile t = tile(cx, cz, false);
        return t == null ? NC_NONE : t.nc[(cz & (TILE - 1)) * TILE + (cx & (TILE - 1))];
    }

    private void include(double x, double z) {
        if (!hasBounds) {
            minX = maxX = x;
            minZ = maxZ = z;
            hasBounds = true;
            return;
        }
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (z < minZ) minZ = z;
        if (z > maxZ) maxZ = z;
    }

    public double[] bounds() { return new double[]{minX, minZ, maxX, maxZ}; }

    public double reach() {
        return Math.max(Math.max(originX - minX, maxX - originX), Math.max(originZ - minZ, maxZ - originZ));
    }

    private Tile tile(int cx, int cz, boolean create) {
        long k = ChunkPos.toLong(cx >> 6, cz >> 6);
        Tile t = tiles.get(k);
        if (t == null && create) t = tiles.computeIfAbsent(k, kk -> new Tile());
        return t;
    }

    private static int sampleIndex(int cx, int cz, int sx, int sz) {
        return ((cz & (TILE - 1)) * SUB + sz) * TS + (cx & (TILE - 1)) * SUB + sx;
    }

    public record Overlay(int[] colors) {
        public static final Overlay NONE = new Overlay(new int[4]);
    }

    public void render(Canvas c, View v, Overlay overlay, boolean grid) {
        int w = c.width(), h = c.height();
        long lastTileKey = Long.MIN_VALUE;
        Tile tile = null;
        for (int py = 0; py < h; py++) {
            int bz = (int) Math.floor(v.toWorldZ(py + 0.5, h));
            int cz = bz >> 4, sz = (bz & 15) * SUB >> 4;
            for (int px = 0; px < w; px++) {
                int bx = (int) Math.floor(v.toWorldX(px + 0.5, w));
                int cx = bx >> 4, sx = (bx & 15) * SUB >> 4;
                long k = ChunkPos.toLong(cx >> 6, cz >> 6);
                if (k != lastTileKey) {
                    tile = tiles.get(k);
                    lastTileKey = k;
                }
                int argb;
                if (tile == null) argb = UNSEEN;
                else {
                    byte b = tile.color[sampleIndex(cx, cz, sx, sz)];
                    argb = b == 0 ? UNSEEN : b == SEEN_EMPTY ? EMPTY : MapColor.getRenderColor(b) | 0xFF000000;
                    int nc = tile.nc[(cz & (TILE - 1)) * TILE + (cx & (TILE - 1))];
                    if (nc != 0 && overlay.colors[nc] != 0) argb = blend(argb, overlay.colors[nc]);
                }
                c.set(px, py, argb);
            }
        }
        if (grid) drawGrid(c, v);
        drawPath(c, v);
    }

    private static final double[] GRID_STEPS = {16, 64, 128, 256, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000, 250000, 500000, 1000000};

    public static double gridStep(double bpp) {
        for (double s : GRID_STEPS) if (s / bpp >= 50) return s;
        return GRID_STEPS[GRID_STEPS.length - 1];
    }

    private void drawGrid(Canvas c, View v) {
        int w = c.width(), h = c.height();
        double step = gridStep(v.bpp());
        double x0 = Math.floor(v.toWorldX(0, w) / step) * step;
        for (double x = x0; v.toPxX(x, w) < w; x += step) {
            int px = (int) Math.floor(v.toPxX(x, w));
            if (px < 0) continue;
            int a = x == 0 ? 0x70 : 0x26;
            for (int py = 0; py < h; py++) c.set(px, py, blend(c.get(px, py), (a << 24) | 0xFFFFFF));
        }
        double z0 = Math.floor(v.toWorldZ(0, h) / step) * step;
        for (double z = z0; v.toPxZ(z, h) < h; z += step) {
            int py = (int) Math.floor(v.toPxZ(z, h));
            if (py < 0) continue;
            int a = z == 0 ? 0x70 : 0x26;
            for (int px = 0; px < w; px++) c.set(px, py, blend(c.get(px, py), (a << 24) | 0xFFFFFF));
        }
    }

    private void drawPath(Canvas c, View v) {
        int len = pathLen;
        float[] p = path;
        int w = c.width(), h = c.height();
        double px0 = Double.NaN, py0 = Double.NaN;
        for (int i = 0; i + 1 < len; i += 2) {
            if (Float.isNaN(p[i])) {
                px0 = Double.NaN;
                continue;
            }
            double px = v.toPxX(p[i], w), py = v.toPxZ(p[i + 1], h);
            if (!Double.isNaN(px0)) line(c, px0, py0, px, py, PATH, 2);
            else plot(c, (int) Math.floor(px), (int) Math.floor(py), PATH);
            px0 = px;
            py0 = py;
        }
    }

    public static void line(Canvas c, double x0, double y0, double x1, double y1, int argb) {
        line(c, x0, y0, x1, y1, argb, 1);
    }

    public static void line(Canvas c, double x0, double y0, double x1, double y1, int argb, int width) {
        int w = c.width(), h = c.height();
        if (Math.max(x0, x1) < 0 || Math.min(x0, x1) >= w || Math.max(y0, y1) < 0 || Math.min(y0, y1) >= h) return;
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps > 20000) steps = 20000;
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.floor(x0 + dx * t), y = (int) Math.floor(y0 + dy * t);
            for (int oy = 0; oy < width; oy++)
                for (int ox = 0; ox < width; ox++) plot(c, x + ox, y + oy, argb);
        }
    }

    private static void plot(Canvas c, int x, int y, int argb) {
        if (x >= 0 && y >= 0 && x < c.width() && y < c.height()) c.set(x, y, argb);
    }

    public static int blend(int dst, int src) {
        int a = src >>> 24;
        if (a == 0) return dst;
        int r = ((dst >> 16) & 0xFF) + ((((src >> 16) & 0xFF) - ((dst >> 16) & 0xFF)) * a) / 255;
        int g = ((dst >> 8) & 0xFF) + ((((src >> 8) & 0xFF) - ((dst >> 8) & 0xFF)) * a) / 255;
        int b = (dst & 0xFF) + (((src & 0xFF) - (dst & 0xFF)) * a) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public static Canvas arrayCanvas(int[] px, int w, int h) {
        return new Canvas() {
            public int width() { return w; }
            public int height() { return h; }
            public int get(int x, int y) { return px[y * w + x]; }
            public void set(int x, int y, int argb) { px[y * w + x] = argb; }
        };
    }

    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeUTF(server);
            out.writeUTF(dimension);
            out.writeUTF(name);
            out.writeUTF(pattern);
            out.writeDouble(originX);
            out.writeDouble(originZ);
            out.writeLong(created);
            List<Map.Entry<Long, Tile>> ts = new ArrayList<>(tiles.entrySet());
            out.writeInt(ts.size());
            for (var e : ts) {
                out.writeLong(e.getKey());
                out.write(e.getValue().color);
                out.write(e.getValue().nc);
            }
            int len = pathLen;
            float[] p = path;
            out.writeInt(len);
            for (int i = 0; i < len; i++) out.writeFloat(p[i]);
            List<Mark> ms = marks();
            out.writeInt(ms.size());
            for (Mark m : ms) {
                out.writeLong(m.id());
                out.writeInt(m.x());
                out.writeInt(m.y());
                out.writeInt(m.z());
                out.writeDouble(m.score());
                out.writeInt(m.lines().size());
                for (String l : m.lines()) out.writeUTF(l);
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static StashMap load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            if (in.readInt() != MAGIC) throw new IOException("not a Bali stash map");
            int format = in.readInt();
            if (format != FORMAT) throw new IOException("unknown map format " + format);
            StashMap m = new StashMap(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readDouble(), in.readDouble(), in.readLong());
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                Tile t = new Tile();
                in.readFully(t.color);
                in.readFully(t.nc);
                m.tiles.put(k, t);
                int tx = ChunkPos.getPackedX(k), tz = ChunkPos.getPackedZ(k);
                for (int cz = 0; cz < TILE; cz++)
                    for (int cx = 0; cx < TILE; cx++)
                        if (t.color[(cz * SUB) * TS + cx * SUB] != 0) {
                            int wx = ((tx << 6) + cx) << 4, wz = ((tz << 6) + cz) << 4;
                            m.include(wx, wz);
                            m.include(wx + 16, wz + 16);
                        }
            }
            int len = in.readInt();
            float[] p = new float[Math.max(2048, len + 2048)];
            for (int i = 0; i < len; i++) p[i] = in.readFloat();
            m.path = p;
            m.pathLen = len;
            for (int i = 0; i + 1 < len; i += 2) if (!Float.isNaN(p[i])) m.include(p[i], p[i + 1]);
            m.breakPath();
            int marks = in.readInt();
            for (int i = 0; i < marks; i++) {
                long id = in.readLong();
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                double score = in.readDouble();
                int lines = in.readInt();
                List<String> ls = new ArrayList<>(lines);
                for (int j = 0; j < lines; j++) ls.add(in.readUTF());
                m.putMark(new Mark(id, x, y, z, score, List.copyOf(ls)));
            }
            return m;
        }
    }

    public void exportPng(Path png, Path txt, Overlay overlay, double playerX, double playerZ) throws IOException {
        double spanX = maxX - minX, spanZ = maxZ - minZ;
        double bpp = 16.0 / SUB;
        while (Math.max(spanX, spanZ) / bpp > 3072) bpp *= 2;
        while (Math.max(spanX, spanZ) / bpp < 600 && bpp > 1) bpp /= 2;
        int w = (int) Math.ceil(spanX / bpp) + 40, h = (int) Math.ceil(spanZ / bpp) + 40;
        View v = new View((minX + maxX) / 2, (minZ + maxZ) / 2, bpp);
        List<Mark> ms = marks();
        Files.createDirectories(png.getParent());
        try (NativeImage img = new NativeImage(w, h, false)) {
            Canvas c = new Canvas() {
                public int width() { return w; }
                public int height() { return h; }
                public int get(int x, int y) { return img.getColorArgb(x, y); }
                public void set(int x, int y, int argb) { img.setColorArgb(x, y, argb); }
            };
            render(c, v, overlay, true);
            int ox = (int) v.toPxX(originX, w), oz = (int) v.toPxZ(originZ, h);
            for (int i = -4; i <= 4; i++) {
                plot(c, ox + i, oz, ORIGIN);
                plot(c, ox, oz + i, ORIGIN);
            }
            if (!Double.isNaN(playerX)) {
                int px = (int) v.toPxX(playerX, w), pz = (int) v.toPxZ(playerZ, h);
                for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) plot(c, px + dx, pz + dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? 0xFF000000 : 0xFFFFFFFF);
            }
            for (int i = 0; i < ms.size(); i++) {
                Mark m = ms.get(i);
                int mx = (int) v.toPxX(m.x() + 0.5, w), mz = (int) v.toPxZ(m.z() + 0.5, h);
                for (int dz = -3; dz <= 3; dz++)
                    for (int dx = -3; dx <= 3; dx++)
                        plot(c, mx + dx, mz + dz, Math.abs(dx) == 3 || Math.abs(dz) == 3 ? 0xFFFFFFFF : STASH);
                PixelFont.draw(c, String.valueOf(i + 1), mx + 5, mz - 2, 0xFFFFFFFF, 0xFF000000);
            }
            String scale = "1 px = " + fmt(bpp) + (bpp == 1 ? " block" : " blocks") + "   grid " + fmt(gridStep(bpp)) + "   " + dimension + "   top = north";
            PixelFont.draw(c, scale, 4, h - 9, 0xFFFFFFFF, 0xFF000000);
            img.writeTo(png);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bali stash map  ").append(server).append(" / ").append(dimension).append('\n');
        sb.append("Search: ").append(pattern).append(", started at ").append((long) originX).append(", ").append((long) originZ)
            .append(" on ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new java.util.Date(created))).append('\n');
        sb.append(String.format(Locale.ROOT, "Area: x %d..%d, z %d..%d   picture: 1 px = %s block(s), top = north%n",
            (long) minX, (long) maxX, (long) minZ, (long) maxZ, fmt(bpp)));
        sb.append("Path flown: ").append(String.format(Locale.ROOT, "%.1f", pathLength() / 1000)).append(" km\n");
        sb.append('\n').append(ms.isEmpty() ? "No possible stashes found.\n" : ms.size() + " possible stashes (numbers match the picture):\n");
        for (int i = 0; i < ms.size(); i++) {
            Mark m = ms.get(i);
            sb.append('\n').append('#').append(i + 1).append("  ").append(m.x()).append(' ').append(m.y()).append(' ').append(m.z()).append('\n');
            for (String l : m.lines()) sb.append("    ").append(net.minecraft.util.Formatting.strip(l)).append('\n');
        }
        Files.writeString(txt, sb.toString(), StandardCharsets.UTF_8);
    }

    public double pathLength() {
        int len = pathLen;
        float[] p = path;
        double total = 0;
        for (int i = 2; i + 1 < len; i += 2) {
            if (Float.isNaN(p[i]) || Float.isNaN(p[i - 2])) continue;
            total += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
        }
        return total;
    }

    public static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    static final class PixelFont {
        private static final Map<Character, int[]> GLYPHS = new java.util.HashMap<>();

        static {
            String[] rows = {
                "0:111101101101111", "1:010110010010111", "2:111001111100111", "3:111001111001111", "4:101101111001001",
                "5:111100111001111", "6:111100111101111", "7:111001001010010", "8:111101111101111", "9:111101111001111",
                "A:010101111101101", "B:110101110101110", "C:011100100100011", "D:110101101101110", "E:111100110100111",
                "F:111100110100100", "G:011100101101011", "H:101101111101101", "I:111010010010111", "J:001001001101010",
                "K:101101110101101", "L:100100100100111", "M:101111111101101", "N:110101101101101", "O:010101101101010",
                "P:110101110100100", "Q:010101101110011", "R:110101110101101", "S:011100010001110", "T:111010010010010",
                "U:101101101101111", "V:101101101101010", "W:101101111111101", "X:101101010101101", "Y:101101010010010",
                "Z:111001010100111", "=:000111000111000", ".:000000000000010", "-:000000111000000", "_:000000000000111",
                "/:001001010100100", ",:000000000010100", "::000010000010000","(:010100100100010", "):010001001001010"
            };
            for (String r : rows) {
                char ch = r.charAt(0);
                String bits = r.substring(2);
                int[] g = new int[15];
                for (int i = 0; i < 15; i++) g[i] = bits.charAt(i) == '1' ? 1 : 0;
                GLYPHS.put(ch, g);
            }
        }

        static void draw(Canvas c, String s, int x, int y, int color, int shadow) {
            s = s.toUpperCase(Locale.ROOT);
            for (int pass = 0; pass < 2; pass++) {
                int cx = x + (pass == 0 ? 1 : 0), cy = y + (pass == 0 ? 1 : 0);
                int col = pass == 0 ? shadow : color;
                for (int i = 0; i < s.length(); i++) {
                    int[] g = GLYPHS.get(s.charAt(i));
                    if (g != null)
                        for (int gy = 0; gy < 5; gy++)
                            for (int gx = 0; gx < 3; gx++)
                                if (g[gy * 3 + gx] == 1) plot(c, cx + gx, cy + gy, col);
                    cx += 4;
                }
            }
        }
    }
}
