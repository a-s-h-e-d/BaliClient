package dev.bali.gui;

import dev.bali.Bali;
import dev.bali.setting.BlockListSetting;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BlockPickerScreen extends Screen {
    private static final int CELL = 20;

    private final Screen parent;
    private final BlockListSetting setting;
    private final List<Block> all = new ArrayList<>();
    private List<Block> shown = new ArrayList<>();
    private String search = "";
    private int scroll;
    private boolean onlySelected;

    public BlockPickerScreen(Screen parent, BlockListSetting setting) {
        super(Text.literal("Blocks"));
        this.parent = parent;
        this.setting = setting;
        for (Block b : Registries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
            all.add(b);
        }
        all.sort((a, b) -> Registries.BLOCK.getId(a).getPath().compareTo(Registries.BLOCK.getId(b).getPath()));
        refilter();
    }

    private void refilter() {
        List<Block> sel = new ArrayList<>(), rest = new ArrayList<>();
        for (Block b : all) {
            String id = Registries.BLOCK.getId(b).getPath();
            String name = b.getName().getString().toLowerCase(Locale.ROOT);
            if (!search.isEmpty() && !id.contains(search) && !name.contains(search)) continue;
            if (setting.contains(b)) sel.add(b);
            else if (!onlySelected) rest.add(b);
        }
        sel.addAll(rest);
        shown = sel;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int cols() { return Math.max(1, (width - 40) / CELL); }
    private int gridTop() { return 40; }
    private int visibleRows() { return Math.max(1, (height - gridTop() - 20) / CELL); }
    private int maxScroll() { return Math.max(0, (shown.size() + cols() - 1) / cols() - visibleRows()); }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xB0000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        int accent = Bali.MODULES.clickGui().accent.argb() | 0xFF000000;
        String header = setting.name + " - " + setting.get().size() + " selected";
        ctx.drawTextWithShadow(textRenderer, header, 20, 8, accent);
        String hint = "Type to search  |  Tab: " + (onlySelected ? "show all" : "selected only") + "  |  Esc: back";
        ctx.drawTextWithShadow(textRenderer, hint, width - 20 - textRenderer.getWidth(hint), 8, 0xFF9A9AA6);
        ctx.fill(20, 20, width - 20, 34, 0xC01C1C24);
        ctx.drawTextWithShadow(textRenderer, search.isEmpty() ? "Search..." : search + "_", 24, 23, search.isEmpty() ? 0xFF707078 : 0xFFFFFFFF);

        int cols = cols();
        int start = scroll * cols;
        int end = Math.min(shown.size(), start + visibleRows() * cols);
        Block hovered = null;
        for (int i = start; i < end; i++) {
            Block b = shown.get(i);
            int idx = i - start;
            int x = 20 + (idx % cols) * CELL, y = gridTop() + (idx / cols) * CELL;
            boolean sel = setting.contains(b);
            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            ctx.fill(x, y, x + CELL - 2, y + CELL - 2, sel ? (accent & 0x00FFFFFF) | 0x90000000 : hover ? 0x60FFFFFF : 0x40000000);
            ItemStack icon = new ItemStack(b.asItem());
            if (icon.isEmpty() || icon.isOf(Items.AIR)) {
                String s = b.getName().getString().substring(0, Math.min(2, b.getName().getString().length()));
                ctx.drawTextWithShadow(textRenderer, s, x + 3, y + 5, 0xFFFFFFFF);
            } else {
                ctx.drawItem(icon, x + 1, y + 1);
            }
            if (hover) hovered = b;
        }
        if (hovered != null) {
            String n = hovered.getName().getString() + "  (" + Registries.BLOCK.getId(hovered) + ")";
            int w = textRenderer.getWidth(n);
            int tx = Math.min(mouseX + 10, width - w - 4);
            ctx.fill(tx - 2, mouseY - 12, tx + w + 2, mouseY, 0xF0141419);
            ctx.drawTextWithShadow(textRenderer, n, tx, mouseY - 10, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int cols = cols();
        int mx = (int) click.x(), my = (int) click.y();
        if (mx >= 20 && my >= gridTop()) {
            int col = (mx - 20) / CELL, row = (my - gridTop()) / CELL;
            if (col < cols && row < visibleRows()) {
                int i = scroll * cols + row * cols + col;
                if (i < shown.size()) {
                    setting.toggle(shown.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(v) * 2));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            refilter();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            onlySelected = !onlySelected;
            refilter();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        String s = input.asString().toLowerCase(Locale.ROOT);
        if (!s.isEmpty() && search.length() < 32) {
            search += s;
            scroll = 0;
            refilter();
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void close() {
        Bali.CONFIG.save();
        if (client != null) client.setScreen(parent);
    }
}
