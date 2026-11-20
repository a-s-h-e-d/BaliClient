package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.module.misc.AutoReconnect;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
    @Unique private ButtonWidget bali$button;
    @Unique private int bali$ticks;

    protected DisconnectedScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void bali$addButton(CallbackInfo ci) {
        if (AutoReconnect.lastServer == null) return;
        AutoReconnect ar = Bali.MODULES.get(AutoReconnect.class);
        bali$ticks = ar.delay.getI() * 20;
        bali$button = ButtonWidget.builder(Text.literal(label()), b -> AutoReconnect.reconnect())
            .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build();
        addDrawableChild(bali$button);
    }

    @Override
    public void tick() {
        super.tick();
        if (bali$button == null) return;
        AutoReconnect ar = Bali.MODULES.get(AutoReconnect.class);
        if (ar.isEnabled() && bali$ticks > 0) {
            bali$ticks--;
            if (bali$ticks == 0) AutoReconnect.reconnect();
        }
        bali$button.setMessage(Text.literal(label()));
    }

    @Unique
    private String label() {
        AutoReconnect ar = Bali.MODULES.get(AutoReconnect.class);
        if (ar.isEnabled() && bali$ticks > 0) return "Reconnect (" + String.format("%.1f", bali$ticks / 20.0) + "s)";
        return "Reconnect";
    }
}
