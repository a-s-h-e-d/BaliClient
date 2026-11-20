package dev.bali.mixin;

import dev.bali.Bali;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Move-fix, velocity half: when a silent rotation is active, this tick's movement is computed with
 * the server yaw (the keys were already remapped to it in MoveFixInputMixin). Grim simulates with
 * the yaw and keys we send, so this keeps us legit.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Shadow public abstract Vec3d getVelocity();
    @Shadow public abstract void setVelocity(Vec3d velocity);

    @Inject(method = "updateVelocity", at = @At("HEAD"), cancellable = true)
    private void bali$moveFix(float speed, Vec3d input, CallbackInfo ci) {
        if (!((Object) this instanceof ClientPlayerEntity) || Bali.ROTATIONS == null) return;
        float serverYaw = Bali.ROTATIONS.movementYaw();
        if (Float.isNaN(serverYaw)) return;

        Vec3d v = Entity.movementInputToVelocity(input, speed, serverYaw);
        setVelocity(getVelocity().add(v));
        ci.cancel();
    }
}
