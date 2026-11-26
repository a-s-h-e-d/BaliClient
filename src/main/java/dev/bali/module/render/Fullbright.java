package dev.bali.module.render;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

public final class Fullbright extends Module {
    public enum Mode { Gamma, NightVision }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Gamma, "Gamma boost or client-side night vision");
    public final NumberSetting gamma = number("Gamma", 10, 1, 20, 0.5, "Gamma value (Gamma mode)").visibleWhen(() -> isGamma());

    public Fullbright() {
        super("Fullbright", "See in the dark", Category.RENDER);
    }

    private boolean isGamma() { return mode != null && mode.is(Mode.Gamma); }

    public boolean gammaActive() { return isEnabled() && isGamma(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (mode.is(Mode.NightVision)) {
            mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 420, 0, false, false, false));
        }
    }

    @Override
    protected void onDisable() {
        if (mc.player != null && mode.is(Mode.NightVision)) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
    }
}
