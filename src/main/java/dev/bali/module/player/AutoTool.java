package dev.bali.module.player;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.util.InvUtil;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

public final class AutoTool extends Module {
    private final BoolSetting switchBack = bool("Switch Back", true, "Return to the previous slot after mining");
    private final BoolSetting saveTools = bool("Save Tools", true, "Skip tools about to break");

    private int prevSlot = -1;

    public AutoTool() {
        super("AutoTool", "Picks the best tool", Category.PLAYER);
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        boolean mining = mc.options.attackKey.isPressed() && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK;
        if (!mining) {
            if (switchBack.on() && prevSlot != -1) {
                InvUtil.select(prevSlot);
                prevSlot = -1;
            }
            return;
        }
        BlockState state = mc.world.getBlockState(((BlockHitResult) mc.crosshairTarget).getBlockPos());
        if (state.isAir()) return;
        int best = -1;
        float bestSpeed = mc.player.getMainHandStack().getMiningSpeedMultiplier(state);
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (saveTools.on() && s.isDamageable() && s.getMaxDamage() - s.getDamage() < 10) continue;
            float speed = s.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed + 0.01f) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best != -1 && best != InvUtil.selected()) {
            if (prevSlot == -1) prevSlot = InvUtil.selected();
            InvUtil.select(best);
        }
    }
}
