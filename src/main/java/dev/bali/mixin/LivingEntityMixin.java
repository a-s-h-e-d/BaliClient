package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.bali.util.GrimBoost;
import net.minecraft.util.math.Vec3d;
import dev.bali.Bali;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /** AutoEat Silent: no food crumbs in front of your face while it eats behind your held item. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "spawnItemParticles", at = @At("HEAD"), cancellable = true)
    private void bali$silentEatParticles(net.minecraft.item.ItemStack stack, int count, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity && Bali.MODULES.get(dev.bali.module.player.AutoEat.class).hidingEat()) ci.cancel();
    }
    /**
     * Elytra move-fix: Grim simulates gliding with the rotation we send, so while a silent rotation
     * is pending the client's gliding physics must use that same rotation (camera stays free).
     */
    @WrapOperation(method = "calcGlidingVelocity", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d bali$glideLook(LivingEntity self, Operation<Vec3d> original) {
        if (self instanceof ClientPlayerEntity && Bali.ROTATIONS != null) {
            float pitch = Bali.ROTATIONS.movementPitch();
            float yaw = Bali.ROTATIONS.movementYaw();
            if (!Float.isNaN(pitch) && !Float.isNaN(yaw)) return Vec3d.fromPolar(pitch, yaw);
        }
        return original.call(self);
    }

    @WrapOperation(method = "calcGlidingVelocity", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getPitch()F"))
    private float bali$glidePitch(LivingEntity self, Operation<Float> original) {
        if (self instanceof ClientPlayerEntity && Bali.ROTATIONS != null) {
            float pitch = Bali.ROTATIONS.movementPitch();
            if (!Float.isNaN(pitch)) return pitch;
        }
        return original.call(self);
    }

    /** A module flying rockets (see GrimBoost) chooses the glide velocity while a rocket is attached (within what Grim accepts). */
    @WrapMethod(method = "calcGlidingVelocity")
    private Vec3d bali$boostGlide(Vec3d oldVelocity, Operation<Vec3d> original) {
        Vec3d vanilla = original.call(oldVelocity);
        if (!((Object) this instanceof ClientPlayerEntity)) return vanilla;
        return GrimBoost.velocity(oldVelocity, vanilla);
    }

    /** Sprint-jump boost direction must use the server yaw too (move-fix). */
    @ModifyExpressionValue(method = "jump", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F"))
    private float bali$jumpYaw(float original) {
        if (!((Object) this instanceof ClientPlayerEntity) || Bali.ROTATIONS == null) return original;
        float y = Bali.ROTATIONS.movementYaw();
        return Float.isNaN(y) ? original : y;
    }
}
