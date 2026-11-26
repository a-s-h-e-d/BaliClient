package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bali.module.render.Xray;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.chunk.SectionBuilder;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SectionBuilder.class)
public abstract class XrayMixins {
    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;getRenderType()Lnet/minecraft/block/BlockRenderType;"))
    private BlockRenderType bali$hideBlocks(BlockState state, Operation<BlockRenderType> original) {
        if (Xray.hides(state.getBlock())) return BlockRenderType.INVISIBLE;
        return original.call(state);
    }

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockState;isOpaqueFullCube()Z"))
    private boolean bali$noOcclusion(BlockState state, Operation<Boolean> original) {
        if (Xray.ACTIVE != null) return false;
        return original.call(state);
    }

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderFluid(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/block/BlockState;Lnet/minecraft/fluid/FluidState;)V"))
    private void bali$hideFluids(BlockRenderManager manager, BlockPos pos, BlockRenderView world, VertexConsumer vc, BlockState state, FluidState fluid, Operation<Void> original) {
        if (Xray.ACTIVE != null && !Xray.SHOW_FLUIDS) return;
        original.call(manager, pos, world, vc, state, fluid);
    }

    @WrapOperation(method = "build", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/chunk/SectionBuilder;addBlockEntity(Lnet/minecraft/client/render/chunk/SectionBuilder$RenderData;Lnet/minecraft/block/entity/BlockEntity;)V"))
    private void bali$hideBlockEntities(SectionBuilder builder, SectionBuilder.RenderData data, BlockEntity be, Operation<Void> original) {
        if (Xray.hides(be.getCachedState().getBlock())) return;
        original.call(builder, data, be);
    }
}
