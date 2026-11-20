package dev.bali.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.bali.Bali;
import dev.bali.event.events.Events;
import dev.bali.module.movement.NoSlow;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void bali$preMovementPackets(CallbackInfo ci) {
        Bali.ROTATIONS.preSend();
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void bali$postMovementPackets(CallbackInfo ci) {
        Bali.ROTATIONS.postSend();
    }

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3d bali$onMove(Vec3d movement, MovementType type) {
        return Bali.EVENTS.post(new Events.Move(type, movement)).movement;
    }

    @ModifyExpressionValue(method = "applyMovementSpeedFactors", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"))
    private boolean bali$noSlowUse(boolean original) {
        return original && !Bali.MODULES.get(NoSlow.class).active();
    }

    @ModifyExpressionValue(method = "isBlockedFromSprinting", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z"))
    private boolean bali$noSlowSprint(boolean original) {
        NoSlow ns = Bali.MODULES.get(NoSlow.class);
        return original && !(ns.active() && ns.sprint.on());
    }
}
