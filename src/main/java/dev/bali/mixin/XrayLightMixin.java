package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bali.module.render.Xray;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldRenderer.class)
public abstract class XrayLightMixin {
    @WrapOperation(method = "getLightmapCoordinates(Lnet/minecraft/client/render/WorldRenderer$BrightnessGetter;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;)I",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;hasEmissiveLighting(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)Z"))
    private static boolean bali$fullBright(BlockState state, BlockView world, BlockPos pos, Operation<Boolean> original) {
        if (Xray.shows(state.getBlock())) return true;
        return original.call(state, world, pos);
    }
}
