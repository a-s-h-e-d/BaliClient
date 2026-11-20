package dev.bali.mixin;

import dev.bali.module.render.Xray;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No smooth lighting while Xray is on. Only Xray blocks are meshed then, and flat lighting goes
 * through WorldRenderer.getLightmapCoordinates, where XrayLightMixin makes them full bright.
 * Works for both vanilla and Indigo terrain rendering.
 */
@Mixin(MinecraftClient.class)
public abstract class XrayAoMixin {
    @Inject(method = "isAmbientOcclusionEnabled", at = @At("HEAD"), cancellable = true)
    private static void bali$noAo(CallbackInfoReturnable<Boolean> cir) {
        if (Xray.ACTIVE != null) cir.setReturnValue(false);
    }
}
