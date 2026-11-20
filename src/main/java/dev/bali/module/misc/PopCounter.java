package dev.bali.module.misc;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.util.ChatUtil;
import net.minecraft.util.Formatting;

public final class PopCounter extends Module {
    private final BoolSetting self = bool("Self", false, "Also announce your own pops");

    public PopCounter() {
        super("PopCounter", "Announces totem pops in chat", Category.MISC);
    }

    @EventHandler
    private void onPop(Events.TotemPop e) {
        if (e.player == mc.player && !self.on()) return;
        ChatUtil.info(Formatting.WHITE + e.player.getGameProfile().name() + Formatting.GRAY + " popped "
            + Formatting.RED + e.pops + Formatting.GRAY + (e.pops == 1 ? " totem" : " totems"));
    }

    @EventHandler
    private void onDeath(Events.Death e) {
        if (e.player == mc.player && !self.on()) return;
        ChatUtil.info(Formatting.WHITE + e.player.getGameProfile().name() + Formatting.GRAY + " died after popping "
            + Formatting.RED + e.pops + Formatting.GRAY + (e.pops == 1 ? " totem" : " totems"));
    }
}
