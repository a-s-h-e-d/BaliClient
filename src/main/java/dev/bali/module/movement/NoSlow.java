package dev.bali.module.movement;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.util.InvUtil;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;

public final class NoSlow extends Module {
    public enum Mode { Vanilla, Grim }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Grim, "Bypass method");
    public final BoolSetting items = bool("Items", true, "No slowdown while eating/using items");
    public final BoolSetting sprint = bool("Sprint", true, "Keep sprinting while using items");

    public NoSlow() {
        super("NoSlow", "Removes item-use slowdown", Category.MOVEMENT);
    }

    @Override
    public String getInfo() { return mode.get().name(); }

    public boolean active() {
        if (!isEnabled() || !items.on() || mc.player == null || !mc.player.isUsingItem()) return false;
        return mode.is(Mode.Vanilla) || mc.player.getActiveHand() == Hand.OFF_HAND;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (!mode.is(Mode.Grim) || !mc.player.isUsingItem() || mc.player.getActiveHand() != Hand.OFF_HAND) return;
        int sel = InvUtil.selected();
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket((sel + 1) % 9));
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(sel));
    }
}
