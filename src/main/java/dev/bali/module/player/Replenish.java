package dev.bali.module.player;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.NumberSetting;
import dev.bali.util.InvUtil;
import net.minecraft.item.ItemStack;

public final class Replenish extends Module {
    private final NumberSetting threshold = number("Threshold", 16, 1, 63, 1, "Refill when a stack drops to this");
    private final NumberSetting delay = number("Delay", 2, 0, 20, 1, "Ticks between refills");

    private int timer;

    public Replenish() {
        super("Replenish", "Keeps hotbar stacks full", Category.PLAYER);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (timer > 0) { timer--; return; }
        if (!InvUtil.canClickInventory() || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isEmpty() || !s.isStackable() || s.getCount() > threshold.getI() || s.getCount() >= s.getMaxCount()) continue;
            for (int j = 9; j < 36; j++) {
                ItemStack o = mc.player.getInventory().getStack(j);
                if (!ItemStack.areItemsAndComponentsEqual(s, o)) continue;
                if (!dev.bali.util.ClickGate.tryClick()) return;
                InvUtil.pickup(InvUtil.toScreenSlot(j));
                InvUtil.pickup(InvUtil.toScreenSlot(i));
                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) InvUtil.pickup(InvUtil.toScreenSlot(j));
                timer = delay.getI();
                return;
            }
        }
    }
}
