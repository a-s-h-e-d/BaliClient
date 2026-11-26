package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

public final class InvUtil {
    private InvUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final int OFFHAND_SCREEN_SLOT = 45;

    public static int findHotbar(Predicate<ItemStack> p) {
        for (int i = 0; i < 9; i++) if (p.test(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    public static int findHotbar(Item item) { return findHotbar(s -> s.isOf(item)); }

    public static int findInventory(Predicate<ItemStack> p) {
        for (int i = 9; i < 36; i++) if (p.test(mc.player.getInventory().getStack(i))) return i;
        for (int i = 0; i < 9; i++) if (p.test(mc.player.getInventory().getStack(i))) return i;
        return -1;
    }

    public static int findInventory(Item item) { return findInventory(s -> s.isOf(item)); }

    public static int count(Item item) {
        int c = 0;
        for (int i = 0; i < 41; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(item)) c += s.getCount();
        }
        return c;
    }

    public static int toScreenSlot(int invIndex) {
        if (invIndex >= 0 && invIndex < 9) return 36 + invIndex;
        if (invIndex < 36) return invIndex;
        if (invIndex < 40) return 8 - (invIndex - 36);
        if (invIndex == 40) return OFFHAND_SCREEN_SLOT;
        return -1;
    }

    public static int selected() { return mc.player.getInventory().getSelectedSlot(); }

    public static void select(int slot) {
        if (slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
        mc.interactionManager.syncSelectedSlot();
    }

    public static boolean silentSwap(int slot, Runnable action) {
        if (slot < 0 || slot > 8) return false;
        int prev = selected();
        if (prev == slot) {
            action.run();
            return true;
        }
        select(slot);
        try {
            action.run();
        } finally {
            select(prev);
        }
        return true;
    }

    public static boolean invSwap(int invIndex, Runnable action) {
        if (invIndex < 0 || invIndex > 35) return false;
        int sel = selected();
        if (invIndex == sel) {
            action.run();
            return true;
        }
        if (!ClickGate.tryClick()) return false;
        int screenSlot = toScreenSlot(invIndex);
        swapClick(screenSlot, sel);
        try {
            action.run();
        } finally {
            swapClick(screenSlot, sel);
        }
        return true;
    }

    public static void swapClick(int screenSlot, int button) {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, button, SlotActionType.SWAP, mc.player);
    }

    public static void pickup(int screenSlot) {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    public static void quickMove(int screenSlot) {
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, screenSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    public static void move(int fromScreenSlot, int toScreenSlot) {
        pickup(fromScreenSlot);
        pickup(toScreenSlot);
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) pickup(fromScreenSlot);
    }

    public static boolean canClickInventory() {
        return mc.player.currentScreenHandler == mc.player.playerScreenHandler;
    }

    public static void closeInventoryPacket() {
        if (mc.currentScreen == null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(mc.player.playerScreenHandler.syncId));
        }
    }
}
