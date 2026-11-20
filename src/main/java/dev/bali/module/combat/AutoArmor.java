package dev.bali.module.combat;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.DamageUtil;
import dev.bali.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public final class AutoArmor extends Module {
    private final BoolSetting blastLegs = bool("Blast Legs", true, "Prefer blast protection leggings (crystal PvP)");
    private final BoolSetting preferElytra = bool("Prefer Elytra", false, "Keep an elytra in the chest slot");
    private final BoolSetting ignoreBinding = bool("Ignore Binding", true, "Never equip curse of binding");
    private final NumberSetting delay = number("Delay", 1, 0, 10, 1, "Ticks between clicks");
    private final BoolSetting pauseMoving = bool("Pause Moving", false, "Only swap while standing still (stricter anticheats)");

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private int timer;

    public AutoArmor() {
        super("AutoArmor", "Equips your best armor", Category.COMBAT);
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (timer > 0) { timer--; return; }
        if (!InvUtil.canClickInventory() || mc.player.isCreative()) return;
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        if (pauseMoving.on() && mc.player.getVelocity().horizontalLengthSquared() > 1e-4) return;

        for (EquipmentSlot slot : SLOTS) {
            // ElytraMace swaps chestplate and elytra itself
            if (slot == EquipmentSlot.CHEST && dev.bali.Bali.MODULES.get(ElytraMace.class).ownsChestSlot()) continue;
            if (slot == EquipmentSlot.CHEST && dev.bali.Bali.MODULES.get(dev.bali.module.movement.ElytraRecast.class).busy()) continue;
            ItemStack current = mc.player.getEquippedStack(slot);
            if (DamageUtil.enchantLevel(current, Enchantments.BINDING_CURSE) > 0) continue;
            // never pull the elytra off mid-flight
            if (slot == EquipmentSlot.CHEST && mc.player.isGliding() && current.contains(DataComponentTypes.GLIDER)) continue;
            double currentScore = score(current, slot);
            int bestIdx = -1;
            double bestScore = currentScore;
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                if (s.isEmpty() || slotOf(s) != slot) continue;
                if (ignoreBinding.on() && DamageUtil.enchantLevel(s, Enchantments.BINDING_CURSE) > 0) continue;
                double sc = score(s, slot);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestIdx = i;
                }
            }
            if (bestIdx == -1) continue;

            if (!dev.bali.util.ClickGate.tryClick()) return; // keys released; equip next tick
            int from = InvUtil.toScreenSlot(bestIdx);
            int armorScreenSlot = 8 - slot.getEntitySlotId();
            if (current.isEmpty()) {
                InvUtil.quickMove(from);
            } else {
                InvUtil.pickup(from);
                InvUtil.pickup(armorScreenSlot);
                InvUtil.pickup(from);
            }
            timer = delay.getI();
            return; // one piece per tick
        }
    }

    private static EquipmentSlot slotOf(ItemStack s) {
        EquippableComponent eq = s.get(DataComponentTypes.EQUIPPABLE);
        return eq == null ? null : eq.slot();
    }

    private double score(ItemStack s, EquipmentSlot slot) {
        if (s.isEmpty()) return 0;
        if (s.isOf(Items.ELYTRA)) return preferElytra.on() ? 1000 : 1;
        AttributeModifiersComponent mods = s.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        double armor = mods.applyOperations(EntityAttributes.ARMOR, 0, slot);
        double tough = mods.applyOperations(EntityAttributes.ARMOR_TOUGHNESS, 0, slot);
        int prot = DamageUtil.enchantLevel(s, Enchantments.PROTECTION);
        int blast = DamageUtil.enchantLevel(s, Enchantments.BLAST_PROTECTION);
        int unb = DamageUtil.enchantLevel(s, Enchantments.UNBREAKING);
        int mending = DamageUtil.enchantLevel(s, Enchantments.MENDING);
        double score = armor * 10 + tough * 6 + prot * 4 + blast * 2 + unb * 0.5 + mending * 0.5;
        if (blastLegs.on() && slot == EquipmentSlot.LEGS) score += blast * 6;
        if (s.isDamageable()) score -= (double) s.getDamage() / s.getMaxDamage() * 2; // slight preference for fresh pieces
        return score;
    }
}
