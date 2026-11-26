package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

public final class MaceUtil {
    private MaceUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final int CHEST_SCREEN_SLOT = 6;

    public static boolean isMace(ItemStack s) { return s.isOf(Items.MACE); }

    public static int findMace() {
        int best = -1, bestLvl = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!isMace(s)) continue;
            int lvl = DamageUtil.enchantLevel(s, Enchantments.DENSITY);
            if (lvl > bestLvl) {
                bestLvl = lvl;
                best = i;
            }
        }
        return best;
    }

    public static boolean isElytra(ItemStack s) {
        return s.contains(DataComponentTypes.GLIDER) && s.getDamage() < s.getMaxDamage() - 1;
    }

    public static boolean wearingElytra() {
        return isElytra(mc.player.getEquippedStack(EquipmentSlot.CHEST));
    }

    public static int findElytra() {
        for (int i = 0; i < 36; i++) if (isElytra(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    public static int findChestplate() {
        int best = -1, bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isIn(ItemTags.CHEST_ARMOR)) continue;
            int score = s.isOf(Items.NETHERITE_CHESTPLATE) ? 3 : s.isOf(Items.DIAMOND_CHESTPLATE) ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    public static boolean equipChest(int invIndex) {
        if (invIndex < 0 || invIndex > 35) return false;
        if (!InvUtil.canClickInventory() || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return false;
        if (invIndex < 9) {
            InvUtil.swapClick(CHEST_SCREEN_SLOT, invIndex);
        } else {
            int from = InvUtil.toScreenSlot(invIndex);
            InvUtil.pickup(from);
            InvUtil.pickup(CHEST_SCREEN_SLOT);
            InvUtil.pickup(from);
        }
        return true;
    }

    public static double smashBonus(double f, int density) {
        if (f <= 1.5) return 0;
        double g = f <= 3 ? 4 * f : f <= 8 ? 12 + 2 * (f - 3) : 22 + f - 8;
        return g + 0.5 * density * f;
    }
}
