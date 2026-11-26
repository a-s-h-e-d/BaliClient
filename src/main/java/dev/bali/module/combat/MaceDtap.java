package dev.bali.module.combat;

import dev.bali.manager.RotationPriority;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.DamageUtil;
import dev.bali.util.EntityUtil;
import dev.bali.util.InvUtil;
import dev.bali.util.MaceUtil;
import dev.bali.util.RotationUtil;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

public final class MaceDtap extends Module {
    public enum Mode { Grim, Spoof }
    public enum Targets { Players, All }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Grim, "Grim = real falls only (works on 2b2t); Spoof = packet spoof, flagged by Grim");
    private final BoolSetting autoSmash = bool("Auto Smash", true, "Hit the nearest target in reach as soon as a smash is ready (Grim)").visibleWhen(() -> is(Mode.Grim));
    private final EnumSetting<Targets> targets = mode("Targets", Targets.Players, "Who Auto Smash hits").visibleWhen(() -> is(Mode.Grim));
    private final NumberSetting range = number("Range", 3.0, 1, 3.5, 0.05, "Auto Smash range to the hitbox (Grim allows 3.0)").visibleWhen(() -> is(Mode.Grim));
    private final NumberSetting minFall = number("Min Fall", 1.6, 1.5, 20, 0.1, "Server fall distance needed before a smash (vanilla needs > 1.5)").visibleWhen(() -> is(Mode.Grim));
    private final BoolSetting swapToMace = bool("Swap To Mace", true, "Hit with the mace from any slot when the hit would smash");
    private final NumberSetting spoofHeight = number("Height", 10, 1, 20, 0.5, "Fake fall height per hit (Spoof); 10 always fits vanilla's move limit, up to 20 on manual hits").visibleWhen(() -> is(Mode.Spoof));
    private final NumberSetting spoofPackets = number("Packets", 3, 0, 3, 1, "Stationary packets first, raising vanilla's per-tick move limit (Spoof)").visibleWhen(() -> is(Mode.Spoof));

    private double serverFall;
    private double lastSentY = Double.NaN;
    private int restoreSlot = -1;
    private boolean reentrant;
    private int smashes;

    public MaceDtap() {
        super("MaceDtap", "Turns hits into mace smashes (real falls on Grim, spoofed elsewhere)", Category.COMBAT);
    }

    private boolean is(Mode m) { return mode != null && mode.is(m); }

    @Override
    public String getInfo() { return is(Mode.Spoof) ? "Spoof" : String.format(Locale.ROOT, "%.1f", serverFall); }

    @Override
    protected void onEnable() {
        serverFall = nullCheck() ? 0 : mc.player.fallDistance;
        lastSentY = Double.NaN;
        restoreSlot = -1;
    }

    @Override
    protected void onDisable() {
        if (restoreSlot != -1 && !nullCheck()) InvUtil.select(restoreSlot);
        restoreSlot = -1;
    }

    public double serverFall() { return serverFall; }

    public boolean smashReady() {
        return serverFall > Math.max(1.5, minFall.getD()) && !mc.player.isGliding();
    }

    @EventHandler(priority = -500)
    private void onSend(Events.PacketSend e) {
        if (e.isCancelled() || !(e.packet instanceof PlayerMoveC2SPacket p) || !p.changesPosition() || mc.player == null) return;
        double y = p.getY(lastSentY);
        double dy = Double.isNaN(lastSentY) ? 0 : y - lastSentY;
        lastSentY = y;
        if (p.isOnGround() || dy > 0) serverFall = 0;
        else if (dy < 0 && !mc.player.isTouchingWater()) serverFall -= dy;
        if (mc.player.hasStatusEffect(StatusEffects.SLOW_FALLING) || mc.player.hasStatusEffect(StatusEffects.LEVITATION)) serverFall = 0;
        if (mc.player.isGliding() && mc.player.getVelocity().y > -0.5 && serverFall > 1) serverFall = 1;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (nullCheck()) return;
        if (restoreSlot != -1) {
            InvUtil.select(restoreSlot);
            restoreSlot = -1;
        }
        if (!is(Mode.Grim) || !autoSmash.on() || !smashReady()) return;
        if (!MaceUtil.isMace(mc.player.getMainHandStack()) && (!swapToMace.on() || MaceUtil.findMace() == -1)) return;
        if (Bali.MODULES.get(ElytraMace.class).isEnabled() && mc.player.isGliding()) return;

        LivingEntity t = findTarget();
        if (t == null) return;
        Vec3d aim = RotationUtil.closestPoint(t.getBoundingBox(), mc.player.getEyePos());
        Bali.ROTATIONS.rotate(aim, RotationPriority.MACE_DTAP, () -> {
            if (!t.isAlive() || !inRange(t) || !smashReady()) return;
            mc.interactionManager.attackEntity(mc.player, t);
            mc.player.swingHand(Hand.MAIN_HAND);
        });
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity ent : mc.world.getEntities()) {
            if (!EntityUtil.isAliveTarget(ent) || EntityUtil.isFriend(ent)) continue;
            EntityUtil.Kind kind = EntityUtil.kind(ent);
            if (kind != EntityUtil.Kind.PLAYER && (targets.is(Targets.Players) || kind == EntityUtil.Kind.OTHER)) continue;
            LivingEntity l = (LivingEntity) ent;
            if (!inRange(l)) continue;
            double d = mc.player.squaredDistanceTo(l);
            if (d < bestSq) {
                bestSq = d;
                best = l;
            }
        }
        return best;
    }

    private boolean inRange(LivingEntity l) {
        Vec3d eye = mc.player.getEyePos();
        return eye.distanceTo(RotationUtil.closestPoint(l.getBoundingBox(), eye)) <= range.getD();
    }

    @EventHandler(priority = 100)
    private void onAttack(Events.Attack e) {
        if (reentrant || !(e.target instanceof LivingEntity target)) return;
        boolean holding = MaceUtil.isMace(mc.player.getMainHandStack());
        boolean willSmash = is(Mode.Spoof) || smashReady();
        int maceSlot = holding ? InvUtil.selected() : (swapToMace.on() && willSmash ? MaceUtil.findMace() : -1);
        if (maceSlot == -1) return;

        if (!holding) {
            e.cancel();
            int prev = InvUtil.selected();
            InvUtil.select(maceSlot);
            restoreSlot = prev;
            reentrant = true;
            try {
                if (is(Mode.Spoof)) spoof();
                mc.interactionManager.attackEntity(mc.player, target);
            } finally {
                reentrant = false;
            }
        } else if (is(Mode.Spoof)) {
            spoof();
        }
        afterSmash(target);
    }

    private void afterSmash(LivingEntity target) {
        double fall = is(Mode.Spoof) ? spoofHeight.getD() : serverFall;
        if (!is(Mode.Spoof) && !smashReady()) return;
        smashes++;
        int density = DamageUtil.enchantLevel(mc.player.getMainHandStack(), Enchantments.DENSITY);
        Recorder.log("MACE", String.format(Locale.ROOT, "smash #%d %s on %s fall=%.1f est bonus=%.1f",
            smashes, mode.get(), target.getName().getString(), fall, MaceUtil.smashBonus(fall, density)));
        serverFall = 0;
    }

    private void spoof() {
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        boolean hc = mc.player.horizontalCollision;
        double h = spoofHeight.getD();
        while (h > 1 && !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(0, h, 0))) h -= 0.5;
        var net = mc.getNetworkHandler();
        for (int i = 0; i < spoofPackets.getI(); i++) net.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, hc));
        net.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + h, z, false, hc));
        net.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, hc));
    }
}
