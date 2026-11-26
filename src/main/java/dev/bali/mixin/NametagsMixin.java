package dev.bali.mixin;

import dev.bali.module.render.Nametags;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class NametagsMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void bali$hideVanilla(net.minecraft.entity.LivingEntity entity, double distSq, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof PlayerEntity && Nametags.active()) cir.setReturnValue(false);
    }
}
