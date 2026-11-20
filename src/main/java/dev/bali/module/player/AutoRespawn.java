package dev.bali.module.player;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.util.ChatUtil;
import net.minecraft.client.gui.screen.DeathScreen;

public final class AutoRespawn extends Module {
    public AutoRespawn() {
        super("AutoRespawn", "Respawns instantly", Category.PLAYER);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (mc.currentScreen instanceof DeathScreen && mc.player.isDead()) {
            ChatUtil.info(String.format("Died at %.0f %.0f %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            mc.player.requestRespawn();
            mc.setScreen(null);
        }
    }
}
