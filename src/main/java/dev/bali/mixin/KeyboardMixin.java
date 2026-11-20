package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.event.events.Events;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void bali$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
        if (input.key() == -1) return;
        if (Bali.EVENTS.post(new Events.Key(input.key(), action, false)).isCancelled()) ci.cancel();
    }
}
