package dev.bali.mixin;

import dev.bali.Bali;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void bali$onChat(String content, CallbackInfo ci) {
        if (Bali.COMMANDS.handle(content)) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addToMessageHistory(content);
            ci.cancel();
        }
    }
}
