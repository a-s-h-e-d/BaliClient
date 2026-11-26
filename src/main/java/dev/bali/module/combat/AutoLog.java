package dev.bali.module.combat;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.DamageUtil;
import dev.bali.util.InvUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

public final class AutoLog extends Module {
    private final NumberSetting health = number("Health", 6, 0, 36, 0.5, "Log out at or below this health (0 = off)");
    private final NumberSetting totems = number("Totems", 0, 0, 10, 1, "Log out when totems drop to this (0 = off)");
    private final BoolSetting onPlayer = bool("Player In Range", false, "Log out when a non-friend player gets close");
    private final NumberSetting playerRange = number("Player Range", 32, 4, 128, 1, "Range for Player In Range").visibleWhen(() -> onPlayer != null && onPlayer.on());
    private final BoolSetting disableAfter = bool("Disable After", true, "Toggle off after logging so you can rejoin");

    public AutoLog() {
        super("AutoLog", "Disconnects when in danger", Category.COMBAT);
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        if (mc.player.isCreative()) return;
        String reason = null;
        if (health.getD() > 0 && DamageUtil.health(mc.player) <= health.getF()) reason = "Health " + String.format("%.1f", DamageUtil.health(mc.player));
        else if (totems.getI() > 0 && InvUtil.count(Items.TOTEM_OF_UNDYING) <= totems.getI()) reason = "Out of totems";
        else if (onPlayer.on()) {
            for (Entity ent : mc.world.getPlayers()) {
                if (ent == mc.player || !(ent instanceof PlayerEntity p) || dev.bali.Bali.FRIENDS.isFriend(p)) continue;
                if (p.getId() < 0) continue;
                if (p.distanceTo(mc.player) <= playerRange.getF()) {
                    reason = "Player " + p.getGameProfile().name() + " in range";
                    break;
                }
            }
        }
        if (reason == null) return;
        if (disableAfter.on()) toggle();
        mc.getNetworkHandler().getConnection().disconnect(Text.literal("[AutoLog] " + reason));
    }
}
