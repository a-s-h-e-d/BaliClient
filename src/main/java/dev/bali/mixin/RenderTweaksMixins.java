package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.bali.Bali;
import dev.bali.module.render.Fullbright;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightmapTextureManager.class)
public abstract class RenderTweaksMixins {
    /** Second Double.floatValue() in update() is the gamma option. */
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 1))
    private float bali$gamma(float original) {
        Fullbright fb = Bali.MODULES.get(Fullbright.class);
        return fb.gammaActive() ? fb.gamma.getF() : original;
    }
}
