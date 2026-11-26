package dev.bali.mixin;

import dev.bali.module.render.Freecam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class FreecamMixins {
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void bali$freecam(World area, Entity focused, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        Freecam f = Freecam.active();
        if (f == null) return;
        setPos(f.pos(tickProgress));
        setRotation(f.yaw(tickProgress), f.pitch(tickProgress));
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "update", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 0),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 1)})
    private void bali$freeLook(Camera camera, float yaw, float pitch, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        if (dev.bali.util.FreeLook.isActive()) original.call(camera, dev.bali.util.FreeLook.yaw(), dev.bali.util.FreeLook.pitch());
        else original.call(camera, yaw, pitch);
    }

    @Inject(method = "isThirdPerson", at = @At("HEAD"), cancellable = true)
    private void bali$showBody(CallbackInfoReturnable<Boolean> cir) {
        if (Freecam.active() != null) cir.setReturnValue(true);
    }
}
