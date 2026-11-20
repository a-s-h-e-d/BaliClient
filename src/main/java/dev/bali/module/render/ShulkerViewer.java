package dev.bali.module.render;

import dev.bali.Bali;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.util.DyeColor;
import net.minecraft.util.collection.DefaultedList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hover a shulker box (or any item holding a container, like a picked-up chest) in any inventory or
 * container screen to see its full contents as a slot grid instead of vanilla's 5-line text list.
 * Purely client-side: the contents are already in the item data the server sends.
 */
public final class ShulkerViewer extends Module {
    public enum Mode { Grid, Compact }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Grid, "Grid = real slot layout, Compact = same items merged with totals");
    private final BoolSetting tint = bool("Tint", true, "Color the preview like the shulker box");
    private final BoolSetting hideText = bool("Hide Text", true, "Hide vanilla's text list of contents");
    private final BoolSetting showEmpty = bool("Show Empty", false, "Also show a grid for empty shulkers");

    public ShulkerViewer() {
        super("ShulkerViewer", "Shows full shulker contents in item tooltips", Category.RENDER);
        TooltipComponentCallback.EVENT.register(data -> data instanceof Preview p ? new PreviewComponent(p) : null);
    }

    public static ShulkerViewer active() {
        if (Bali.MODULES == null) return null;
        ShulkerViewer v = Bali.MODULES.get(ShulkerViewer.class);
        return v != null && v.isEnabled() ? v : null;
    }

    public boolean hidesText() { return hideText.on(); }

    /** Called from the ItemStack mixin; empty when this stack shouldn't get a preview. */
    public Optional<TooltipData> preview(ItemStack stack) {
        ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
        boolean shulker = stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
        if (c == null && !shulker) return Optional.empty();
        if (c == null) c = ContainerComponent.DEFAULT;
        boolean empty = !c.iterateNonEmpty().iterator().hasNext();
        if (empty && !showEmpty.on()) return Optional.empty();

        DefaultedList<ItemStack> slots = DefaultedList.ofSize(27, ItemStack.EMPTY);
        c.copyTo(slots);
        List<ItemStack> shown;
        int cols = 9;
        if (mode.is(Mode.Compact)) {
            shown = new ArrayList<>();
            outer:
            for (ItemStack s : slots) {
                if (s.isEmpty()) continue;
                for (int i = 0; i < shown.size(); i++) {
                    ItemStack m = shown.get(i);
                    if (ItemStack.areItemsAndComponentsEqual(m, s)) {
                        shown.set(i, m.copyWithCount(m.getCount() + s.getCount()));
                        continue outer;
                    }
                }
                shown.add(s.copy());
            }
            if (shown.isEmpty()) shown.add(ItemStack.EMPTY);
            cols = Math.min(9, shown.size());
        } else {
            shown = new ArrayList<>(slots);
        }
        return Optional.of(new Preview(shown, cols, tint.on() ? colorOf(stack) : 0));
    }

    private static int colorOf(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock sb) {
            DyeColor d = sb.getColor();
            return d == null ? 0x976997 : d.getEntityColor() & 0xFFFFFF; // undyed shulker purple
        }
        return 0x8B6A3E; // chest brown
    }

    public record Preview(List<ItemStack> stacks, int cols, int rgb) implements TooltipData {}

    private static final class PreviewComponent implements TooltipComponent {
        private static final int SLOT = 18;
        private final Preview p;
        private final int rows;

        PreviewComponent(Preview p) {
            this.p = p;
            this.rows = Math.max(1, (p.stacks().size() + p.cols() - 1) / p.cols());
        }

        @Override
        public int getHeight(TextRenderer tr) { return rows * SLOT + 6; }

        @Override
        public int getWidth(TextRenderer tr) { return p.cols() * SLOT + 2; }

        @Override
        public void drawItems(TextRenderer tr, int x, int y, int width, int height, DrawContext ctx) {
            int w = p.cols() * SLOT + 2, h = rows * SLOT + 2;
            int top = y + 2;
            if (p.rgb() != 0) ctx.fill(x, top, x + w, top + h, 0x70000000 | p.rgb());
            for (int i = 0; i < p.stacks().size(); i++) {
                int sx = x + 1 + (i % p.cols()) * SLOT, sy = top + 1 + (i / p.cols()) * SLOT;
                ctx.fill(sx, sy, sx + SLOT - 1, sy + SLOT - 1, 0x60000000);
                ItemStack s = p.stacks().get(i);
                if (s.isEmpty()) continue;
                ctx.drawItem(s, sx + 1, sy + 1);
                ctx.drawStackOverlay(tr, s, sx + 1, sy + 1);
            }
        }
    }
}
