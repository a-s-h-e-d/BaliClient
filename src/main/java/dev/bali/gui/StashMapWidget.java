package dev.bali.gui;

import dev.bali.Bali;
import dev.bali.module.misc.StashFinderBot;
import dev.bali.util.StashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class StashMapWidget {
    public static final StashMapWidget INSTANCE = new StashMapWidget();
    private static final int RES = 512;
    private static final Identifier ID = Identifier.of("bali", "stashmap");
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Bali-StashMap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private NativeImageBackedTexture texture;
    private volatile boolean busy;
    private volatile int[] readyPixels;
    private volatile StashMap.View readyView;
    private volatile StashMap readyMap;
    private StashMap.View shownView;
    private StashMap shownMap;
    private StashMap.View requestedView;
    private StashMap requestedMap;
    private long requestedVersion = -1;
    private boolean requestedOverlay;
    private long lastRequest;

    private StashMap autoFor;
    private double autoHalf, autoCx, autoCz;
    private double userZoom = 1, panX, panZ;

    private StashMapWidget() {}

    public record Hover(List<String> lines) {}

    private StashMap.View view(StashMap map, StashFinderBot bot, boolean user) {
        double[] me = bot.playerOnMap(map);
        double[] tgt = bot.currentTarget();
        boolean spiral = map.pattern.startsWith("Spiral");
        double cx, cz, needed;
        if (spiral) {
            cx = map.originX;
            cz = map.originZ;
            needed = map.reach();
            if (me != null) needed = Math.max(needed, Math.max(Math.abs(me[0] - cx), Math.abs(me[1] - cz)));
            if (tgt != null && bot.isEnabled()) needed = Math.max(needed, Math.max(Math.abs(tgt[0] - cx), Math.abs(tgt[1] - cz)));
        } else {
            double[] b = map.bounds();
            if (me != null) b = grow(b, me[0], me[1]);
            if (tgt != null && bot.isEnabled()) b = grow(b, tgt[0], tgt[1]);
            cx = (b[0] + b[2]) / 2;
            cz = (b[1] + b[3]) / 2;
            needed = Math.max(b[2] - b[0], b[3] - b[1]) / 2;
        }
        needed = Math.max(needed + 32, 128);
        if (autoFor != map || needed > autoHalf * 0.92 || needed < autoHalf * 0.3) {
            autoFor = map;
            autoHalf = needed * 1.25;
            autoCx = cx;
            autoCz = cz;
        } else if (!spiral && (Math.abs(cx - autoCx) > autoHalf * 0.15 || Math.abs(cz - autoCz) > autoHalf * 0.15)) {
            autoCx = cx;
            autoCz = cz;
        }
        if (!user) return new StashMap.View(autoCx, autoCz, autoHalf * 2 / RES);
        return new StashMap.View(autoCx + panX, autoCz + panZ, autoHalf * userZoom * 2 / RES);
    }

    private static double[] grow(double[] b, double x, double z) {
        return new double[]{Math.min(b[0], x), Math.min(b[1], z), Math.max(b[2], x), Math.max(b[3], z)};
    }

    public void zoom(double amount) {
        userZoom = Math.max(1 / 512.0, Math.min(4, userZoom * Math.pow(0.8, amount)));
    }

    public void pan(double dx, double dy, int size) {
        StashMap.View v = shownView;
        if (v == null) return;
        double blocksPerScreenPx = v.bpp() * RES / size;
        panX -= dx * blocksPerScreenPx;
        panZ -= dy * blocksPerScreenPx;
    }

    public void resetView() {
        userZoom = 1;
        panX = panZ = 0;
    }

    public boolean isUserView() { return userZoom != 1 || panX != 0 || panZ != 0; }

    public StashMap.View shownView() { return shownView; }

    public double[] screenPos(double wx, double wz, int x, int y, int size) {
        StashMap.View v = shownView;
        if (v == null) return null;
        double k = (double) size / RES;
        return new double[]{x + v.toPxX(wx, RES) * k, y + v.toPxZ(wz, RES) * k};
    }

    private void request(StashMap map, StashMap.View v, StashMap.Overlay overlay, boolean overlayOn) {
        long now = System.currentTimeMillis();
        boolean viewChanged = !v.equals(requestedView) || map != requestedMap || overlayOn != requestedOverlay;
        boolean dataChanged = map.version() != requestedVersion;
        if (busy || (!viewChanged && !dataChanged)) return;
        if (!viewChanged && now - lastRequest < 350) return;
        busy = true;
        requestedView = v;
        requestedMap = map;
        requestedVersion = map.version();
        requestedOverlay = overlayOn;
        lastRequest = now;
        worker.execute(() -> {
            try {
                int[] px = new int[RES * RES];
                map.render(StashMap.arrayCanvas(px, RES, RES), v, overlay, true);
                readyView = v;
                readyMap = map;
                readyPixels = px;
            } catch (Throwable t) {
                Bali.LOG.debug("stash map render failed", t);
            } finally {
                busy = false;
            }
        });
    }

    private void uploadIfReady() {
        int[] px = readyPixels;
        if (px == null) return;
        readyPixels = null;
        if (texture == null) {
            texture = new NativeImageBackedTexture(() -> "bali_stashmap", RES, RES, false);
            mc.getTextureManager().registerTexture(ID, texture);
        }
        NativeImage img = texture.getImage();
        if (img == null) return;
        for (int y = 0; y < RES; y++)
            for (int x = 0; x < RES; x++) img.setColorArgb(x, y, px[y * RES + x]);
        texture.upload();
        shownView = readyView;
        shownMap = readyMap;
    }

    public Hover draw(DrawContext ctx, int x, int y, int size, int mouseX, int mouseY, boolean interactive) {
        StashFinderBot bot = Bali.MODULES.get(StashFinderBot.class);
        TextRenderer tr = mc.textRenderer;
        StashMap map = bot.viewMap();
        ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF000000);
        if (map == null) {
            ctx.fill(x, y, x + size, y + size, StashMap.UNSEEN);
            String s = "No map yet";
            ctx.drawTextWithShadow(tr, s, x + size / 2 - tr.getWidth(s) / 2, y + size / 2 - 8, 0xFFB0B0B8);
            String s2 = "start StashFinderBot";
            if (tr.getWidth(s2) < size) ctx.drawTextWithShadow(tr, s2, x + size / 2 - tr.getWidth(s2) / 2, y + size / 2 + 2, 0xFF7A7A86);
            return null;
        }
        StashMap.View want = view(map, bot, interactive);
        boolean overlayOn = bot.mapOverlayOn();
        request(map, want, overlayOn ? bot.mapOverlay() : StashMap.Overlay.NONE, overlayOn);
        uploadIfReady();
        StashMap.View v = shownView;
        if (v == null || shownMap != map) {
            ctx.fill(x, y, x + size, y + size, StashMap.UNSEEN);
            return null;
        }
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ID, x, y, 0, 0, size, size, RES, RES, RES, RES);

        double k = (double) size / RES;
        ctx.enableScissor(x, y, x + size, y + size);
        Hover hover = null;
        try {
            int ox = sx(v, map.originX, x, k), oz = sz(v, map.originZ, y, k);
            ctx.fill(ox - 3, oz, ox + 4, oz + 1, StashMap.ORIGIN);
            ctx.fill(ox, oz - 3, ox + 1, oz + 4, StashMap.ORIGIN);

            double[] me = bot.playerOnMap(map);
            if (bot.mapPredictOn() && bot.isEnabled() && me != null) {
                List<double[]> pts = bot.predictedPath();
                double px = me[0], pz = me[1];
                double phase = 0;
                for (double[] p : pts) {
                    phase = dotted(ctx, v, x, y, k, px, pz, p[0], p[1], phase, StashMap.PREDICT);
                    px = p[0];
                    pz = p[1];
                }
                if (!pts.isEmpty()) {
                    double[] t = pts.get(0);
                    int tx = sx(v, t[0], x, k), tz = sz(v, t[1], y, k);
                    ctx.fill(tx - 1, tz - 1, tx + 2, tz + 2, StashMap.PREDICT);
                }
            }

            List<StashMap.Mark> marks = map.marks();
            int r = size >= 150 ? 3 : 2;
            StashMap.Mark hovered = null;
            int hoveredIndex = -1;
            double best = 7 * 7;
            for (int i = 0; i < marks.size(); i++) {
                StashMap.Mark m = marks.get(i);
                int mx = sx(v, m.x() + 0.5, x, k), mz = sz(v, m.z() + 0.5, y, k);
                ctx.fill(mx - r - 1, mz - r - 1, mx + r + 2, mz + r + 2, 0xFFFFFFFF);
                ctx.fill(mx - r, mz - r, mx + r + 1, mz + r + 1, StashMap.STASH);
                if (interactive && mouseX >= x && mouseY >= y && mouseX < x + size && mouseY < y + size) {
                    double d = (mouseX - mx) * (mouseX - mx) + (mouseY - mz) * (mouseY - mz);
                    if (d <= best) {
                        best = d;
                        hovered = m;
                        hoveredIndex = i;
                    }
                }
            }
            if (hovered != null) {
                int mx = sx(v, hovered.x() + 0.5, x, k), mz = sz(v, hovered.z() + 0.5, y, k);
                ctx.drawStrokedRectangle(mx - r - 3, mz - r - 3, 2 * r + 7, 2 * r + 7, 0xFFFFFF55);
            }

            if (me != null) {
                int px = sx(v, me[0], x, k), pz = sz(v, me[1], y, k);
                double yaw = Math.toRadians(me[2]);
                double hx = -Math.sin(yaw), hz = Math.cos(yaw);
                for (int i = 2; i <= 7; i++) {
                    int lx = (int) Math.round(px + hx * i), lz = (int) Math.round(pz + hz * i);
                    ctx.fill(lx, lz, lx + 1, lz + 1, 0xFFFFFFFF);
                }
                ctx.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFF000000);
                ctx.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFFFFFFFF);
            }

            if (hovered != null) hover = new Hover(stashLines(hovered, hoveredIndex, me));
            else if (interactive && mouseX >= x && mouseY >= y && mouseX < x + size && mouseY < y + size) {
                double wx = v.toWorldX((mouseX - x) / k, RES), wz = v.toWorldZ((mouseY - y) / k, RES);
                String nc = switch (map.overlayAt((int) Math.floor(wx) >> 4, (int) Math.floor(wz) >> 4)) {
                    case StashMap.NC_NEW -> Formatting.RED + "  new chunk";
                    case StashMap.NC_SINCE -> Formatting.GOLD + "  since 1.19";
                    case StashMap.NC_OLD -> Formatting.GREEN + "  old chunk";
                    default -> "";
                };
                hover = new Hover(List.of(Formatting.GRAY + "" + (long) Math.floor(wx) + ", " + (long) Math.floor(wz) + nc));
            }
        } finally {
            ctx.disableScissor();
        }

        if (size >= 90) {
            double step = StashMap.gridStep(v.bpp());
            String label = step >= 1000 ? StashMap.fmt(step / 1000) + " km" : StashMap.fmt(step) + " m";
            int len = (int) Math.round(step / v.bpp() * k);
            int bx = x + 4, by = y + size - 4;
            if (len < size - 8) {
                ctx.fill(bx, by, bx + len, by + 1, 0xFFFFFFFF);
                ctx.fill(bx, by - 3, bx + 1, by + 1, 0xFFFFFFFF);
                ctx.fill(bx + len - 1, by - 3, bx + len, by + 1, 0xFFFFFFFF);
                ctx.drawTextWithShadow(tr, label, bx + 2, by - 12, 0xFFFFFFFF);
            }
            String count = map.marks().size() + " stash" + (map.marks().size() == 1 ? "" : "es");
            ctx.drawTextWithShadow(tr, count, x + size - 3 - tr.getWidth(count), y + size - 11, map.marks().isEmpty() ? 0xFFB0B0B8 : StashMap.STASH);
        }
        return hover;
    }

    private static int sx(StashMap.View v, double wx, int x, double k) { return x + (int) Math.floor(v.toPxX(wx, RES) * k); }

    private static int sz(StashMap.View v, double wz, int y, double k) { return y + (int) Math.floor(v.toPxZ(wz, RES) * k); }

    private static double dotted(DrawContext ctx, StashMap.View v, int x, int y, double k, double ax, double az, double bx, double bz, double phase, int color) {
        double x0 = v.toPxX(ax, RES) * k + x, y0 = v.toPxZ(az, RES) * k + y;
        double x1 = v.toPxX(bx, RES) * k + x, y1 = v.toPxZ(bz, RES) * k + y;
        double len = Math.hypot(x1 - x0, y1 - y0);
        if (len > 4000) {
            double s = 4000 / len;
            x1 = x0 + (x1 - x0) * s;
            y1 = y0 + (y1 - y0) * s;
            len = 4000;
        }
        double d = phase;
        while (d < len) {
            double t = len == 0 ? 0 : d / len;
            int px = (int) Math.floor(x0 + (x1 - x0) * t), py = (int) Math.floor(y0 + (y1 - y0) * t);
            ctx.fill(px, py, px + 1, py + 1, color);
            d += 3;
        }
        return d - len;
    }

    private static List<String> stashLines(StashMap.Mark m, int index, double[] me) {
        List<String> l = new ArrayList<>();
        l.add(Formatting.LIGHT_PURPLE + "Possible stash #" + (index + 1) + Formatting.WHITE + "  " + m.x() + " " + m.y() + " " + m.z());
        for (String s : m.lines()) l.add(s);
        if (me != null) l.add(Formatting.GRAY + String.format(Locale.ROOT, "%,d m from you", (long) Math.hypot(m.x() - me[0], m.z() - me[1])));
        return l;
    }

    public static void drawTooltip(DrawContext ctx, List<String> lines, int mouseX, int mouseY, int screenW, int screenH) {
        TextRenderer tr = mc.textRenderer;
        int w = 0;
        for (String s : lines) w = Math.max(w, tr.getWidth(s));
        int h = lines.size() * 10 + 4;
        int tx = mouseX + 10, ty = mouseY - 4;
        if (tx + w + 6 > screenW) tx = mouseX - w - 12;
        if (ty + h > screenH) ty = screenH - h - 2;
        if (ty < 2) ty = 2;
        ctx.fill(tx - 2, ty - 2, tx + w + 4, ty + h, 0xF0101016);
        ctx.fill(tx - 2, ty - 2, tx + w + 4, ty - 1, StashMap.STASH);
        int yy = ty + 1;
        for (String s : lines) {
            ctx.drawTextWithShadow(tr, s, tx + 1, yy, 0xFFE6E6E6);
            yy += 10;
        }
    }
}
