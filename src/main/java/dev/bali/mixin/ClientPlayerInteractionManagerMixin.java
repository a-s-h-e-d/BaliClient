package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.event.events.Events;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void bali$onAttack(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (Bali.EVENTS.post(new Events.Attack(target)).isCancelled()) ci.cancel();
    }
}
