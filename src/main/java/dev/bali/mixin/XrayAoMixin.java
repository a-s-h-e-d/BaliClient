package dev.bali.mixin;

import dev.bali.module.render.Xray;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class XrayAoMixin {
    @Inject(method = "isAmbientOcclusionEnabled", at = @At("HEAD"), cancellable = true)
    private static void bali$noAo(CallbackInfoReturnable<Boolean> cir) {
        if (Xray.ACTIVE != null) cir.setReturnValue(false);
    }
}
