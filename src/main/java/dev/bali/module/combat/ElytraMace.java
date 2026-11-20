package dev.bali.module.combat;

import dev.bali.manager.RotationPriority;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.DamageUtil;
import dev.bali.util.EntityUtil;
import dev.bali.util.GrimBoost;
import dev.bali.util.InvUtil;
import dev.bali.util.MaceUtil;
import dev.bali.util.Render3D;
import dev.bali.util.RotationUtil;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Elytra dive-bomb with a mace.
 *
 * Launch: take off and open the elytra the vanilla way (fresh jump press).
 * Approach: rocket-fly to a point {@code Height} blocks above where the target is heading. While a
 *   rocket is attached Grim accepts any velocity inside a 1.7 b/t per-axis box around the look
 *   (see {@link GrimBoost}), so the flight steers straight at the point instead of turning like a glider.
 * Dive: point the look and the velocity at the target's predicted position and come down fast. The
 *   server adds every downward move to fallDistance as long as its own velocity.y stays below -0.5.
 * Strike: a few ticks before reach, swap the chestplate in. The server stops gliding on its next tick
 *   (no glider equipped), which is what lets the mace smash bonus apply, and the hit then lands with
 *   the whole dive as fall distance. A smash also cancels our own fall.
 * Recover: put the elytra back on, reopen, climb, repeat.
 */
public final class ElytraMace extends Module {
    public enum Targets { Players, All }
    private enum Phase { Idle, Launch, Approach, Dive, Strike, Recover }

    private final EnumSetting<Targets> targets = mode("Targets", Targets.Players, "Who to hunt");
    private final NumberSetting targetRange = number("Target Range", 64, 8, 160, 1, "Lock onto targets within this distance");
    private final NumberSetting height = number("Height", 20, 6, 60, 1, "Blocks above the target to dive from; higher = more damage, slower cycle");
    private final NumberSetting speed = number("Speed", 34, 10, 34, 1, "Rocket flight speed in blocks/s (Grim's box allows 34 per axis)");
    private final NumberSetting reach = number("Reach", 3.0, 2, 3.5, 0.05, "Attack range to the hitbox (Grim allows 3.0)");
    private final NumberSetting swapLead = number("Swap Lead", 3, 1, 8, 1, "Swap to the chestplate this many ticks before reach; raise on high ping");
    private final NumberSetting hitDelay = number("Hit Delay", 2, 1, 6, 1, "Ticks after the swap before hitting, so the server has stopped gliding");
    private final BoolSetting crits = bool("Crits", true, "Hover until the hit will be a full-charge crit: the whole smash x1.5");
    private final NumberSetting rocketDelay = number("Rocket Delay", 10, 2, 40, 1, "Min ticks between rockets");
    private final BoolSetting autoTakeoff = bool("Auto Takeoff", true, "Jump and open the elytra from the ground");
    private final BoolSetting render = bool("Render", true, "Highlight the target and dive point");
    private final ColorSetting color = color("Color", 0x60A040FF, "Target highlight");

    private Phase phase = Phase.Idle;
    private LivingEntity target;
    private int rocketTimer, phaseTicks, hits;
    private volatile int setbackPause;
    private boolean swapped, jumpDown, holdingJump;
    private double diveStartY;
    private Vec3d divePoint;
    /** The chest slot held a chestplate when we were enabled: put it back when we are disabled. */
    private boolean restoreChestplate;
    // target motion, smoothed over a few ticks (other players move in 2-3 tick interpolated steps)
    private Entity tracked;
    private Vec3d trackedPrev, trackedVel = Vec3d.ZERO;

    public ElytraMace() {
        super("ElytraMace", "Elytra dive-bombs the target with a mace and a timed chestplate swap", Category.COMBAT);
    }

    @Override
    public String getInfo() {
        String name = target instanceof PlayerEntity p ? p.getGameProfile().name() : target != null ? target.getName().getString() : null;
        return name == null ? phase.name() : phase.name() + " " + name;
    }

    /** True while ElytraMace manages the chest slot (AutoArmor stays off it). */
    public boolean ownsChestSlot() { return isEnabled(); }

    public int hits() { return hits; }

    /** True while hunting a target (other flight modules stand down). */
    public boolean busy() { return isEnabled() && phase != Phase.Idle; }

    /**
     * The hit itself: chestplate swapped in, falling onto the target. Nothing may take the hand or
     * the chest slot now, not even AntiMace (the smash also cancels our own fall).
     */
    public boolean striking() { return isEnabled() && phase == Phase.Strike; }

    /** Someone AntiMace caught diving at us: hunted next, once their dive is over. */
    private PlayerEntity counter;
    private long counterUntil;

    @Override
    protected void onEnable() {
        phase = Phase.Idle;
        target = null;
        swapped = false;
        hits = 0;
        tracked = null;
        counter = null;
        restoreChestplate = !nullCheck() && mc.player.getEquippedStack(EquipmentSlot.CHEST).isIn(ItemTags.CHEST_ARMOR);
    }

    @Override
    protected void onDisable() {
        releaseJump();
        if (!nullCheck()) {
            boolean down = mc.player.isOnGround() || mc.player.isTouchingWater();
            if (!down) {
                // never leave us falling in a chestplate: put the elytra back so space reopens it
                if (!MaceUtil.wearingElytra()) Bali.EVENTS.subscribe(new ElytraBack()); // keys released first, then the click
                if (restoreChestplate) {
                    Bali.EVENTS.subscribe(new ChestRestore());
                    dev.bali.util.ChatUtil.info("ElytraMace: chestplate goes back on when you land");
                }
            } else if (restoreChestplate && MaceUtil.wearingElytra()) {
                Bali.EVENTS.subscribe(new ChestRestore()); // next tick, with the keys released first
            }
        }
        target = null;
        phase = Phase.Idle;
    }

    // ------------------------------------------------------------------ tick

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (nullCheck()) return;
        if (rocketTimer > 0) rocketTimer--;
        phaseTicks++;
        if (mc.player.hasVehicle() || mc.player.isTouchingWater() || mc.player.getAbilities().flying) {
            releaseJump();
            return;
        }

        PlayerEntity threat = Bali.MODULES.get(AntiMace.class).threat();
        if (threat != null && threat != counter) {
            counter = threat;
            counterUntil = System.currentTimeMillis() + 10_000;
            Recorder.log("EMACE", "counter: " + threat.getGameProfile().name() + " is diving at us, hunting them next");
        }
        LivingEntity before = target;
        target = pickTarget();
        // re-plan a dive that was aimed at someone else
        if (target != before && before != null && phase == Phase.Dive) setPhase(Phase.Approach, "switched to the attacker");
        trackTarget();
        // Safety first: while a dive is coming at us AntiMace shields, swaps the chestplate in or
        // rockets out of the way. We stand down, except for our own hit (already committed) and a
        // recovery in the air (pausing it would leave us falling in a chestplate).
        if (threat != null && phase != Phase.Strike && !(phase == Phase.Recover && !mc.player.isOnGround())) {
            releaseJump();
            return;
        }
        if (target == null) {
            if (phase == Phase.Dive || phase == Phase.Strike || swapped) setPhase(Phase.Recover, "target lost");
            else if (phase != Phase.Recover) {
                releaseJump();
                setPhase(Phase.Idle, null);
                return;
            }
        } else if (phase == Phase.Idle) {
            setPhase(mc.player.isGliding() ? Phase.Approach : Phase.Launch, null);
        }

        switch (phase) {
            case Launch -> launch();
            case Approach -> approach();
            case Dive -> dive();
            case Strike -> strike();
            case Recover -> recover();
            default -> {}
        }
    }

    private void launch() {
        if (!MaceUtil.wearingElytra()) {
            releaseJump();
            if (!gate()) return;
            if (!MaceUtil.equipChest(MaceUtil.findElytra())) return;
            swapped = false;
            return; // equipment change takes effect next tick
        }
        if (mc.player.isGliding()) {
            releaseJump();
            setPhase(Phase.Approach, null);
            return;
        }
        if (mc.player.isOnGround()) {
            if (autoTakeoff.on()) setJump(true);
            else releaseJump();
        } else if (mc.player.getVelocity().y < 0.1 && !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(0, -0.3, 0))) {
            // a tiny hop (a hit's knockback): opening here lands at once and loops. Land, then jump properly.
            releaseJump();
        } else {
            // vanilla (and Grim) only open the elytra on a fresh jump press
            setJump(!jumpDown);
        }
    }

    private void approach() {
        releaseJump();
        if (!mc.player.isGliding()) {
            setPhase(Phase.Launch, "not gliding");
            return;
        }
        if (setbackPause > 0) return;
        holdMace();
        Vec3d pos = mc.player.getEntityPos();
        double s = speed.getD() / 20;
        Vec3d goal = intercept(pos, new Vec3d(0, height.getD(), 0), s);
        goal = new Vec3d(goal.x, Math.min(goal.y, maxY()), goal.z);
        divePoint = goal;
        // horizontal distance to where the target will be when a dive from here arrives
        Vec3d diveHit = intercept(mc.player.getEyePos(), Vec3d.ZERO, s);
        double hDist = Math.hypot(diveHit.x - pos.x, diveHit.z - pos.z);

        double above = pos.y - target.getY();
        // a crit (charge > 0.9, not sprinting) multiplies base + smash bonus by 1.5: wait for the charge at impact
        double diveTicks = Math.max(0, above - reach.getD()) / (speed.getD() / 20) + swapLead.getI();
        boolean charged = !crits.on() || mc.player.getAttackCooldownProgress(0.5f + (float) diveTicks) > 0.92f;
        // Grim's box is per axis: a dive whose sideways lead (per axis) is no more than its drop still falls at
        // the full 1.7 b/t, so a moving target can be dived on at up to 45 degrees
        double lead = Math.max(Math.abs(diveHit.x - pos.x), Math.abs(diveHit.z - pos.z));
        if (above >= height.getD() * 0.8 && lead <= Math.max(4, above - reach.getD()) && charged) {
            diveStartY = pos.y;
            setPhase(Phase.Dive, String.format(Locale.ROOT, "dive from %.1f above, %.1f out", above, hDist));
            dive();
            return;
        }

        Vec3d delta = goal.subtract(pos);
        boolean rocket = GrimBoost.rocketAttached();
        Runnable fire = needRocket(rocket) ? this::fireRocket : null;
        if (rocket) {
            double len = delta.length();
            // fly at the goal and match the target's motion as we arrive, so we hover above it
            Vec3d want = len < 1e-3 ? Vec3d.ZERO : delta.multiply(Math.min(s, len / 4) / len);
            want = want.add(targetVelocity(target).multiply(Math.max(0, 1 - len / 8)));
            GrimBoost.requestVelocity(want);
            float[] r = want.lengthSquared() > 0.04 ? GrimBoost.lookAlong(want)
                : RotationUtil.rotationsTo(mc.player.getEyePos(), target.getBoundingBox().getCenter());
            GrimBoost.face(r[0], r[1], RotationPriority.ELYTRA_MACE, fire);
        } else {
            // between rockets: glide toward the goal holding height
            float yaw = RotationUtil.rotationsTo(pos, goal)[0];
            double wantVy = MathHelper.clamp(delta.y / 20, -0.5, 0);
            GrimBoost.face(yaw, GrimBoost.glidePitch(mc.player.getVelocity(), yaw, wantVy), RotationPriority.ELYTRA_MACE, fire);
        }
    }

    private void dive() {
        releaseJump();
        if (!mc.player.isGliding()) {
            setPhase(Phase.Launch, "stopped gliding mid-dive");
            return;
        }
        if (setbackPause > 0) return;
        holdMace();
        Vec3d eye = mc.player.getEyePos();
        Vec3d vel = mc.player.getVelocity();
        Box box = target.getBoundingBox();
        double dist = eye.distanceTo(RotationUtil.closestPoint(box, eye));
        double s = speed.getD() / 20;
        Vec3d aim = intercept(eye, new Vec3d(0, target.getHeight() * 0.7, 0), s);
        divePoint = aim;
        // the velocity that arrives exactly there; at least one axis is at full speed
        Vec3d flyVel = aim.subtract(eye).multiply(1.0 / interceptTicks);
        Vec3d dir = flyVel.normalize();

        if (mc.player.isOnGround() || mc.player.getY() < target.getY() - 1) {
            setPhase(Phase.Recover, "passed the target in the dive");
            return;
        }

        boolean rocket = GrimBoost.rocketAttached();
        Runnable fire = needRocket(rocket) ? this::fireRocket : null;
        if (rocket) GrimBoost.requestVelocity(flyVel);
        float[] r = GrimBoost.lookAlong(dir);
        GrimBoost.face(r[0], r[1], RotationPriority.ELYTRA_MACE, fire);

        // time to reach, from our closing speed on the target
        Vec3d rel = vel.subtract(targetVelocity(target));
        Vec3d to = RotationUtil.closestPoint(box, eye).subtract(eye);
        double closing = to.lengthSquared() < 1e-6 ? 1 : Math.max(0.2, rel.dotProduct(to.normalize()));
        double ticksToReach = (dist - reach.getD()) / closing;
        // the server must still be falling fast (velocity.y < -0.5) on the tick it stops gliding, or it caps fallDistance at 1
        boolean fastEnough = vel.y < -0.6 || dist < reach.getD() + 1;
        // one tick early: the tick before the click sends our keys released (Grim MultiActionsC)
        if (ticksToReach <= swapLead.getI() + 1 && fastEnough) {
            int chest = MaceUtil.findChestplate();
            if (chest == -1) {
                Recorder.log("EMACE", "no chestplate to swap in; hitting while gliding gives no smash bonus");
                setPhase(Phase.Strike, null);
                return;
            }
            if (!gate()) return;
            if (MaceUtil.equipChest(chest)) {
                swapped = true;
                setPhase(Phase.Strike, String.format(Locale.ROOT, "chestplate in: dist=%.2f ticksToReach=%.1f vy=%.2f fall=%.1f",
                    dist, ticksToReach, vel.y, mc.player.fallDistance));
            }
        }
    }

    private void strike() {
        releaseJump();
        if (mc.player.isSprinting()) mc.player.setSprinting(false); // sprinting hits can't crit
        Vec3d eye = mc.player.getEyePos();
        Box box = target.getBoundingBox();
        Vec3d aim = RotationUtil.closestPoint(box, eye);
        double dist = eye.distanceTo(aim);
        divePoint = aim;
        if (Recorder.active()) {
            Vec3d v = mc.player.getVelocity();
            Recorder.log("EMACE", String.format(Locale.ROOT, "strike t=%d dist=%.2f y=%.2f vel=(%.2f %.2f %.2f) gliding=%b ground=%b fall=%.1f",
                phaseTicks, dist, mc.player.getY(), v.x, v.y, v.z, mc.player.isGliding(), mc.player.isOnGround(), mc.player.fallDistance));
        }

        // Until the server's "stopped gliding" reaches us the client still glides. Keep flying the rocket
        // ourselves: the vanilla push would use the camera's look, not the rotation the server has.
        if (mc.player.isGliding() && GrimBoost.rocketAttached() && setbackPause == 0) {
            Vec3d at = intercept(eye, new Vec3d(0, target.getHeight() * 0.7, 0), speed.getD() / 20);
            GrimBoost.requestVelocity(at.subtract(eye).multiply(1.0 / interceptTicks));
        }

        if (dist <= reach.getD() && phaseTicks >= hitDelay.getI()) {
            holdMace();
            LivingEntity t = target;
            double fall = mc.player.fallDistance;
            Bali.ROTATIONS.rotate(aim, RotationPriority.ELYTRA_MACE, () -> {
                if (!t.isAlive() || mc.player.getEyePos().distanceTo(RotationUtil.closestPoint(t.getBoundingBox(), mc.player.getEyePos())) > reach.getD()) return;
                mc.interactionManager.attackEntity(mc.player, t);
                mc.player.swingHand(Hand.MAIN_HAND);
                hits++;
                int density = DamageUtil.enchantLevel(mc.player.getMainHandStack(), Enchantments.DENSITY);
                setPhase(Phase.Recover, String.format(Locale.ROOT, "SMASH #%d fall=%.1f (dove %.1f) est bonus=%.1f gliding=%b",
                    hits, fall, diveStartY - mc.player.getY(), MaceUtil.smashBonus(fall, density), mc.player.isGliding()));
            });
            return;
        }
        // keep looking at the target: while the client still glides (until the server's flag arrives) this steers us in
        float[] r = RotationUtil.rotationsTo(eye, aim);
        GrimBoost.face(r[0], r[1], RotationPriority.ELYTRA_MACE, null);

        if (phaseTicks > 25 || mc.player.isOnGround() || mc.player.getY() < target.getY() - 2) {
            setPhase(Phase.Recover, String.format(Locale.ROOT, "missed: dist=%.2f after %d ticks", dist, phaseTicks));
        }
    }

    private void recover() {
        if (!MaceUtil.wearingElytra()) {
            if (MaceUtil.findElytra() == -1) {
                Recorder.log("EMACE", "no elytra left, disabling");
                toggle();
                return;
            }
            releaseJump();
            if (gate() && MaceUtil.equipChest(MaceUtil.findElytra())) swapped = false;
            return;
        }
        swapped = false;
        setPhase(target == null ? Phase.Idle : Phase.Launch, null);
        if (target != null) launch();
    }

    // ------------------------------------------------------------------ helpers

    private LivingEntity pickTarget() {
        double maxSq = targetRange.getD() * targetRange.getD();
        // the attacker comes first; not mid-hit, the strike is committed to its target
        if (counter != null && phase != Phase.Strike) {
            PlayerEntity c = counter;
            boolean valid = System.currentTimeMillis() < counterUntil && c.isAlive() && !c.isRemoved()
                && EntityUtil.isAliveTarget(c) && !EntityUtil.isFriend(c) && mc.player.squaredDistanceTo(c) <= maxSq;
            if (valid) return c;
            counter = null;
        }
        if (target != null && target.isAlive() && !target.isRemoved() && mc.player.squaredDistanceTo(target) <= maxSq * 1.5) return target;
        LivingEntity best = null;
        double bestSq = maxSq;
        for (Entity ent : mc.world.getEntities()) {
            if (!EntityUtil.isAliveTarget(ent) || EntityUtil.isFriend(ent)) continue;
            EntityUtil.Kind kind = EntityUtil.kind(ent);
            if (kind != EntityUtil.Kind.PLAYER && (targets.is(Targets.Players) || kind == EntityUtil.Kind.OTHER)) continue;
            double d = mc.player.squaredDistanceTo(ent);
            if (d < bestSq) {
                bestSq = d;
                best = (LivingEntity) ent;
            }
        }
        return best;
    }

    private void trackTarget() {
        if (target == null) {
            tracked = null;
            return;
        }
        Vec3d pos = target.getEntityPos();
        if (tracked != target || trackedPrev == null) {
            tracked = target;
            trackedVel = new Vec3d(target.getX() - target.lastX, target.getY() - target.lastY, target.getZ() - target.lastZ);
        } else {
            Vec3d raw = pos.subtract(trackedPrev);
            if (raw.lengthSquared() > 16) raw = trackedVel; // teleport or pearl: ignore the jump
            trackedVel = trackedVel.multiply(0.5).add(raw.multiply(0.5));
        }
        trackedPrev = pos;
    }

    private Vec3d targetVelocity(Entity t) {
        return t == tracked ? trackedVel : new Vec3d(t.getX() - t.lastX, t.getY() - t.lastY, t.getZ() - t.lastZ);
    }

    /** Where the target will be in {@code ticks}, from its recent motion. Vertical only if it is airborne. */
    private Vec3d predict(Entity t, double ticks) {
        Vec3d v = targetVelocity(t);
        if (t.isOnGround()) v = new Vec3d(v.x, 0, v.z);
        return t.getEntityPos().add(v.multiply(ticks));
    }

    private int interceptTicks;

    /**
     * Lead pursuit: the first future tick at which flying at most {@code s} b/t on every axis (Grim's box)
     * gets us from {@code from} to the target's predicted position plus {@code offset}. Sets interceptTicks.
     */
    private Vec3d intercept(Vec3d from, Vec3d offset, double s) {
        for (int t = 1; t <= 60; t++) {
            Vec3d p = predict(target, t).add(offset);
            Vec3d d = p.subtract(from);
            if (Math.max(Math.abs(d.x), Math.max(Math.abs(d.y), Math.abs(d.z))) <= s * t) {
                interceptTicks = t;
                return p;
            }
        }
        interceptTicks = 60;
        return predict(target, 60).add(offset);
    }

    /** After disabling mid-air: the elytra goes back on as soon as a click is Grim-safe (a tick later, at most a few). */
    private static final class ElytraBack {
        private int ticks;

        @EventHandler
        private void onTick(Events.TickPre e) {
            if (mc.player == null || MaceUtil.wearingElytra() || Bali.MODULES.get(ElytraMace.class).isEnabled()) {
                Bali.EVENTS.unsubscribe(this);
                return;
            }
            dev.bali.util.ClickGate.hold();
            // falling in a chestplate is worse than one flagged click: don't wait more than 5 ticks
            if ((dev.bali.util.ClickGate.ready() || ++ticks > 5) && MaceUtil.equipChest(MaceUtil.findElytra())) Bali.EVENTS.unsubscribe(this);
        }
    }

    /** After disabling in the air: put the chestplate back on once we are down (or ElytraMace is turned back on). */
    private static final class ChestRestore {
        private int timeout = 20 * 60 * 10;

        @EventHandler
        private void onTick(Events.TickPre e) {
            if (mc.player == null || --timeout <= 0 || Bali.MODULES.get(ElytraMace.class).isEnabled()) {
                Bali.EVENTS.unsubscribe(this);
                return;
            }
            if (mc.player.isGliding() || !(mc.player.isOnGround() || mc.player.isTouchingWater())) return;
            if (!MaceUtil.wearingElytra()) {
                Bali.EVENTS.unsubscribe(this);
                return;
            }
            dev.bali.util.ClickGate.hold();
            if (dev.bali.util.ClickGate.ready() && MaceUtil.equipChest(MaceUtil.findChestplate())) Bali.EVENTS.unsubscribe(this);
        }
    }

    /**
     * Keys released this tick (Grim MultiActionsC cancels clicks sent while moving, jumping or sprinting);
     * true once the server has that clean input, i.e. a click now goes through.
     */
    private static boolean gate() {
        dev.bali.util.ClickGate.hold();
        return dev.bali.util.ClickGate.ready();
    }

    private boolean needRocket(boolean attached) {
        return !attached && rocketTimer <= 0 && setbackPause == 0;
    }

    private void fireRocket() {
        if (rocketTimer > 0) return;
        if (GrimBoost.fireRocket()) rocketTimer = rocketDelay.getI();
    }

    private void holdMace() {
        if (MaceUtil.isMace(mc.player.getMainHandStack())) return;
        int slot = MaceUtil.findMace();
        if (slot != -1) InvUtil.select(slot);
    }

    private double maxY() {
        return mc.world.getDimension().hasCeiling() && mc.player.getY() < 127 ? 118 : 1000;
    }

    private void setPhase(Phase p, String why) {
        if (why != null) Recorder.log("EMACE", phase + " -> " + p + ": " + why);
        if (p != phase) phaseTicks = 0;
        phase = p;
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (e.packet instanceof PlayerPositionLookS2CPacket) {
            setbackPause = 10;
            Recorder.log("EMACE", "setback in phase " + phase);
        }
    }

    @EventHandler
    private void onTickPost(Events.TickPost e) {
        if (setbackPause > 0) setbackPause--;
    }

    private void setJump(boolean down) {
        mc.options.jumpKey.setPressed(down);
        jumpDown = down;
        holdingJump = true;
    }

    private void releaseJump() {
        if (!holdingJump) return;
        holdingJump = false;
        jumpDown = false;
        if (mc.options != null) mc.options.jumpKey.setPressed(false);
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (!render.on() || target == null) return;
        Vec3d p = target.getLerpedPos(e.tickDelta);
        Box b = target.getBoundingBox().offset(p.subtract(target.getEntityPos()));
        Render3D.box(b, color.argb(), color.withAlpha(255), 1.5f);
        if (divePoint != null && (phase == Phase.Approach || phase == Phase.Dive)) {
            Render3D.box(new Box(divePoint.add(-0.25, -0.25, -0.25), divePoint.add(0.25, 0.25, 0.25)), color.argb(), color.withAlpha(255), 1.5f);
        }
    }
}
