package dev.bali.mixin;

import dev.bali.module.render.NoParticles;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.BlockParticleEffect;
import net.minecraft.util.collection.WeightedPool;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explosion smoke/poof burst spawned from the explosion packet. */
@Mixin(ClientWorld.class)
public abstract class WeatherAndExplosionMixins {
    @Inject(method = "addBlockParticleEffects", at = @At("HEAD"), cancellable = true)
    private void bali$explosionSmoke(Vec3d center, float radius, int blockCount, WeightedPool<BlockParticleEffect> particles, CallbackInfo ci) {
        if (NoParticles.hidesExplosionSmoke()) ci.cancel();
    }
}
