package dev.bali.module.movement;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.InvUtil;
import dev.bali.util.MaceUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.PlayerInput;

import java.util.Locale;

public final class ElytraRecast extends Module {
    private final NumberSetting recastAfter = number("Recast After", 14, 4, 18, 1, "Glide ticks before swapping the chestplate in; the elytra takes damage on the 20th. Lower it on unstable ping");
    private final BoolSetting hotbarOnly = bool("Hotbar Only", true, "Only use a chestplate in your hotbar (one click each way, fastest). Off: the main inventory too (three clicks)");
    private final NumberSetting maxWait = number("Max Wait", 40, 10, 100, 1, "Put the elytra back if the server hasn't stopped the glide after this many ticks");

    private enum State { Idle, Prep, Wait, Release }

    private State state = State.Idle;
    private int glideTicks, waitTicks;
    private int elytraSlot = -1;
    private boolean jumpThisTick;
    private int recasts;

    public ElytraRecast() {
        super("ElytraRecast", "Swaps a chestplate in mid-glide at the right tick so the elytra never loses durability", Category.MOVEMENT);
    }

    @Override
    public String getInfo() { return recasts > 0 ? String.valueOf(recasts) : null; }

    public boolean busy() { return isEnabled() && state != State.Idle; }

    public boolean reopening() { return busy() && mc.player != null && !mc.player.isGliding(); }

    @Override
    protected void onEnable() {
        state = State.Idle;
        glideTicks = 0;
        elytraSlot = -1;
        recasts = 0;
    }

    @Override
    protected void onDisable() {
        if (elytraSlot != -1 && !nullCheck() && !MaceUtil.wearingElytra()) MaceUtil.equipChest(elytraSlot);
        elytraSlot = -1;
        state = State.Idle;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (nullCheck()) return;
        jumpThisTick = false;
        switch (state) {
            case Idle -> idle();
            case Prep -> swapOut();
            case Wait -> waitForStop();
            case Release -> {
                state = State.Idle;
                glideTicks = 0;
            }
        }
    }

    private void idle() {
        if (!mc.player.isGliding() || !MaceUtil.wearingElytra()) {
            glideTicks = 0;
            return;
        }
        glideTicks++;
        if (glideTicks >= recastAfter.getI() - 1 && canRecast() && !landingSoon() && chestplate() != -1) {
            state = State.Prep;
            mc.player.setSprinting(false);
        }
    }

    private boolean canRecast() {
        return InvUtil.canClickInventory() && mc.currentScreen == null && mc.player.currentScreenHandler.getCursorStack().isEmpty()
            && !mc.player.isTouchingWater() && !mc.player.hasVehicle()
            && !Bali.MODULES.get(dev.bali.module.combat.ElytraMace.class).busy();
    }

    private boolean landingSoon() {
        if (mc.player.isOnGround()) return true;
        var v = mc.player.getVelocity();
        double drop = Math.min(v.y, 0) * 3 - 0.1;
        return !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(v.x * 3, drop, v.z * 3));
    }

    private int chestplate() {
        int best = -1, bestScore = -1, end = hotbarOnly.on() ? 9 : 36;
        for (int i = 0; i < end; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isIn(ItemTags.CHEST_ARMOR)) continue;
            int score = (i < 9 ? 10 : 0) + (s.isOf(net.minecraft.item.Items.NETHERITE_CHESTPLATE) ? 3 : s.isOf(net.minecraft.item.Items.DIAMOND_CHESTPLATE) ? 2 : 1);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private void swapOut() {
        int chest = chestplate();
        if (!mc.player.isGliding() || !canRecast() || chest == -1 || mc.player.isSprinting() || !MaceUtil.equipChest(chest)) {
            state = State.Idle;
            return;
        }
        elytraSlot = chest;
        state = State.Wait;
        waitTicks = 0;
        Recorder.log("RECAST", String.format(Locale.ROOT, "chestplate in from slot %d after %d glide ticks, y=%.1f speed=%.1f b/s",
            chest, glideTicks, mc.player.getY(), mc.player.getVelocity().horizontalLength() * 20));
    }

    private void waitForStop() {
        waitTicks++;
        if (elytraSlot != -1 && MaceUtil.wearingElytra()) {
            Recorder.log("RECAST", "elytra was put back by something else after " + waitTicks + " ticks; recast skipped");
            elytraSlot = -1;
            state = State.Release;
            return;
        }
        if (mc.player.isGliding()) {
            if (waitTicks <= maxWait.getI()) return;
            putElytraBack();
            Recorder.log("RECAST", "server kept us gliding for " + waitTicks + " ticks; elytra back on");
            state = State.Release;
            return;
        }
        if (!putElytraBack()) return;
        if (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isClimbing()) {
            Recorder.log("RECAST", "landed during the recast; elytra back on");
            state = State.Release;
            return;
        }
        jumpThisTick = true;
        recasts++;
        state = State.Release;
        Recorder.log("RECAST", String.format(Locale.ROOT, "reopened #%d after %d ticks waiting, y=%.1f speed=%.1f b/s",
            recasts, waitTicks, mc.player.getY(), mc.player.getVelocity().horizontalLength() * 20));
    }

    private boolean putElytraBack() {
        if (elytraSlot == -1 || MaceUtil.wearingElytra()) {
            elytraSlot = -1;
            return true;
        }
        if (!MaceUtil.isElytra(mc.player.getInventory().getStack(elytraSlot))) {
            int other = MaceUtil.findElytra();
            if (other == -1) return false;
            elytraSlot = other;
        }
        if (!MaceUtil.equipChest(elytraSlot)) return false;
        elytraSlot = -1;
        return true;
    }

    public PlayerInput filterInput(PlayerInput in) {
        if (!isEnabled() || state == State.Idle) return null;
        return new PlayerInput(false, false, false, false, jumpThisTick, false, false);
    }
}
