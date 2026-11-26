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

@Mixin(KeyboardInput.class)
public abstract class MoveFixInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void bali$remapKeys(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || Bali.ROTATIONS == null) return;
        bali$remap(mc);
        KeyboardInput input = (KeyboardInput) (Object) this;
        input.playerInput = Bali.MODULES.get(dev.bali.module.movement.Scaffold.class).filterInput(input.playerInput);
        KeyboardInput self = (KeyboardInput) (Object) this;
        PlayerInput filtered = Bali.MODULES.get(dev.bali.module.movement.ElytraRecast.class).filterInput(self.playerInput);
        if (filtered != null) {
            self.playerInput = filtered;
            self.movementVector = Vec2f.ZERO;
        }
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
