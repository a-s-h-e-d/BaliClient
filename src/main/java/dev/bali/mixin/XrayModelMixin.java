package dev.bali.mixin;

import dev.bali.module.render.Xray;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Face culling for Xray. Hooked on Block.shouldDrawSide itself because both the vanilla model
 * renderer and Fabric's Indigo renderer (which replaces vanilla terrain meshing) call it.
 */
@Mixin(Block.class)
public abstract class XrayModelMixin {
    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true)
    private static void bali$drawAllSides(BlockState state, BlockState other, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (Xray.shows(state.getBlock())) cir.setReturnValue(true);
    }
}
