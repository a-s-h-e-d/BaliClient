package dev.bali.module.player;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.util.ChatUtil;
import dev.bali.util.InvUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

public final class MiddleClick extends Module {
    public enum Action { Pearl, Rocket, Exp }

    private final EnumSetting<Action> action = mode("Action", Action.Pearl, "Item to use on middle click");
    private final BoolSetting friends = bool("Friends", true, "Middle click a player to add/remove friend");
    private final BoolSetting inventory = bool("Inventory", true, "Also use items from the main inventory");

    private Item pendingItem;
    private int pendingTicks;

    public MiddleClick() {
        super("MiddleClick", "Middle click actions (MCP / MCF)", Category.PLAYER);
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (pendingItem == null) return;
        if (--pendingTicks < 0 || mc.currentScreen != null || !InvUtil.canClickInventory()) {
            pendingItem = null;
            return;
        }
        int inv = InvUtil.findInventory(pendingItem);
        if (inv == -1) {
            pendingItem = null;
            return;
        }
        Runnable use = () -> {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        };
        if (InvUtil.invSwap(inv, use)) pendingItem = null;
    }

    @EventHandler
    private void onKey(Events.Key e) {
        if (!e.mouse || e.key != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || e.action != 1 || mc.currentScreen != null || nullCheck()) return;

        if (friends.on() && mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof PlayerEntity p) {
            String name = p.getGameProfile().name();
            if (Bali.FRIENDS.isFriend(name)) {
                Bali.FRIENDS.remove(name);
                ChatUtil.info("Removed friend " + name);
            } else {
                Bali.FRIENDS.add(name);
                ChatUtil.info("Added friend " + name);
            }
            e.cancel();
            return;
        }
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK && action.is(Action.Exp)) return;

        Item item = switch (action.get()) {
            case Pearl -> Items.ENDER_PEARL;
            case Rocket -> Items.FIREWORK_ROCKET;
            case Exp -> Items.EXPERIENCE_BOTTLE;
        };
        if (mc.player.getItemCooldownManager().isCoolingDown(new net.minecraft.item.ItemStack(item))) return;
        Runnable use = () -> {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
        };
        int hotbar = InvUtil.findHotbar(item);
        if (hotbar != -1) {
            InvUtil.silentSwap(hotbar, use);
            e.cancel();
        } else if (inventory.on() && InvUtil.canClickInventory()) {
            int inv = InvUtil.findInventory(item);
            if (inv != -1) {
                if (!InvUtil.invSwap(inv, use)) {
                    pendingItem = item;
                    pendingTicks = 3;
                }
                e.cancel();
            }
        }
    }
}
