package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bali.util.GrimBoost;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    /**
     * The rocket push is purely client-side. While a module picks the velocity itself
     * (inside what Grim accepts during a burn), the vanilla push must not be added on top.
     */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;setVelocity(Lnet/minecraft/util/math/Vec3d;)V"))
    private void bali$rocketPush(LivingEntity shooter, Vec3d velocity, Operation<Void> original) {
        if (shooter instanceof ClientPlayerEntity && GrimBoost.ownsRocket()) return;
        original.call(shooter, velocity);
    }
}
