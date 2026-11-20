package dev.bali.mixin;

import dev.bali.module.render.Freecam;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse look turns the freecam, not the player (so the server never sees you turn). */
@Mixin(Entity.class)
public abstract class FreecamLookMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void bali$look(double dx, double dy, CallbackInfo ci) {
        if (!((Object) this instanceof ClientPlayerEntity)) return;
        Freecam f = Freecam.active();
        if (f != null) {
            f.look(dx, dy);
            ci.cancel();
        } else if (dev.bali.util.FreeLook.isActive()) {
            dev.bali.util.FreeLook.look(dx, dy);
            ci.cancel();
        }
    }

    /** YawLock: the mouse only moves your pitch (freecam and free look keep full control). */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double bali$yawLock(double dx) {
        if (!((Object) this instanceof ClientPlayerEntity) || Freecam.active() != null || dev.bali.util.FreeLook.isActive()) return dx;
        return dev.bali.module.movement.YawLock.blocksMouseYaw() ? 0 : dx;
    }
}
