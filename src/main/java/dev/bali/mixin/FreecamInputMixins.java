package dev.bali.mixin;

import dev.bali.module.render.Freecam;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While freecam is on the player receives no movement input (keys move the camera instead). */
@Mixin(KeyboardInput.class)
public abstract class FreecamInputMixins {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bali$freezePlayer(CallbackInfo ci) {
        if (Freecam.active() == null) return;
        KeyboardInput self = (KeyboardInput) (Object) this;
        self.playerInput = PlayerInput.DEFAULT;
        self.movementVector = Vec2f.ZERO;
    }
}
