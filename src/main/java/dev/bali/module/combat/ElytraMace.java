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
import dev.bali.util.FlightGuard;
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
    private final BoolSetting avoid = bool("Avoid Blocks", true, "Never fly or dive into blocks: steer around them, only dive on a clear path, bail out of a strike that would hit the ground");
    private final BoolSetting bailRocket = bool("Bail Rocket", true, "Avoid Blocks: fire a fresh rocket just before the swap if the attached one would burn out mid-strike, so a dodged strike can still brake (about +1 rocket per dive with flight duration 1 rockets)");
    private final BoolSetting render = bool("Render", true, "Highlight the target and dive point");
    private final ColorSetting color = color("Color", 0x60A040FF, "Target highlight");

    private Phase phase = Phase.Idle;
    private LivingEntity target;
    private int rocketTimer, phaseTicks, hits;
    private volatile int setbackPause;
    private boolean swapped, jumpDown, holdingJump;
    private double diveStartY;
    private Vec3d divePoint;
    private boolean restoreChestplate;
    private Entity tracked;
    private Vec3d trackedPrev, trackedVel = Vec3d.ZERO;
    private Vec3d steerPrev, diveOffset = Vec3d.ZERO;
    private int offsetCheck;
    private boolean avoiding, covered, holdSwapLogged;

    public ElytraMace() {
        super("ElytraMace", "Elytra dive-bombs the target with a mace and a timed chestplate swap", Category.COMBAT);
    }

    @Override
    public String getInfo() {
        String name = target instanceof PlayerEntity p ? p.getGameProfile().name() : target != null ? target.getName().getString() : null;
        return name == null ? phase.name() : phase.name() + " " + name;
    }

    public boolean ownsChestSlot() { return isEnabled(); }

    public int hits() { return hits; }

    public boolean busy() { return isEnabled() && phase != Phase.Idle; }

    public boolean striking() { return isEnabled() && phase == Phase.Strike; }

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
        steerPrev = null;
        diveOffset = Vec3d.ZERO;
        offsetCheck = 0;
        avoiding = covered = holdSwapLogged = false;
        restoreChestplate = !nullCheck() && mc.player.getEquippedStack(EquipmentSlot.CHEST).isIn(ItemTags.CHEST_ARMOR);
    }

    @Override
    protected void onDisable() {
        releaseJump();
        if (!nullCheck()) {
            boolean down = mc.player.isOnGround() || mc.player.isTouchingWater();
            if (!down) {
                if (!MaceUtil.wearingElytra()) Bali.EVENTS.subscribe(new ElytraBack());
                if (restoreChestplate) {
                    Bali.EVENTS.subscribe(new ChestRestore());
                    dev.bali.util.ChatUtil.info("ElytraMace: chestplate goes back on when you land");
                }
            } else if (restoreChestplate && MaceUtil.wearingElytra()) {
                Bali.EVENTS.subscribe(new ChestRestore());
            }
        }
        target = null;
        phase = Phase.Idle;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (nullCheck()) return;
        if (rocketTimer > 0) rocketTimer--;
        phaseTicks++;
        if (mc.player.hasVehicle() || mc.player.isTouchingWater() || mc.player.getAbilities().flying) {
            releaseJump();
            return;
        }
        if (phase != Phase.Idle && mc.player.isGliding()) GrimBoost.holdRocket();

        PlayerEntity threat = Bali.MODULES.get(AntiMace.class).threat();
        if (threat != null && threat != counter) {
            counter = threat;
            counterUntil = System.currentTimeMillis() + 10_000;
            Recorder.log("EMACE", "counter: " + threat.getGameProfile().name() + " is diving at us, hunting them next");
        }
        LivingEntity before = target;
        target = pickTarget();
        if (target != before && before != null && phase == Phase.Dive) setPhase(Phase.Approach, "switched to the attacker");
        if (target != before) {
            diveOffset = Vec3d.ZERO;
            offsetCheck = 0;
            covered = false;
        }
        trackTarget();
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
            return;
        }
        if (mc.player.isGliding()) {
            releaseJump();
            setPhase(Phase.Approach, null);
            approach();
            return;
        }
        if (mc.player.isOnGround()) {
            if (autoTakeoff.on()) setJump(true);
            else releaseJump();
        } else if (mc.player.getVelocity().y < 0.1 && !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().offset(0, -0.3, 0))) {
            releaseJump();
        } else {
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
        Vec3d off = diveOffset();
        Vec3d goal = intercept(pos, new Vec3d(off.x, height.getD(), off.z), s);
        goal = new Vec3d(goal.x, Math.min(goal.y, maxY()), goal.z);
        divePoint = goal;
        Vec3d diveHit = intercept(mc.player.getEyePos(), Vec3d.ZERO, s);
        double hDist = Math.hypot(diveHit.x - pos.x, diveHit.z - pos.z);

        double above = pos.y - target.getY();
        double diveTicks = Math.max(0, above - reach.getD()) / (speed.getD() / 20) + swapLead.getI();
        boolean charged = !crits.on() || mc.player.getAttackCooldownProgress(0.5f + (float) diveTicks) > 0.92f;
        double lead = Math.max(Math.abs(diveHit.x - pos.x), Math.abs(diveHit.z - pos.z));
        if (above >= height.getD() * 0.8 && lead <= Math.max(4, above - reach.getD()) && charged
            && (!avoid.on() || strikePathClear(mc.player.getBoundingBox(), mc.player.getEyePos(), diveAim(), diveTargetBox()))) {
            diveStartY = pos.y;
            holdSwapLogged = false;
            setPhase(Phase.Dive, String.format(Locale.ROOT, "dive from %.1f above, %.1f out", above, hDist));
            dive();
            return;
        }

        Vec3d delta = goal.subtract(pos);
        boolean rocket = GrimBoost.rocketAttached();
        Runnable fire = needRocket(rocket) ? this::fireRocket : null;
        if (rocket) {
            double len = delta.length();
            Vec3d want = len < 1e-3 ? Vec3d.ZERO : delta.multiply(Math.min(s, len / 4) / len);
            want = want.add(targetVelocity(target).multiply(Math.max(0, 1 - len / 8)));
            if (avoid.on()) want = steer(want, len);
            GrimBoost.requestVelocity(want);
            float[] r = want.lengthSquared() > 0.04 ? GrimBoost.lookAlong(want)
                : RotationUtil.rotationsTo(mc.player.getEyePos(), target.getBoundingBox().getCenter());
            GrimBoost.face(r[0], r[1], RotationPriority.ELYTRA_MACE, fire);
        } else {
            float yaw = RotationUtil.rotationsTo(pos, goal)[0];
            double wantVy = MathHelper.clamp(delta.y / 20, -0.5, 0);
            float pitch = GrimBoost.glidePitch(mc.player.getVelocity(), yaw, wantVy);
            float[] safe = avoid.on() ? FlightGuard.safeGlide(yaw, pitch, 12) : null;
            if (safe != null) {
                if (!avoiding) Recorder.log("EMACE", String.format(Locale.ROOT, "avoid: glide would hit a block, turning %.0f/%.0f -> %.0f/%.0f and rocketing",
                    yaw, pitch, safe[0], safe[1]));
                avoiding = true;
                yaw = safe[0];
                pitch = safe[1];
                emergencyRocket();
                fire = needRocket(false) ? this::fireRocket : null;
            }
            GrimBoost.face(yaw, pitch, RotationPriority.ELYTRA_MACE, fire);
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
        Vec3d flyVel = aim.subtract(eye).multiply(1.0 / interceptTicks);
        Vec3d dir = flyVel.normalize();

        if (mc.player.isOnGround() || mc.player.getY() < target.getY() - 1) {
            setPhase(Phase.Recover, "passed the target in the dive");
            return;
        }
        if (avoid.on() && !strikePathClear(mc.player.getBoundingBox(), eye, aim, box.offset(aim.subtract(target.getEntityPos().add(0, target.getHeight() * 0.7, 0))))) {
            offsetCheck = 0;
            setPhase(Phase.Approach, String.format(Locale.ROOT, "dive path blocked %.1f from the target, pulling out", dist));
            approach();
            return;
        }

        Vec3d rel = vel.subtract(targetVelocity(target));
        Vec3d to = RotationUtil.closestPoint(box, eye).subtract(eye);
        double closing = to.lengthSquared() < 1e-6 ? 1 : Math.max(0.2, rel.dotProduct(to.normalize()));
        double ticksToReach = (dist - reach.getD()) / closing;

        boolean rocket = GrimBoost.rocketAttached();
        Runnable fire = needRocket(rocket) ? this::fireRocket : null;
        if (avoid.on() && bailRocket.on() && rocket && fire == null && ticksToReach <= swapLead.getI() + 4
            && GrimBoost.rocketLifeLeft() < swapLead.getI() + 10 && rocketDelay.getI() - rocketTimer >= 4 && setbackPause == 0) {
            rocketTimer = 0;
            fire = this::fireRocket;
            Recorder.log("EMACE", "fresh rocket for the strike (the attached one would burn out)");
        }
        if (rocket) GrimBoost.requestVelocity(flyVel);
        float[] r = GrimBoost.lookAlong(dir);
        GrimBoost.face(r[0], r[1], RotationPriority.ELYTRA_MACE, fire);

        boolean fastEnough = vel.y < -0.6 || dist < reach.getD() + 1;
        if (ticksToReach <= swapLead.getI() + 1 && fastEnough) {
            if (avoid.on()) {
                FlightGuard.Fall f = FlightGuard.fall(30);
                if (f.impact() >= 0 && f.fallAtImpact() > 3.5 && reachTick(f, f.impact()) < 0) {
                    if (!holdSwapLogged) Recorder.log("EMACE", String.format(Locale.ROOT, "holding the swap: the fall would land in %d ticks before reaching the target, pulling out", f.impact()));
                    holdSwapLogged = true;
                    offsetCheck = 0;
                    setPhase(Phase.Approach, null);
                    approach();
                    return;
                }
            }
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
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
        dev.bali.util.ClickGate.hold();
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
        float[] r = RotationUtil.rotationsTo(eye, aim);
        GrimBoost.face(r[0], r[1], RotationPriority.ELYTRA_MACE, null);

        if (phaseTicks > 25 || mc.player.isOnGround() || mc.player.getY() < target.getY() - 2) {
            setPhase(Phase.Recover, String.format(Locale.ROOT, "missed: dist=%.2f after %d ticks", dist, phaseTicks));
            return;
        }
        if (avoid.on() && !mc.player.isOnGround()) {
            FlightGuard.Fall f = FlightGuard.fall(30);
            int bail = GrimBoost.rocketAttached() ? 6 : 9;
            if (f.impact() >= 0 && f.impact() <= bail && f.fallAtImpact() > 3.5 && reachTick(f, f.impact()) < 0) {
                emergencyRocket();
                setPhase(Phase.Recover, String.format(Locale.ROOT, "bail: ground in %d ticks (fall %.1f), target out of reach at %.2f", f.impact(), f.fallAtImpact(), dist));
                bailOut();
            }
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

    private Vec3d steer(Vec3d want, double distToGoal) {
        double look = Math.min(MathHelper.clamp(want.length() * 8, 1.5, 14), distToGoal + 1.5);
        Vec3d got = FlightGuard.steer(want, look, true, steerPrev);
        if (got != want) {
            if (!avoiding) Recorder.log("EMACE", String.format(Locale.ROOT, "avoid: blocked ahead, steering (%.2f %.2f %.2f) -> (%.2f %.2f %.2f)",
                want.x, want.y, want.z, got.x, got.y, got.z));
            avoiding = true;
            steerPrev = got;
        } else {
            avoiding = false;
            steerPrev = null;
        }
        return got;
    }

    private Vec3d diveAim() {
        return intercept(mc.player.getEyePos(), new Vec3d(0, target.getHeight() * 0.7, 0), speed.getD() / 20);
    }

    private Box diveTargetBox() {
        Vec3d aim = diveAim();
        return target.getBoundingBox().offset(aim.subtract(target.getEntityPos().add(0, target.getHeight() * 0.7, 0)));
    }

    private boolean strikePathClear(Box box, Vec3d eye, Vec3d aim, Box targetBox) {
        Vec3d d = aim.subtract(eye);
        double len = d.length();
        if (len < 1e-6) return true;
        Vec3d u = d.multiply(1 / len);
        Vec3d standEye = new Vec3d(box.getCenter().x, box.minY + mc.player.getEyeHeight(net.minecraft.entity.EntityPose.STANDING), box.getCenter().z);
        double strikeAt = reach.getD() - 0.2, need = len;
        for (double t = 0; t <= len; t += 0.25) {
            Vec3d e = standEye.add(u.multiply(t));
            if (e.distanceTo(RotationUtil.closestPoint(targetBox, e)) <= strikeAt) {
                need = t;
                break;
            }
        }
        return FlightGuard.clear(box, u, need);
    }

    private Vec3d diveOffset() {
        if (!avoid.on() || target == null) return Vec3d.ZERO;
        if (offsetCheck-- > 0) return diveOffset;
        offsetCheck = 5;
        if (diveLineClear(Vec3d.ZERO)) return pickOffset(Vec3d.ZERO);
        if (diveOffset.lengthSquared() > 0 && diveLineClear(diveOffset)) return pickOffset(diveOffset);
        Vec3d me = mc.player.getEntityPos().subtract(target.getEntityPos());
        double start = Math.atan2(me.z, me.x);
        for (double r : new double[]{4, 8, 12}) {
            for (int k = 0; k < 8; k++) {
                double a = start + Math.PI / 4 * ((k + 1) / 2) * (k % 2 == 0 ? 1 : -1);
                Vec3d off = new Vec3d(Math.cos(a) * r, 0, Math.sin(a) * r);
                if (diveLineClear(off)) return pickOffset(off);
            }
        }
        if (!covered) Recorder.log("EMACE", "avoid: no clear dive line onto the target (covered), waiting above it");
        covered = true;
        diveOffset = Vec3d.ZERO;
        return diveOffset;
    }

    private Vec3d pickOffset(Vec3d off) {
        if (off.squaredDistanceTo(diveOffset) > 1e-6 || covered) {
            Recorder.log("EMACE", String.format(Locale.ROOT, "avoid: diving from %.0f,%.0f off the target", off.x, off.z));
        }
        covered = false;
        diveOffset = off;
        return off;
    }

    private boolean diveLineClear(Vec3d off) {
        Vec3d feet = target.getEntityPos();
        Vec3d eye = feet.add(off.x, height.getD(), off.z);
        eye = new Vec3d(eye.x, Math.min(eye.y, maxY()), eye.z);
        Box box = FlightGuard.boxAtEye(eye);
        if (!mc.world.isSpaceEmpty(mc.player, box)) return false;
        return strikePathClear(box, eye, feet.add(0, target.getHeight() * 0.7, 0), target.getBoundingBox());
    }

    private int reachTick(FlightGuard.Fall f, int before) {
        double eyeH = mc.player.getEyeHeight(net.minecraft.entity.EntityPose.STANDING);
        Vec3d tv = targetVelocity(target);
        if (target.isOnGround()) tv = new Vec3d(tv.x, 0, tv.z);
        Box tb = target.getBoundingBox();
        for (int t = 0; t < Math.min(before, f.path().size()); t++) {
            Vec3d eye = f.path().get(t).add(0, eyeH, 0);
            Box at = tb.offset(tv.multiply(t));
            if (eye.distanceTo(RotationUtil.closestPoint(at, eye)) <= reach.getD()) return t;
        }
        return -1;
    }

    private void bailOut() {
        recover();
        if (!MaceUtil.wearingElytra() || mc.player.isGliding() || mc.player.isOnGround()) return;
        dev.bali.util.ClickGate.cancelHold();
        setJump(true);
        Vec3d v = mc.player.getVelocity();
        float yaw = v.horizontalLengthSquared() > 0.01 ? GrimBoost.lookAlong(v)[0] : mc.player.getYaw();
        float pitch = GrimBoost.glidePitch(v, yaw, 0);
        float[] safe = FlightGuard.safeGlide(yaw, pitch, 8);
        if (safe != null) {
            yaw = safe[0];
            pitch = safe[1];
        }
        GrimBoost.face(yaw, pitch, RotationPriority.ELYTRA_MACE, needRocket(GrimBoost.rocketAttached()) ? this::fireRocket : null);
    }

    private void emergencyRocket() {
        if (rocketTimer > 0 && rocketDelay.getI() - rocketTimer >= 6) rocketTimer = 0;
    }

    private LivingEntity pickTarget() {
        double maxSq = targetRange.getD() * targetRange.getD();
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
            if (raw.lengthSquared() > 16) raw = trackedVel;
            trackedVel = trackedVel.multiply(0.5).add(raw.multiply(0.5));
        }
        trackedPrev = pos;
    }

    private Vec3d targetVelocity(Entity t) {
        return t == tracked ? trackedVel : new Vec3d(t.getX() - t.lastX, t.getY() - t.lastY, t.getZ() - t.lastZ);
    }

    private Vec3d predict(Entity t, double ticks) {
        Vec3d v = targetVelocity(t);
        if (t.isOnGround()) v = new Vec3d(v.x, 0, v.z);
        return t.getEntityPos().add(v.multiply(ticks));
    }

    private int interceptTicks;

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

    private static final class ElytraBack {
        private int ticks;

        @EventHandler
        private void onTick(Events.TickPre e) {
            if (mc.player == null || MaceUtil.wearingElytra() || Bali.MODULES.get(ElytraMace.class).isEnabled()) {
                Bali.EVENTS.unsubscribe(this);
                return;
            }
            dev.bali.util.ClickGate.hold();
            if ((dev.bali.util.ClickGate.ready() || ++ticks > 5) && MaceUtil.equipChest(MaceUtil.findElytra())) Bali.EVENTS.unsubscribe(this);
        }
    }

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
