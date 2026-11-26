package dev.bali.gui;

import dev.bali.Bali;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.ModuleManager;
import dev.bali.setting.BlockListSetting;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.setting.Setting;
import dev.bali.util.KeyNames;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ClickGuiScreen extends Screen {
    public static final Map<String, int[]> PANEL_STATE = new HashMap<>();
    private static final Set<Module> EXPANDED = new HashSet<>();

    private static final int W = 112, HEADER = 15, ROW = 13, SROW = 12;
    private static final int BG = 0xE0141419, ROW_BG = 0xC01C1C24, SET_BG = 0xC0121218, TEXT = 0xFFE6E6E6, DIM = 0xFF9A9AA6;

    private enum Kind { HEADER, MODULE, BOOL, NUMBER, ENUM, COLOR_HEADER, COLOR_CHANNEL, BLOCKS, BIND, DRAWN }

    private record Row(Kind kind, int x, int y, int w, int h, Category cat, Module module, Setting<?> setting, int channel) {
        boolean hit(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final List<Row> rows = new ArrayList<>();
    private Category dragging;
    private int dragOffX, dragOffY;
    private Setting<?> sliding;
    private int slidingChannel = -1;
    private Row slidingRow;
    private Module binding;
    private String search = "";

    private static final String MAP_KEY = "STASHMAP";
    private static final int MAP_SIZE = 220;
    private boolean mapVisible;
    private int mapX, mapY;
    private boolean mapOpen;
    private boolean draggingMap, panningMap;
    private StashMapWidget.Hover mapHover;

    public static void expand(Module m) { EXPANDED.add(m); }

    public ClickGuiScreen() {
        super(Text.literal("Bali"));
        int x = 6;
        for (Category c : Category.values()) {
            PANEL_STATE.putIfAbsent(c.name(), new int[]{x, 6, 1});
            x += W + 6;
        }
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (Bali.MODULES.clickGui().darken.on()) ctx.fill(0, 0, width, height, 0x60000000);
    }

    private static float scale() { return Bali.MODULES.clickGui().scale.getF(); }

    @Override
    public void render(DrawContext ctx, int rawMouseX, int rawMouseY, float delta) {
        renderBackground(ctx, rawMouseX, rawMouseY, delta);
        float s = scale();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(s, s);
        try {
            renderScaled(ctx, (int) (rawMouseX / s), (int) (rawMouseY / s), (int) (width / s), (int) (height / s));
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }

    private void renderScaled(DrawContext ctx, int mouseX, int mouseY, int width, int height) {
        int accent = Bali.MODULES.clickGui().accent.argb() | 0xFF000000;
        rows.clear();
        Module hovered = null;

        for (Category c : Category.values()) {
            int[] st = PANEL_STATE.get(c.name());
            int x = st[0], y = st[1];
            boolean open = st[2] == 1;

            List<Module> mods = new ArrayList<>();
            for (Module m : Bali.MODULES.inCategory(c)) {
                if (search.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(search)) mods.add(m);
            }
            if (!search.isEmpty() && mods.isEmpty()) continue;

            ctx.fill(x, y, x + W, y + HEADER, accent);
            ctx.drawTextWithShadow(textRenderer, c.title, x + 4, y + 4, 0xFFFFFFFF);
            String count = String.valueOf(mods.size());
            ctx.drawTextWithShadow(textRenderer, count, x + W - 4 - textRenderer.getWidth(count), y + 4, 0xFFFFFFFF);
            rows.add(new Row(Kind.HEADER, x, y, W, HEADER, c, null, null, -1));
            int cy = y + HEADER;
            if (!open) continue;

            for (Module m : mods) {
                ctx.fill(x, cy, x + W, cy + ROW, ROW_BG);
                if (m.isEnabled()) ctx.fill(x, cy, x + 2, cy + ROW, accent);
                ctx.drawTextWithShadow(textRenderer, m.name, x + 5, cy + 3, m.isEnabled() ? accent : TEXT);
                if (!m.settings.isEmpty() || true) {
                    String arrow = EXPANDED.contains(m) ? "-" : "+";
                    ctx.drawTextWithShadow(textRenderer, arrow, x + W - 8, cy + 3, DIM);
                }
                Row r = new Row(Kind.MODULE, x, cy, W, ROW, c, m, null, -1);
                rows.add(r);
                if (r.hit(mouseX, mouseY)) hovered = m;
                cy += ROW;
                if (EXPANDED.contains(m)) cy = renderSettings(ctx, m, x, cy, accent);
            }
            ctx.fill(x, cy, x + W, cy + 2, accent);
        }

        mapHover = null;
        mapVisible = mapPanelWanted();
        if (mapVisible) {
            if (mouseOverMap(mouseX, mouseY)) hovered = null;
            renderMapPanel(ctx, mouseX, mouseY, width, accent);
        }

        if (!search.isEmpty()) {
            String s = "Search: " + search;
            ctx.drawTextWithShadow(textRenderer, s, width / 2 - textRenderer.getWidth(s) / 2, height - 24, accent);
        }
        if (hovered != null) {
            String d = hovered.description;
            int tw = textRenderer.getWidth(d);
            ctx.fill(mouseX + 8, mouseY - 2, mouseX + 12 + tw, mouseY + 10, BG);
            ctx.drawTextWithShadow(textRenderer, d, mouseX + 10, mouseY, TEXT);
        }
        if (binding != null) {
            String s = "Press a key to bind " + binding.name + " (Esc = none)";
            ctx.drawTextWithShadow(textRenderer, s, width / 2 - textRenderer.getWidth(s) / 2, height - 12, 0xFFFFFF55);
        }
        if (mapHover != null && !draggingMap && !panningMap) StashMapWidget.drawTooltip(ctx, mapHover.lines(), mouseX, mouseY, width, height);
    }

    private static boolean mapPanelWanted() {
        Module bot = Bali.MODULES.get(dev.bali.module.misc.StashFinderBot.class);
        return bot.isEnabled() || EXPANDED.contains(bot);
    }

    private void renderMapPanel(DrawContext ctx, int mouseX, int mouseY, int width, int accent) {
        int[] st = PANEL_STATE.computeIfAbsent(MAP_KEY, k -> new int[]{Math.max(6, width - MAP_SIZE - 8), 6, 1});
        mapX = st[0];
        mapY = st[1];
        mapOpen = st[2] == 1;
        int w = MAP_SIZE + 2;
        ctx.fill(mapX, mapY, mapX + w, mapY + HEADER, accent);
        ctx.drawTextWithShadow(textRenderer, "Stash Map", mapX + 4, mapY + 4, 0xFFFFFFFF);
        String mode = StashMapWidget.INSTANCE.isUserView() ? "manual" : "auto zoom";
        ctx.drawTextWithShadow(textRenderer, mode, mapX + w - 4 - textRenderer.getWidth(mode), mapY + 4, 0xFFFFFFFF);
        if (!mapOpen) return;
        int by = mapY + HEADER;
        ctx.fill(mapX, by, mapX + w, by + MAP_SIZE + 2 + SROW, BG);
        boolean over = mouseX >= mapX + 1 && mouseY >= by + 1 && mouseX < mapX + 1 + MAP_SIZE && mouseY < by + 1 + MAP_SIZE;
        mapHover = StashMapWidget.INSTANCE.draw(ctx, mapX + 1, by + 1, MAP_SIZE, over ? mouseX : -1, over ? mouseY : -1, true);
        String hint = "scroll zoom, drag pan, right click auto";
        ctx.drawTextWithShadow(textRenderer, hint, mapX + 4, by + MAP_SIZE + 4, DIM);
        ctx.fill(mapX, by + MAP_SIZE + 2 + SROW, mapX + w, by + MAP_SIZE + 4 + SROW, accent);
    }

    private boolean mouseOverMap(double mx, double my) {
        if (!mapVisible) return false;
        int bottom = mapY + HEADER + (mapOpen ? MAP_SIZE + 4 + SROW : 0);
        return mx >= mapX && mx < mapX + MAP_SIZE + 2 && my >= mapY && my < bottom;
    }

    private boolean overMapBody(double mx, double my) {
        int by = mapY + HEADER + 1;
        return mapVisible && mapOpen && mx >= mapX + 1 && mx < mapX + 1 + MAP_SIZE && my >= by && my < by + MAP_SIZE;
    }

    private int renderSettings(DrawContext ctx, Module m, int x, int y, int accent) {
        for (Setting<?> s : m.settings) {
            if (!s.isVisible()) continue;
            ctx.fill(x, y, x + W, y + SROW, SET_BG);
            if (s instanceof BlockListSetting bl) {
                ctx.drawTextWithShadow(textRenderer, s.name, x + 7, y + 2, TEXT);
                String v = bl.get().size() + " >";
                ctx.drawTextWithShadow(textRenderer, v, x + W - 4 - textRenderer.getWidth(v), y + 2, accent);
                rows.add(new Row(Kind.BLOCKS, x, y, W, SROW, m.category, m, s, -1));
            } else if (s instanceof BoolSetting b) {
                ctx.drawTextWithShadow(textRenderer, s.name, x + 7, y + 2, b.on() ? accent : DIM);
                rows.add(new Row(Kind.BOOL, x, y, W, SROW, m.category, m, s, -1));
            } else if (s instanceof NumberSetting n) {
                double pct = (n.getD() - n.min) / (n.max - n.min);
                ctx.fill(x + 4, y + SROW - 2, x + 4 + (int) ((W - 8) * pct), y + SROW - 1, accent);
                ctx.drawTextWithShadow(textRenderer, s.name, x + 7, y + 2, TEXT);
                String v = n.displayValue();
                ctx.drawTextWithShadow(textRenderer, v, x + W - 4 - textRenderer.getWidth(v), y + 2, DIM);
                rows.add(new Row(Kind.NUMBER, x, y, W, SROW, m.category, m, s, -1));
            } else if (s instanceof EnumSetting<?> en) {
                ctx.drawTextWithShadow(textRenderer, s.name, x + 7, y + 2, TEXT);
                String v = en.get().name();
                ctx.drawTextWithShadow(textRenderer, v, x + W - 4 - textRenderer.getWidth(v), y + 2, accent);
                rows.add(new Row(Kind.ENUM, x, y, W, SROW, m.category, m, s, -1));
            } else if (s instanceof ColorSetting col) {
                ctx.drawTextWithShadow(textRenderer, s.name, x + 7, y + 2, TEXT);
                ctx.fill(x + W - 14, y + 2, x + W - 4, y + SROW - 2, col.argb() | 0xFF000000);
                rows.add(new Row(Kind.COLOR_HEADER, x, y, W, SROW, m.category, m, s, -1));
                y += SROW;
                String[] names = {"R", "G", "B", "A"};
                int[] colors = {0xFFFF4040, 0xFF40FF40, 0xFF4080FF, 0xFFC0C0C0};
                for (int ch = 0; ch < 4; ch++) {
                    ctx.fill(x, y, x + W, y + SROW, SET_BG);
                    double pct = col.channel(ch) / 255.0;
                    ctx.fill(x + 14, y + SROW - 2, x + 14 + (int) ((W - 18) * pct), y + SROW - 1, colors[ch]);
                    ctx.drawTextWithShadow(textRenderer, names[ch], x + 7, y + 2, DIM);
                    String v = String.valueOf(col.channel(ch));
                    ctx.drawTextWithShadow(textRenderer, v, x + W - 4 - textRenderer.getWidth(v), y + 2, DIM);
                    rows.add(new Row(Kind.COLOR_CHANNEL, x, y, W, SROW, m.category, m, s, ch));
                    y += SROW;
                }
                continue;
            }
            y += SROW;
        }
        ctx.fill(x, y, x + W, y + SROW, SET_BG);
        String bind = binding == m ? "..." : KeyNames.name(m.getKey());
        ctx.drawTextWithShadow(textRenderer, "Bind", x + 7, y + 2, TEXT);
        ctx.drawTextWithShadow(textRenderer, bind, x + W - 4 - textRenderer.getWidth(bind), y + 2, DIM);
        rows.add(new Row(Kind.BIND, x, y, W, SROW, m.category, m, null, -1));
        y += SROW;
        ctx.fill(x, y, x + W, y + SROW, SET_BG);
        ctx.drawTextWithShadow(textRenderer, "Drawn", x + 7, y + 2, m.isDrawn() ? accent : DIM);
        String dv = m.isDrawn() ? "Shown" : "Hidden";
        ctx.drawTextWithShadow(textRenderer, dv, x + W - 4 - textRenderer.getWidth(dv), y + 2, DIM);
        rows.add(new Row(Kind.DRAWN, x, y, W, SROW, m.category, m, null, -1));
        return y + SROW;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x() / scale(), my = click.y() / scale();
        int button = click.button();
        if (binding != null) {
            if (button > 1) {
                binding.setKey(ModuleManager.MOUSE_OFFSET + button);
                binding = null;
                return true;
            }
            binding = null;
        }
        if (mouseOverMap(mx, my)) {
            if (my < mapY + HEADER) {
                int[] st = PANEL_STATE.get(MAP_KEY);
                if (button == 0) {
                    draggingMap = true;
                    dragOffX = (int) mx - st[0];
                    dragOffY = (int) my - st[1];
                } else if (button == 1) st[2] = st[2] == 1 ? 0 : 1;
            } else if (overMapBody(mx, my)) {
                if (button == 0) panningMap = true;
                else if (button == 1) StashMapWidget.INSTANCE.resetView();
            }
            return true;
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row r = rows.get(i);
            if (!r.hit(mx, my)) continue;
            switch (r.kind) {
                case HEADER -> {
                    int[] st = PANEL_STATE.get(r.cat.name());
                    if (button == 0) {
                        dragging = r.cat;
                        dragOffX = (int) mx - st[0];
                        dragOffY = (int) my - st[1];
                    } else if (button == 1) {
                        st[2] = st[2] == 1 ? 0 : 1;
                    }
                }
                case MODULE -> {
                    if (button == 0) r.module.toggle();
                    else if (button == 1) {
                        if (!EXPANDED.remove(r.module)) EXPANDED.add(r.module);
                    }
                }
                case BOOL -> ((BoolSetting) r.setting).toggle();
                case NUMBER -> {
                    sliding = r.setting;
                    slidingRow = r;
                    slidingChannel = -1;
                    slide(mx);
                }
                case ENUM -> {
                    if (button == 1) ((EnumSetting<?>) r.setting).cycleBack();
                    else ((EnumSetting<?>) r.setting).cycle();
                }
                case COLOR_HEADER -> {}
                case BLOCKS -> {
                    if (client != null) client.setScreen(new BlockPickerScreen(this, (BlockListSetting) r.setting));
                }
                case COLOR_CHANNEL -> {
                    sliding = r.setting;
                    slidingRow = r;
                    slidingChannel = r.channel;
                    slide(mx);
                }
                case BIND -> binding = r.module;
                case DRAWN -> r.module.setDrawn(!r.module.isDrawn());
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private void slide(double mx) {
        if (sliding == null || slidingRow == null) return;
        if (sliding instanceof NumberSetting n) {
            double pct = Math.max(0, Math.min(1, (mx - (slidingRow.x + 4)) / (double) (W - 8)));
            n.set(n.min + (n.max - n.min) * pct);
        } else if (sliding instanceof ColorSetting c && slidingChannel >= 0) {
            double pct = Math.max(0, Math.min(1, (mx - (slidingRow.x + 14)) / (double) (W - 18)));
            c.setChannel(slidingChannel, (int) Math.round(pct * 255));
        }
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (draggingMap) {
            int[] st = PANEL_STATE.get(MAP_KEY);
            st[0] = (int) (click.x() / scale()) - dragOffX;
            st[1] = (int) (click.y() / scale()) - dragOffY;
            return true;
        }
        if (panningMap) {
            StashMapWidget.INSTANCE.pan(dx / scale(), dy / scale(), MAP_SIZE);
            return true;
        }
        if (dragging != null) {
            int[] st = PANEL_STATE.get(dragging.name());
            st[0] = (int) (click.x() / scale()) - dragOffX;
            st[1] = (int) (click.y() / scale()) - dragOffY;
            return true;
        }
        if (sliding != null) {
            for (Row r : rows) {
                if (r.setting == sliding && r.channel == slidingChannel) {
                    slidingRow = r;
                    break;
                }
            }
            slide(click.x() / scale());
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        draggingMap = panningMap = false;
        sliding = null;
        slidingRow = null;
        slidingChannel = -1;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (overMapBody(mouseX / scale(), mouseY / scale())) {
            StashMapWidget.INSTANCE.zoom(v);
            return true;
        }
        for (int[] st : PANEL_STATE.values()) st[1] += (int) (v * 14);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (binding != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) binding.setKey(-1);
            else binding.setKey(key);
            binding = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && !search.isEmpty()) {
            search = "";
            return true;
        }
        if (key == Bali.MODULES.clickGui().getKey() && search.isEmpty()) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (binding != null) return true;
        String s = input.asString();
        if (!s.isBlank() && search.length() < 24) {
            search += s.toLowerCase(Locale.ROOT);
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        Bali.CONFIG.save();
        super.close();
    }
}
