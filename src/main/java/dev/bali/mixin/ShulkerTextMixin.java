package dev.bali.mixin;

import dev.bali.module.render.ShulkerViewer;
import net.minecraft.component.type.ContainerComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContainerComponent.class)
public abstract class ShulkerTextMixin {
    @Inject(method = "appendTooltip", at = @At("HEAD"), cancellable = true)
    private void bali$hideText(CallbackInfo ci) {
        ShulkerViewer v = ShulkerViewer.active();
        if (v != null && v.hidesText()) ci.cancel();
    }
}
