package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.bali.Bali;
import dev.bali.module.player.AutoEat;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** AutoEat Silent: the hotbar highlight and the held-item name keep showing the slot you had before eating. */
@Mixin(InGameHud.class)
public abstract class SilentEatHudMixin {
    @ModifyExpressionValue(method = "renderHotbar", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;getSelectedSlot()I"))
    private int bali$silentEatSlot(int real) {
        return Bali.MODULES.get(AutoEat.class).displaySlot(real);
    }

    @ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;getSelectedStack()Lnet/minecraft/item/ItemStack;"))
    private ItemStack bali$silentEatName(ItemStack real) {
        return Bali.MODULES.get(AutoEat.class).displayStack(real);
    }
}
