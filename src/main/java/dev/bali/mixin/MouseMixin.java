package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.event.events.Events;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void bali$onMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
        if (Bali.EVENTS.post(new Events.Key(input.button(), action, true)).isCancelled()) ci.cancel();
    }
}
