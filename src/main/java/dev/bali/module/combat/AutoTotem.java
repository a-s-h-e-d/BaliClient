package dev.bali.module.combat;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.ClickGate;
import dev.bali.util.DamageUtil;
import dev.bali.util.InvUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

public final class AutoTotem extends Module {
    public enum Mode { Totem, Smart }
    public enum Fallback { Crystal, Gapple }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Totem, "Totem always, or Smart offhand");
    private final EnumSetting<Fallback> fallback = mode("Fallback", Fallback.Crystal, "Item to hold when safe (Smart)");
    private final NumberSetting health = number("Health", 16, 1, 36, 0.5, "Totem below this health+absorption (Smart)");
    private final BoolSetting crystalCheck = bool("Crystal Check", true, "Totem if a nearby crystal could kill you (Smart, and KillAura Offhand Gap)");
    private final BoolSetting elytraCheck = bool("Elytra Check", true, "Totem while flying (Smart, and KillAura Offhand Gap)");
    private final BoolSetting fallCheck = bool("Fall Check", true, "Totem if the fall could kill you (Smart, and KillAura Offhand Gap)");
    private final BoolSetting gappleSword = bool("Sword Gap", true, "Hold a gapple when holding a sword and right-clicking (Smart)");
    private final NumberSetting delay = number("Delay", 0, 0, 10, 1, "Ticks between swaps");
    private final BoolSetting closePacket = bool("Close Packet", false, "Send an inventory close after swapping");
    private final BoolSetting hotbarSwap = bool("Hotbar Swap", true, "After a pop, take a hotbar totem with the offhand key: instant, even while moving");

    private int timer;

    public AutoTotem() {
        super("AutoTotem", "Keeps a totem in the offhand", Category.COMBAT);
    }

    @Override
    public String getInfo() { return String.valueOf(InvUtil.count(Items.TOTEM_OF_UNDYING)); }

    @EventHandler(priority = 100)
    private void onTick(Events.TickPre e) {
        if (timer > 0) { timer--; return; }
        doSwap();
    }

    @EventHandler(priority = 100)
    private void onPop(Events.TotemPop e) {
        if (e.player == mc.player) {
            timer = 0;
            doSwap();
        }
    }

    private void doSwap() {
        if (!InvUtil.canClickInventory() || mc.player.isCreative()) return;
        Item wanted = wantedItem();
        if (mc.player.getOffHandStack().isOf(wanted)) return;

        int slot = InvUtil.findInventory(wanted);
        if (slot == -1 && wanted != Items.TOTEM_OF_UNDYING) {
            wanted = Items.TOTEM_OF_UNDYING;
            if (mc.player.getOffHandStack().isOf(wanted)) return;
            slot = InvUtil.findInventory(wanted);
        }
        if (slot == -1) return;

        if (slot < 9 && hotbarSwap.on() && mc.player.getOffHandStack().isEmpty() && offhandKeySwap(slot)) {
            timer = delay.getI();
            return;
        }
        if (!ClickGate.tryClick()) return;
        InvUtil.swapClick(InvUtil.toScreenSlot(slot), 40);
        if (closePacket.on()) InvUtil.closeInventoryPacket();
        timer = delay.getI();
    }

    private boolean offhandKeySwap(int slot) {
        if (mc.player.isUsingItem() || mc.interactionManager.isBreakingBlock() || mc.getNetworkHandler() == null) return false;
        InvUtil.silentSwap(slot, () -> mc.getNetworkHandler().sendPacket(
            new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN)));
        ItemStack hot = mc.player.getInventory().getStack(slot);
        mc.player.getInventory().setStack(slot, mc.player.getOffHandStack());
        mc.player.setStackInHand(Hand.OFF_HAND, hot);
        return true;
    }

    private Item wantedItem() {
        Item gap = dev.bali.Bali.MODULES.get(KillAura.class).offhandGapple();
        if (gap != null && !danger()) return gap;
        if (mode.is(Mode.Totem)) return Items.TOTEM_OF_UNDYING;
        if (DamageUtil.health(mc.player) <= health.getF()) return Items.TOTEM_OF_UNDYING;
        if (danger()) return Items.TOTEM_OF_UNDYING;

        if (gappleSword.on() && mc.options.useKey.isPressed()
            && mc.player.getMainHandStack().isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) {
            return Items.ENCHANTED_GOLDEN_APPLE;
        }
        return fallback.is(Fallback.Crystal) ? Items.END_CRYSTAL : Items.ENCHANTED_GOLDEN_APPLE;
    }

    private boolean danger() {
        if (elytraCheck.on() && mc.player.isGliding()) return true;
        if (fallCheck.on() && mc.player.fallDistance > 3 && mc.player.fallDistance - 3 >= DamageUtil.health(mc.player)) return true;
        return crystalCheck.on() && crystalDanger();
    }

    private boolean crystalDanger() {
        float hp = DamageUtil.health(mc.player);
        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof EndCrystalEntity c)) continue;
            if (c.squaredDistanceTo(mc.player) > 12 * 12) continue;
            if (DamageUtil.crystalDamage(mc.player, c.getEntityPos()) + 1f >= hp) return true;
        }
        return false;
    }
}
