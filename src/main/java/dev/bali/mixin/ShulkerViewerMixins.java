package dev.bali.mixin;

import dev.bali.module.render.ShulkerViewer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ItemStack.class)
public abstract class ShulkerViewerMixins {
    @Inject(method = "getTooltipData", at = @At("HEAD"), cancellable = true)
    private void bali$shulkerPreview(CallbackInfoReturnable<Optional<TooltipData>> cir) {
        ShulkerViewer v = ShulkerViewer.active();
        if (v == null) return;
        Optional<TooltipData> d = v.preview((ItemStack) (Object) this);
        if (d.isPresent()) cir.setReturnValue(d);
    }
}
