package dev.bali.module.movement;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.EnumSetting;
import dev.bali.util.EntityUtil;

public final class Sprint extends Module {
    public enum Mode { Legit, Omni }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Legit, "Legit is Grim-safe");

    public Sprint() {
        super("Sprint", "Sprints automatically", Category.MOVEMENT);
    }

    @Override
    public String getInfo() { return mode.get().name(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (mc.player.isSneaking() || mc.player.getHungerManager().getFoodLevel() <= 6 || mc.player.horizontalCollision) return;
        if (mc.player.isGliding()) return;
        if (dev.bali.Bali.MODULES.get(dev.bali.module.combat.ElytraMace.class).busy()) return;
        if (dev.bali.Bali.MODULES.get(ElytraRecast.class).busy()) return;
        if (dev.bali.util.ClickGate.holding()) return;
        if (mode.is(Mode.Legit)) {
            if (mc.player.input.hasForwardMovement() && !mc.player.isUsingItem()) mc.options.sprintKey.setPressed(true);
        } else if (EntityUtil.isMoving()) {
            mc.player.setSprinting(true);
        }
    }

    @Override
    protected void onDisable() {
        if (mc.options != null) mc.options.sprintKey.setPressed(false);
    }
}
