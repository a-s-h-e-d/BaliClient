package dev.bali.module.player;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.combat.AutoCrystal;
import dev.bali.module.combat.KillAura;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.DamageUtil;
import dev.bali.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.util.Hand;

/**
 * Eats when hungry, or eats a golden apple when health is low.
 *
 * Silent is visual only: the real slot (client and server) is the food, but the hotbar highlight,
 * the first-person hand and the item name keep showing what you held before (see SilentEatMixins).
 *
 * Using another item cancels the eat. On Grim and vanilla a slot change ends item use on the
 * server, so when a module silent-swaps to use something mid-eat, we stop the client's use too and
 * start over; otherwise the client would finish eating and the server would not. If you change slot
 * yourself, AutoEat backs off instead of snapping back to the food.
 */
public final class AutoEat extends Module {
    private final NumberSetting hunger = number("Hunger", 14, 1, 19, 1, "Eat at or below this food level");
    private final BoolSetting gapple = bool("Gapple Health", true, "Eat a golden apple when health is low");
    private final NumberSetting gappleHealth = number("Gapple HP", 10, 1, 36, 0.5, "Health threshold for gapples").visibleWhen(() -> gapple != null && gapple.on());
    private final BoolSetting pauseCombat = bool("Pause Combat", false, "Don't eat (hunger) while KillAura/AutoCrystal has a target");
    private final BoolSetting silent = bool("Silent", true, "Keep showing your previous item while eating (visual only)");

    /** Ticks AutoEat waits after you pick another slot yourself mid-eat. */
    private static final int YIELD_TICKS = 20;

    private boolean eating;
    private int prevSlot = -1;
    private int foodSlot = -1;
    private int yield;
    /** Set from the network thread when a slot packet other than the food goes out mid-eat. */
    private volatile boolean interrupted;
    private int restarts;
    /** Count of the gapple stack when this gapple was started; -1 when not committed to a gapple. */
    private int commitCount = -1;

    public AutoEat() {
        super("AutoEat", "Eats automatically", Category.PLAYER);
    }

    @Override
    protected void onDisable() { stop(true); }

    /** Hotbar slot to draw as selected; the real one unless a silent eat is running. Read by SilentEatMixins. */
    public int displaySlot(int real) {
        return isEnabled() && eating && silent.on() && prevSlot != -1 ? prevSlot : real;
    }

    /** Main-hand stack to draw; read by SilentEatMixins. */
    public ItemStack displayStack(ItemStack real) {
        int slot = displaySlot(-1);
        return slot == -1 || mc.player == null ? real : mc.player.getInventory().getStack(slot);
    }

    public boolean isEating() { return eating; }

    /** A silent eat is running: hide its food particles too (client ones via LivingEntityMixin, server ones below). */
    public boolean hidingEat() { return displaySlot(-1) != -1; }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!hidingEat() || !(e.packet instanceof ParticleS2CPacket p) || !(p.getParameters() instanceof ItemStackParticleEffect)) return;
        var player = mc.player;
        if (player != null && player.getEyePos().squaredDistanceTo(p.getX(), p.getY(), p.getZ()) < 4) e.cancel();
    }

    /** Times an eat was restarted because something else used an item; for tests. */
    public int restarts() { return restarts; }

    @EventHandler
    private void onSend(Events.PacketSend e) {
        if (eating && e.packet instanceof UpdateSelectedSlotC2SPacket p && p.getSelectedSlot() != foodSlot) interrupted = true;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (mc.player.isCreative()) return;
        if (yield > 0) yield--;

        if (eating) {
            // you picked another slot yourself: give way instead of fighting you for it
            if (InvUtil.selected() != foodSlot) {
                stop(false);
                yield = YIELD_TICKS;
                return;
            }
            // a module swapped away and back to use something: the server dropped the eat, so drop it here
            // too. The key stays held, so the client starts a fresh use this same tick (no idle gap).
            if (interrupted) {
                interrupted = false;
                restarts++;
                if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player);
            }
        }

        if (yield > 0 || usingOtherItem()) {
            stop(true);
            return;
        }

        // a gapple, once started, is finished: health crossing the threshold (totem absorption,
        // regen) must not cancel it. It ends when one is eaten (count drops) or you switch slots.
        int slot = committed() ? foodSlot : pickFood();
        if (slot == -1) {
            stop(true);
            return;
        }
        if (!eating) {
            prevSlot = InvUtil.selected();
            interrupted = false;
        }
        if (!committed() && isGapple(mc.player.getInventory().getStack(slot))) commitCount = mc.player.getInventory().getStack(slot).getCount();
        eating = true;
        foodSlot = slot;
        if (InvUtil.selected() != slot) InvUtil.select(slot);
        interrupted = false; // our own select above
        mc.options.useKey.setPressed(true);
    }

    /** Mid-gapple: the same gapple stack is still in the food slot with the count it had when we started. */
    private boolean committed() {
        if (!eating || commitCount < 0 || foodSlot < 0) return false;
        ItemStack s = mc.player.getInventory().getStack(foodSlot);
        if (isGapple(s) && s.getCount() == commitCount) return true;
        commitCount = -1; // eaten (or gone)
        return false;
    }

    private static boolean isGapple(ItemStack s) {
        return s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE);
    }

    /** Using something that isn't our food (offhand item, or a use we didn't start). */
    private boolean usingOtherItem() {
        if (!mc.player.isUsingItem()) return false;
        if (!eating) return true;
        return mc.player.getActiveHand() != Hand.MAIN_HAND;
    }

    private int pickFood() {
        boolean lowHp = gapple.on() && DamageUtil.health(mc.player) <= gappleHealth.getF();
        if (lowHp) {
            int s = InvUtil.findHotbar(Items.ENCHANTED_GOLDEN_APPLE);
            if (s == -1) s = InvUtil.findHotbar(Items.GOLDEN_APPLE);
            if (s != -1) return s;
        }
        if (mc.player.getHungerManager().getFoodLevel() > hunger.getI()) return -1;
        if (pauseCombat.on() && (Bali.MODULES.get(KillAura.class).target() != null || Bali.MODULES.get(AutoCrystal.class).target() != null)) return -1;
        int best = -1;
        float bestScore = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            FoodComponent food = s.get(DataComponentTypes.FOOD);
            if (food == null || bad(s)) continue;
            float score = food.nutrition() + food.saturation();
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean bad(ItemStack s) {
        return s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.POISONOUS_POTATO)
            || s.isOf(Items.PUFFERFISH) || s.isOf(Items.CHORUS_FRUIT) || s.isOf(Items.SUSPICIOUS_STEW)
            || s.isOf(Items.ENCHANTED_GOLDEN_APPLE) || s.isOf(Items.GOLDEN_APPLE);
    }

    /** @param restore switch back to the slot held before eating (false when you picked a slot yourself) */
    private void stop(boolean restore) {
        if (!eating) return;
        eating = false;
        interrupted = false;
        foodSlot = -1;
        commitCount = -1;
        if (mc.options != null) mc.options.useKey.setPressed(false);
        if (restore && mc.player != null && prevSlot != -1) InvUtil.select(prevSlot);
        prevSlot = -1;
    }
}
