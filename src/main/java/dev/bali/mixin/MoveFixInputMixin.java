package dev.bali.mixin;

import dev.bali.Bali;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Move-fix, input half. While a silent rotation is pending, the keys themselves are remapped to
 * whichever of the 8 directions relative to the server yaw best matches what was pressed relative
 * to the camera.
 *
 * It has to be the real input, not just the velocity: 1.21.2+ clients send their key state to the
 * server (PlayerInputC2SPacket) and Grim simulates movement with exactly those keys and the yaw
 * we sent. It also makes vanilla's own sprint rule (forward key required) apply to the remapped
 * keys, so we never sprint sideways as far as the server can tell.
 */
@Mixin(KeyboardInput.class)
public abstract class MoveFixInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bali$remapKeys(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || Bali.ROTATIONS == null) return;
        bali$remap(mc);
        // ElytraRecast: no keys while its inventory clicks go out (Grim MultiActionsC)
        KeyboardInput self = (KeyboardInput) (Object) this;
        PlayerInput filtered = Bali.MODULES.get(dev.bali.module.movement.ElytraRecast.class).filterInput(self.playerInput);
        if (filtered != null) {
            self.playerInput = filtered;
            self.movementVector = Vec2f.ZERO;
        }
        // ClickGate (ElytraMace swaps etc.): same rule for any blind click; also tracks what we last sent
        PlayerInput gated = dev.bali.util.ClickGate.filter(self.playerInput);
        if (gated != self.playerInput) {
            self.playerInput = gated;
            self.movementVector = Vec2f.ZERO;
        }
    }

    private void bali$remap(MinecraftClient mc) {
        float serverYaw = Bali.ROTATIONS.movementYaw();
        if (Float.isNaN(serverYaw)) return;
        KeyboardInput self = (KeyboardInput) (Object) this;
        PlayerInput in = self.playerInput;
        int f = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
        int s = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
        if (f == 0 && s == 0) return;

        // world-space direction the player wants (degrees), using the real camera yaw
        double wanted = mc.player.getYaw() + Math.toDegrees(Math.atan2(-s, f));
        double bestDiff = Double.MAX_VALUE;
        int bestF = 0, bestS = 0;
        for (int cf = -1; cf <= 1; cf++) {
            for (int cs = -1; cs <= 1; cs++) {
                if (cf == 0 && cs == 0) continue;
                double diff = Math.abs(MathHelper.wrapDegrees(serverYaw + Math.toDegrees(Math.atan2(-cs, cf)) - wanted));
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestF = cf;
                    bestS = cs;
                }
            }
        }
        self.playerInput = new PlayerInput(bestF > 0, bestF < 0, bestS > 0, bestS < 0, in.jump(), in.sneak(), in.sprint());
        self.movementVector = new Vec2f(bestS, bestF).normalize();
    }
}
