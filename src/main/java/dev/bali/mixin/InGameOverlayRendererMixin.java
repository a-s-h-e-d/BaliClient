package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.module.render.NoRender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public abstract class InGameOverlayRendererMixin {
    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private static void bali$fire(MatrixStack matrices, VertexConsumerProvider vcp, Sprite sprite, CallbackInfo ci) {
        NoRender nr = Bali.MODULES.get(NoRender.class);
        if (nr.isEnabled() && nr.fire.on()) ci.cancel();
    }

    @Inject(method = "renderInWallOverlay", at = @At("HEAD"), cancellable = true)
    private static void bali$wall(Sprite sprite, MatrixStack matrices, VertexConsumerProvider vcp, CallbackInfo ci) {
        NoRender nr = Bali.MODULES.get(NoRender.class);
        if (nr.isEnabled() && nr.wall.on()) ci.cancel();
    }

    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private static void bali$water(MinecraftClient client, MatrixStack matrices, VertexConsumerProvider vcp, CallbackInfo ci) {
        NoRender nr = Bali.MODULES.get(NoRender.class);
        if (nr.isEnabled() && nr.water.on()) ci.cancel();
    }
}
