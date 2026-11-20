package dev.bali.mixin;

import dev.bali.module.render.NoParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void bali$filterEffect(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir) {
        if (NoParticles.hides(effect)) cir.setReturnValue(null);
    }

    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void bali$filterParticle(Particle particle, CallbackInfo ci) {
        if (NoParticles.hides(particle)) ci.cancel();
    }

    @Inject(method = "addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;)V", at = @At("HEAD"), cancellable = true)
    private void bali$filterEmitter(Entity entity, ParticleEffect effect, CallbackInfo ci) {
        if (NoParticles.hides(effect)) ci.cancel();
    }

    @Inject(method = "addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;I)V", at = @At("HEAD"), cancellable = true)
    private void bali$filterEmitterAge(Entity entity, ParticleEffect effect, int maxAge, CallbackInfo ci) {
        if (NoParticles.hides(effect)) ci.cancel();
    }
}
