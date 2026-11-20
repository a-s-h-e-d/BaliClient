package dev.bali.module.player;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import net.minecraft.item.Items;

public final class FastPlace extends Module {
    private final NumberSetting delay = number("Delay", 0, 0, 4, 1, "Right-click delay in ticks (vanilla is 4)");
    private final BoolSetting crystalsOnly = bool("Crystals Only", false, "Only when holding end crystals / exp");

    public FastPlace() {
        super("FastPlace", "Removes the right-click delay", Category.PLAYER);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (crystalsOnly.on()) {
            var main = mc.player.getMainHandStack();
            var off = mc.player.getOffHandStack();
            boolean ok = main.isOf(Items.END_CRYSTAL) || off.isOf(Items.END_CRYSTAL) || main.isOf(Items.EXPERIENCE_BOTTLE) || off.isOf(Items.EXPERIENCE_BOTTLE);
            if (!ok) return;
        }
        if (mc.itemUseCooldown > delay.getI()) mc.itemUseCooldown = delay.getI();
    }
}
