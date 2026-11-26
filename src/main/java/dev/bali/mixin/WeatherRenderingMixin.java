package dev.bali.mixin;

import dev.bali.module.render.NoParticles;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.render.state.WeatherRenderState;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherRendering.class)
public abstract class WeatherRenderingMixin {
    @Inject(method = "renderPrecipitation", at = @At("HEAD"), cancellable = true)
    private void bali$noPrecipitation(VertexConsumerProvider vcp, Vec3d pos, WeatherRenderState state, CallbackInfo ci) {
        if (NoParticles.hidesWeather()) ci.cancel();
    }
}
