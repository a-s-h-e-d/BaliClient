package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.bali.Bali;
import dev.bali.module.player.AutoEat;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HeldItemRenderer.class)
public abstract class SilentEatHandMixin {
    @ModifyExpressionValue(method = "updateHeldItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getMainHandStack()Lnet/minecraft/item/ItemStack;"))
    private ItemStack bali$silentEatHand(ItemStack real) {
        return Bali.MODULES.get(AutoEat.class).displayStack(real);
    }
}
