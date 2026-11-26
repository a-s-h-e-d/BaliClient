package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.module.render.NoRender;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void bali$hurtCam(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        NoRender nr = Bali.MODULES.get(NoRender.class);
        if (nr.isEnabled() && nr.hurtCam.on()) ci.cancel();
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void bali$freecamHand(float tickProgress, boolean sleeping, org.joml.Matrix4f positionMatrix, CallbackInfo ci) {
        if (dev.bali.module.render.Freecam.active() != null) ci.cancel();
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "renderWorld",
        at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", ordinal = 0))
    private org.joml.Matrix4f bali$captureBob(org.joml.Matrix4f projection, org.joml.Matrix4fc bob,
                                              com.llamalad7.mixinextras.injector.wrapoperation.Operation<org.joml.Matrix4f> original) {
        org.joml.Matrix4f result = original.call(projection, bob);
        dev.bali.util.Render3D.captureProjection(bob, result);
        return result;
    }

    @Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
    private void bali$totemAnimation(ItemStack stack, CallbackInfo ci) {
        NoRender nr = Bali.MODULES.get(NoRender.class);
        if (nr.isEnabled() && nr.totem.on() && stack.isOf(Items.TOTEM_OF_UNDYING)) ci.cancel();
    }
}
