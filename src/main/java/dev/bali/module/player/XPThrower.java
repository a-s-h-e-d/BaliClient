package dev.bali.module.player;

import dev.bali.manager.RotationPriority;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.ChatUtil;
import dev.bali.util.DamageUtil;
import dev.bali.util.InvUtil;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

/**
 * Throws experience bottles at your feet to mend your gear. Toggle with B.
 *
 * The bottles fly along the rotation in the item-use packet, so with Look Down they are thrown
 * from a silent pitch-90 rotation (the camera stays free) and land on you. Bottles come from the
 * offhand or hotbar (silent swap); with Refill a stack is moved up from the inventory when the
 * hotbar runs out. Pauses while you or AutoEat are eating, since a slot change would cancel it.
 *
 * You can only absorb one XP orb every 2 ticks, so throwing faster just piles orbs up. With Save
 * Bottles it waits while the XP already on the ground or in the air covers what is left to repair
 * (mending repairs 2 durability per XP), or while orbs are queuing up at your feet.
 */
public final class XPThrower extends Module {
    private final NumberSetting perTick = number("Bottles/Tick", 1, 1, 3, 1, "Bottles thrown each tick (vanilla right-click is one every 4 ticks)");
    private final BoolSetting lookDown = bool("Look Down", true, "Silently look straight down so the bottles land on you");
    private final BoolSetting autoDisable = bool("Auto Disable", true, "Turn off once your mending gear is fully repaired, or when out of bottles");
    private final BoolSetting refill = bool("Refill", true, "Move bottles from the inventory to the hotbar when the hotbar runs out");
    private final BoolSetting pauseEating = bool("Pause Eating", true, "Don't throw while eating (the slot swap would cancel the eat)");
    private final BoolSetting saveBottles = bool("Save Bottles", true, "Wait while the XP already thrown covers the repair, or orbs are piling up");

    /** Average XP in a bottle (3 to 11). */
    private static final int BOTTLE_XP = 7;
    /** Orbs waiting at your feet before we stop adding more. */
    private static final int ORB_BACKLOG = 6;

    private int thrown;

    public XPThrower() {
        super("XPThrower", "Throws XP bottles at your feet to mend gear", Category.PLAYER);
        setKey(GLFW.GLFW_KEY_B);
    }

    @Override
    public String getInfo() { return String.valueOf(InvUtil.count(Items.EXPERIENCE_BOTTLE)); }

    @Override
    protected void onEnable() { thrown = 0; }

    /** Bottles thrown since it was turned on (for tests). */
    public int thrown() { return thrown; }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (autoDisable.on() && !needsRepair()) {
            ChatUtil.info("XPThrower: gear repaired" + (thrown > 0 ? " (" + thrown + " bottles)" : ""));
            setEnabled(false);
            return;
        }
        if (pauseEating.on() && (mc.player.isUsingItem() || Bali.MODULES.get(AutoEat.class).isEating())) return;

        boolean offhand = mc.player.getOffHandStack().isOf(Items.EXPERIENCE_BOTTLE);
        int slot = offhand ? -1 : InvUtil.findHotbar(Items.EXPERIENCE_BOTTLE);
        if (!offhand && slot == -1) {
            if (refill.on() && moveUp()) return; // thrown from the hotbar next tick
            if (autoDisable.on()) {
                ChatUtil.info("XPThrower: out of bottles");
                setEnabled(false);
            }
            return;
        }

        if (saveBottles.on() && enoughOnTheWay()) return;

        Hand hand = offhand ? Hand.OFF_HAND : Hand.MAIN_HAND;
        int bottleSlot = slot;
        Runnable throwAll = () -> {
            Runnable use = () -> {
                for (int i = 0; i < perTick.getI(); i++) {
                    ItemStack held = mc.player.getStackInHand(hand);
                    if (!held.isOf(Items.EXPERIENCE_BOTTLE)) break;
                    mc.interactionManager.interactItem(mc.player, hand);
                    thrown++;
                }
                mc.player.swingHand(hand);
            };
            if (hand == Hand.OFF_HAND) use.run();
            else InvUtil.silentSwap(bottleSlot, use);
        };
        // the item-use packet carries the rotation the bottle flies along, so throw while the server has pitch 90
        if (lookDown.on()) Bali.ROTATIONS.rotate(mc.player.getYaw(), 90f, RotationPriority.XP_THROWER, throwAll);
        else throwAll.run();
    }

    /** Moves a bottle stack from the main inventory into an empty hotbar slot. */
    private boolean moveUp() {
        if (!InvUtil.canClickInventory()) return false;
        int inv = -1;
        for (int i = 9; i < 36; i++) if (mc.player.getInventory().getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) { inv = i; break; }
        if (inv == -1) return false;
        int empty = InvUtil.findHotbar(ItemStack::isEmpty);
        if (empty == -1) return false;
        if (!dev.bali.util.ClickGate.tryClick()) return true; // keys released; the move goes out next tick
        InvUtil.swapClick(InvUtil.toScreenSlot(inv), empty);
        return true;
    }

    /** XP lying around us or still flying in bottles already covers the repair, or orbs are queuing. */
    private boolean enoughOnTheWay() {
        var area = mc.player.getBoundingBox().expand(4);
        int orbs = 0, xp = 0;
        for (var orb : mc.world.getEntitiesByClass(net.minecraft.entity.ExperienceOrbEntity.class, area, o -> true)) {
            orbs++;
            xp += orb.getValue(); // merged orbs show as one, so this can only undercount
        }
        for (var b : mc.world.getEntitiesByClass(net.minecraft.entity.projectile.thrown.ExperienceBottleEntity.class, area.expand(4), o -> true)) xp += BOTTLE_XP;
        return orbs >= ORB_BACKLOG || xp * 2 >= damageToRepair();
    }

    private int damageToRepair() {
        int total = 0;
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = mc.player.getEquippedStack(s);
            if (!st.isEmpty() && st.isDamaged() && DamageUtil.enchantLevel(st, Enchantments.MENDING) > 0) total += st.getDamage();
        }
        return total;
    }

    /** Anything worn or held that has Mending and is damaged. */
    private boolean needsRepair() {
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = mc.player.getEquippedStack(s);
            if (!st.isEmpty() && st.isDamaged() && DamageUtil.enchantLevel(st, Enchantments.MENDING) > 0) return true;
        }
        return false;
    }
}
