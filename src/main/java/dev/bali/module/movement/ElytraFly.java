package dev.bali.module.movement;

import dev.bali.manager.RotationPriority;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.module.client.Rotations;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.GrimBoost;
import dev.bali.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * Bounce: the 2b2t highway method. Jump is held to re-jump on landing; in the air the elytra is
 *   opened by releasing and re-pressing jump so vanilla sends START_FALL_FLYING itself. Sending it
 *   without a fresh jump press got rejected by Grim every time (2b2t recording, 156/156 setbacks).
 *   Only sprints on the ground. Hold W, use AutoWalk, or turn on Auto Forward.
 *
 *   Bounce speed comes from the sprint jump (+0.2 b/t) and is lost to drag: 1% per gliding tick
 *   and 9% in the one tick after each jump where the elytra is closed (the server closes it on
 *   landing; reopening needs a released then a pressed jump). A 2b2t recording settled at 22.6 b/s,
 *   exactly what that cycle gives at a flat pitch of 80. Two Phase pitches 89 while rising (the
 *   hop stays low and short) and 30 while falling (the drop is turned into forward speed):
 *   27.6 b/s in a simulation of the vanilla physics, 12-tick hops, under 1 block high.
 * Boost: rocket flight tuned to what Grim's simulation accepts (see {@link GrimBoost}).
 *   While a rocket is attached Grim does not simulate the boost; it accepts any velocity between
 *   the plain glide result and a per-axis box of 1.7 b/t in the look direction. So during a burn
 *   we take the box corner along our heading (34 b/s on an axis, 48 b/s on a diagonal, instantly)
 *   and can climb at up to 1.7 b/t at almost no cost in speed. Between rockets it is ordinary
 *   elytra physics: a silent pitch holds altitude and spends the banked height, and the next
 *   rocket fires when we are back at cruise height and slow.
 * Pitch40: rocket-free cruising between two heights, with a silent pitch. Dive at +40 from the top
 *   bound to the bottom one, then climb at -40: vanilla's climb turns horizontal speed into 3.2x as
 *   much lift, so a cycle at least ~40 blocks tall climbs back to the top on its own. Simulated with
 *   vanilla's glide physics: 26-28 b/s indefinitely without a rocket (20-block bounds decay).
 *   The bounds are synced to your height when the glide starts and re-armed after setbacks or if
 *   a climb stalls short of the top. A rocket is only fired at such a stall, the slowest point of
 *   the cycle, where Grim's firework box adds the most speed, and the burn climbs back to the top.
 */
public final class ElytraFly extends Module {
    public enum Mode { Bounce, Boost, Pitch40 }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.Bounce, "Flight mode");
    public enum PitchLock { Silent, Client, Off }

    private final EnumSetting<PitchLock> pitchLock = mode("Pitch Lock", PitchLock.Silent, "Silent = server-side only, your camera stays free (Bounce)").visibleWhen(() -> is(Mode.Bounce));
    private final NumberSetting pitch = number("Pitch", 80, -90, 90, 0.5, "Locked pitch (Bounce, when Two Phase is off)").visibleWhen(() -> is(Mode.Bounce) && (this.twoPhase == null || !this.twoPhase.on()));
    private final BoolSetting twoPhase = bool("Two Phase", true, "Pitch 89 while rising, Fall Pitch while falling: about 15% faster bounce (Silent pitch lock)").visibleWhen(() -> is(Mode.Bounce));
    private final NumberSetting fallPitch = number("Fall Pitch", 30, 0, 89, 1, "Pitch on the way down with Two Phase (25-35 is best)").visibleWhen(() -> is(Mode.Bounce) && twoPhase.on());
    private final BoolSetting autoForward = bool("Auto Forward", false, "Fly forward without holding W");
    private final BoolSetting yawLock = bool("Yaw Lock", false, "Fly at the nearest 45 degrees (highways); silent, your camera stays free");
    private final BoolSetting sprintFix = bool("Sprint Fix", true, "Only sprint on the ground (anticheat friendly)").visibleWhen(() -> is(Mode.Bounce));
    private final BoolSetting noJumpDelay = bool("No Jump Delay", false, "Re-jump the tick you land (faster, less vanilla)").visibleWhen(() -> is(Mode.Bounce));
    private final NumberSetting restartDelay = number("Restart Delay", 3, 0, 20, 1, "Ticks to wait after a setback before re-opening").visibleWhen(() -> is(Mode.Bounce));
    private final NumberSetting p40Climb = number("Climb Pitch", -40, -60, -20, 1, "Pitch on the way up (Pitch40)").visibleWhen(() -> is(Mode.Pitch40));
    private final NumberSetting p40Dive = number("Dive Pitch", 40, 20, 60, 1, "Pitch on the way down (Pitch40)").visibleWhen(() -> is(Mode.Pitch40));
    private final NumberSetting p40Bounds = number("Bounds", 50, 20, 120, 1, "Height between the top and bottom bound; under ~40 the cycle can't climb back on its own (Pitch40)").visibleWhen(() -> is(Mode.Pitch40));
    private final NumberSetting p40Rate = number("Pitch Speed", 4, 1, 20, 0.5, "Degrees per tick when turning between climb and dive; 4 is fastest (Pitch40)").visibleWhen(() -> is(Mode.Pitch40));
    private final BoolSetting p40Rockets = bool("Stall Rockets", true, "Fire a rocket only once stalled climbs have sagged the bounds more than Stall Drop; off = just let the bounds sag (Pitch40)").visibleWhen(() -> is(Mode.Pitch40));
    private final BoolSetting p40Kick = bool("Kickstart", true, "One rocket when a glide starts slow (under 15 b/s): the cycle otherwise takes minutes to build up to full speed (Pitch40)").visibleWhen(() -> is(Mode.Pitch40));
    private final NumberSetting p40Drop = number("Stall Drop", 10, 0, 60, 1, "Blocks the cycle may sag below the armed height before a stall rocket climbs back (Pitch40)").visibleWhen(() -> is(Mode.Pitch40) && p40Rockets.on());
    private final BoolSetting autoOpen = bool("Auto Open", true, "Open the elytra when falling (Boost, Pitch40)").visibleWhen(() -> !is(Mode.Bounce));
    private final NumberSetting minSpeed = number("Rocket Below", 20, 0, 60, 1, "Fire a rocket below this speed in blocks/s, 0 = off (Boost)").visibleWhen(() -> is(Mode.Boost));
    private final NumberSetting rocketDelay = number("Rocket Delay", 20, 5, 100, 1, "Min ticks between rockets (Boost, Pitch40)").visibleWhen(() -> !is(Mode.Bounce));
    private final BoolSetting grimBoost = bool("Grim Boost", true, "Take the full speed Grim accepts while a rocket burns (48 b/s on diagonals)").visibleWhen(() -> !is(Mode.Bounce));
    private final NumberSetting boostSpeed = number("Boost Speed", 60, 10, 60, 1, "Speed cap during a burn in blocks/s; Grim's own limit applies below it").visibleWhen(() -> !is(Mode.Bounce) && grimBoost.on());
    private final NumberSetting rocketClimb = number("Rocket Climb", 32, 0, 32, 1, "Climb this fast (blocks/s) during a burn, then glide the height off: far fewer rockets. 0 = stay level").visibleWhen(() -> !is(Mode.Bounce) && grimBoost.on());
    private final NumberSetting ceiling = number("Ceiling", 250, 0, 600, 5, "Rocket Climb (and the Pitch40 top bound) stops at this Y (118 under the nether roof)").visibleWhen(() -> !is(Mode.Bounce) && grimBoost.on());
    private final BoolSetting levelGlide = bool("Level Glide", true, "Between rockets a silent pitch holds your altitude; off = your camera pitch flies").visibleWhen(() -> is(Mode.Boost));

    private int rocketTimer;
    private boolean holdingJump;
    private volatile boolean rubberbanded;
    private int restartTimer;
    private boolean lastGround, lastGlide;

    // Boost state
    private double cruiseY = Double.NaN;
    private volatile int setbackPause;

    // Pitch40 state
    /** Current top bound, and the height it was armed at (a stall rocket climbs back there). */
    private double p40Top = Double.NaN, p40Home;
    private boolean p40Diving, p40Rose, p40Kickstart;
    private float p40Pitch;
    private int p40Stalls, p40StallRockets;
    /** Last silent rotation we sent while gliding; held through an ElytraRecast reopen tick. */
    private float lastFaceYaw, lastFacePitch = Float.NaN;

    public ElytraFly() {
        super("ElytraFly", "Elytra flight helpers", Category.MOVEMENT);
    }

    private boolean is(Mode m) { return mode != null && mode.is(m); }

    @Override
    public String getInfo() {
        if (is(Mode.Pitch40) && !Double.isNaN(p40Top)) return String.format(Locale.ROOT, "Pitch40 %.0f-%.0f", p40Top - p40Bounds.getD(), p40Top);
        return mode.get().name();
    }

    /** Pitch while on the ground or rising with Two Phase. Not 90: vanilla's cosine table can make the look's horizontal part point backwards there. */
    private static final float RISE_PITCH = 89f;

    @Override
    protected void onDisable() {
        releaseForward();
        releaseJump();
        rubberbanded = false;
        cruiseY = Double.NaN;
        p40Top = Double.NaN;
        lastFacePitch = Float.NaN;
    }

    @Override
    protected void onEnable() {
        p40Stalls = p40StallRockets = 0;
    }

    private boolean wearingElytra() {
        // ElytraRecast has the chestplate on for a moment; the client is still gliding
        if (Bali.MODULES.get(ElytraRecast.class).busy()) return true;
        var chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return chest.contains(DataComponentTypes.GLIDER) && chest.getDamage() < chest.getMaxDamage() - 1;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (rocketTimer > 0) rocketTimer--;
        if (!wearingElytra() || mc.player.hasVehicle() || mc.player.getAbilities().flying
            || Bali.MODULES.get(RocketControl.class).steering() || Bali.MODULES.get(dev.bali.module.combat.ElytraMace.class).busy()) {
            releaseForward();
            releaseJump();
            return;
        }
        if (Bali.MODULES.get(ElytraRecast.class).reopening()) {
            // the glide stopped for a moment so the elytra takes no damage; it reopens this tick.
            // Keep the flight's rotation and state rather than treating it as a landing.
            if (!mode.is(Mode.Bounce) && !Float.isNaN(lastFacePitch)) face(lastFaceYaw, lastFacePitch, null);
            return;
        }
        // only while we are actually flying: a broken elytra must not keep walking you forward
        if (autoForward.on()) {
            mc.options.forwardKey.setPressed(true);
            holdingForward = true;
        } else {
            releaseForward();
        }
        if (mode.is(Mode.Bounce)) bounce();
        else if (mode.is(Mode.Boost)) boost();
        else pitch40();

        if (Recorder.active() && (lastGround != mc.player.isOnGround() || lastGlide != mc.player.isGliding())) {
            Recorder.log("EFLY", String.format(Locale.ROOT, "ground %b->%b glide %b->%b speed=%.2f b/s vy=%.3f sprint=%b",
                lastGround, mc.player.isOnGround(), lastGlide, mc.player.isGliding(),
                mc.player.getVelocity().horizontalLength() * 20, mc.player.getVelocity().y, mc.player.isSprinting()));
        }
        lastGround = mc.player.isOnGround();
        lastGlide = mc.player.isGliding();
    }

    private void bounce() {
        if (!mc.player.input.hasForwardMovement() || mc.player.isTouchingWater() || mc.player.isInLava()) {
            releaseJump();
            return;
        }
        float yaw = lockedYaw();
        switch (pitchLock.get()) {
            // Sent only to the server; the elytra move-fix makes our physics use it too, so the
            // camera can look anywhere while Grim sees (and simulates) the locked rotation.
            case Silent -> Bali.ROTATIONS.rotate(yaw, bouncePitch(), RotationPriority.ELYTRA_FLY, null);
            case Client -> {
                mc.player.setPitch(pitch.getF());
                mc.player.setYaw(yaw);
            }
            case Off -> { if (yawLock.on()) mc.player.setYaw(yaw); }
        }

        if (sprintFix.on()) mc.player.setSprinting(mc.player.isOnGround() || !mc.player.isGliding());
        else mc.player.setSprinting(true);
        if (noJumpDelay.on()) mc.player.jumpingCooldown = 0;

        if (rubberbanded) {
            setJump(false); // don't reopen while the server is still correcting us
            if (restartTimer-- > 0) return;
            rubberbanded = false;
            Recorder.log("EFLY", "restarting after setback");
        }

        if (mc.player.isOnGround() || mc.player.isGliding()) {
            // hold jump: re-jump the moment we land
            setJump(true);
        } else {
            // airborne with the elytra closed. Vanilla (and Grim) only open it on a FRESH jump press,
            // so release for one tick, then press: vanilla sends START_FALL_FLYING with matching input.
            setJump(!jumpDown);
            if (jumpDown) Recorder.log("EFLY", String.format(Locale.ROOT, "jump press to open elytra at y=%.2f vy=%.3f", mc.player.getY(), mc.player.getVelocity().y));
        }
    }

    /** On the ground (the jump tick) and rising: steep; falling: Fall Pitch. Velocity is read before this tick's move, as the physics will use it. */
    private float bouncePitch() {
        if (!twoPhase.on()) return pitch.getF();
        return mc.player.isOnGround() || mc.player.getVelocity().y > 0 ? RISE_PITCH : fallPitch.getF();
    }

    private float lockedYaw() {
        return yawLock.on() ? Math.round(mc.player.getYaw() / 45f) * 45f : mc.player.getYaw();
    }

    // ------------------------------------------------------------------ boost

    private void boost() {
        openWhenFalling();
        if (!mc.player.isGliding()) {
            cruiseY = Double.NaN;
            return;
        }
        double y = mc.player.getY();
        if (Double.isNaN(cruiseY)) cruiseY = y;
        if (setbackPause > 0) { // let the server's correction settle with plain vanilla flight
            setbackPause--;
            return;
        }
        boolean up = mc.options.jumpKey.isPressed() && !holdingJump;
        boolean down = mc.options.sneakKey.isPressed();
        if (up || down) cruiseY = y; // steering by hand moves the cruise height with us

        Vec3d vel = mc.player.getVelocity();
        float yaw = lockedYaw();
        boolean rocket = GrimBoost.rocketAttached();
        boolean needRocket = !rocket && rocketTimer <= 0 && minSpeed.getD() > 0
            && (up || (vel.horizontalLength() * 20 < minSpeed.getD() && (!levelGlide.on() || y <= cruiseY + 0.5)));
        Runnable fire = needRocket ? this::fireRocket : null;

        if (rocket && grimBoost.on()) {
            double climb = rocketClimb.getD() / 20;
            double vy;
            if (up) vy = Math.max(climb, 0.15);
            else if (down) vy = -Math.max(climb, 0.15);
            else vy = MathHelper.clamp(maxY() - y, 0, climb);
            GrimBoost.request(vy, boostSpeed.getD() / 20);
            face(yaw, GrimBoost.burnPitch(vy), fire);
        } else if (levelGlide.on()) {
            double excess = y - cruiseY;
            double wantVy = down ? -0.6 : excess > 0.5 ? -Math.min(0.5, excess / 40) : 0;
            face(yaw, GrimBoost.glidePitch(vel, yaw, wantVy), fire);
        } else if (yawLock.on()) {
            face(yaw, mc.player.getPitch(), fire);
        } else if (fire != null) {
            fire.run();
        }
    }

    // ------------------------------------------------------------------ pitch 40

    private void pitch40() {
        openWhenFalling();
        if (!mc.player.isGliding()) {
            p40Top = Double.NaN;
            return;
        }
        double y = mc.player.getY();
        Vec3d vel = mc.player.getVelocity();
        if (Double.isNaN(p40Top)) {
            syncBounds("glide start");
            p40Kickstart = p40Kick.on() && vel.horizontalLength() * 20 < 15;
        }
        if (setbackPause > 0) {
            setbackPause--;
            if (setbackPause == 0) syncBounds("after setback");
            return;
        }
        // jump / sneak move the bounds with you
        if (mc.options.jumpKey.isPressed() && !holdingJump) p40Home = p40Top = Math.min(maxY(), p40Top + 0.5);
        if (mc.options.sneakKey.isPressed()) p40Home = p40Top -= 0.5;

        float yaw = lockedYaw();
        boolean rocket = GrimBoost.rocketAttached();
        Runnable fire = null;
        float target;
        if (rocket) {
            // burn: climb back to the top bound, then dive again
            if (grimBoost.on()) {
                double vy = MathHelper.clamp(p40Top - y, 0, rocketClimb.getD() / 20);
                GrimBoost.request(vy, boostSpeed.getD() / 20);
                target = GrimBoost.burnPitch(vy);
                p40Pitch = target; // the box follows the look: no easing during a burn
            } else {
                target = p40Climb.getF(); // the vanilla push follows the look
            }
            p40Diving = y >= p40Top - 1;
            p40Rose = false;
        } else {
            if (p40Kickstart && rocketTimer <= 0 && !Bali.MODULES.get(ElytraRecast.class).busy()) {
                p40Kickstart = false;
                if (InvUtil.count(Items.FIREWORK_ROCKET) > 0) {
                    fire = this::fireRocket;
                    Recorder.log("EFLY", String.format(Locale.ROOT, "pitch40 kickstart rocket at %.1f b/s", vel.horizontalLength() * 20));
                }
            }
            if (p40Diving) {
                if (y <= p40Top - p40Bounds.getD()) {
                    p40Diving = false;
                    p40Rose = false;
                }
            } else {
                if (vel.y > 0.05) p40Rose = true;
                if (y >= p40Top) {
                    p40Diving = true;
                } else if (p40Rose && vel.y <= 0) {
                    // the climb ran out of speed short of the top
                    p40Diving = true;
                    p40Stalls++;
                    if (Bali.MODULES.get(ElytraRecast.class).busy()) {
                        // mid-recast the server isn't gliding and won't use a rocket: dive, the next stall fires it
                    } else if (p40Rockets.on() && y < p40Home - p40Drop.getD() && rocketTimer <= 0 && InvUtil.count(Items.FIREWORK_ROCKET) > 0) {
                        fire = this::fireRocket; // slowest point: a burn gains the most here
                        p40StallRockets++;
                        Recorder.log("EFLY", String.format(Locale.ROOT, "pitch40 stall at y=%.1f (armed %.1f): rocket back up", y, p40Home));
                        p40Top = p40Home;
                    } else {
                        Recorder.log("EFLY", String.format(Locale.ROOT, "pitch40 stall at y=%.1f: bounds re-armed %.1f -> %.1f", y, p40Top, y));
                        p40Top = y;
                    }
                }
            }
            target = p40Diving ? p40Dive.getF() : p40Climb.getF();
        }
        float rate = p40Rate.getF();
        p40Pitch = p40Pitch > target ? Math.max(p40Pitch - rate, target) : Math.min(p40Pitch + rate, target);
        face(yaw, p40Pitch, fire);
    }

    /** Top bound = where we are now (under the ceiling); start with the dive. */
    private void syncBounds(String why) {
        p40Home = p40Top = Math.min(mc.player.getY(), maxY());
        p40Diving = true;
        p40Rose = false;
        p40Pitch = Float.isNaN(lastFacePitch) ? mc.player.getPitch() : lastFacePitch;
        Recorder.log("EFLY", String.format(Locale.ROOT, "pitch40 bounds %.1f..%.1f (%s)", p40Top - p40Bounds.getD(), p40Top, why));
    }

    /** Stall counters since enabling (stalls, rockets fired at stalls), for tests. */
    public int[] pitch40Stats() { return new int[]{p40Stalls, p40StallRockets}; }

    private void openWhenFalling() {
        if (autoOpen.on() && !mc.player.isOnGround() && !mc.player.isGliding() && mc.player.getVelocity().y < -0.1 && !mc.player.isTouchingWater()) {
            setJump(!jumpDown); // fresh press opens the elytra the vanilla way
        } else {
            releaseJump();
        }
    }

    private double maxY() {
        double c = ceiling.getD();
        if (mc.world.getDimension().hasCeiling() && mc.player.getY() < 127) c = Math.min(c, 118);
        return c;
    }

    /** Silent rotation with the move-fix (our physics use it too); without move-fix, a real one. */
    private void face(float yaw, float pitch, Runnable afterSend) {
        lastFaceYaw = yaw;
        lastFacePitch = pitch;
        if (Bali.MODULES.get(Rotations.class).moveFix.on()) {
            Bali.ROTATIONS.rotate(yaw, pitch, RotationPriority.ELYTRA_FLY, afterSend);
        } else {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            if (afterSend != null) afterSend.run();
        }
    }

    private void fireRocket() {
        // mid-recast the server has already stopped our glide and would not use the rocket
        if (rocketTimer > 0 || Bali.MODULES.get(ElytraRecast.class).busy()) return;
        Runnable use = () -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        int hot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
        if (hot != -1) {
            InvUtil.silentSwap(hot, use);
        } else {
            int inv = InvUtil.canClickInventory() ? InvUtil.findInventory(Items.FIREWORK_ROCKET) : -1;
            if (inv == -1) return;
            if (!InvUtil.invSwap(inv, use)) return; // keys released for the click (no effect while gliding); fires next tick
        }
        rocketTimer = rocketDelay.getI();
        Recorder.log("EFLY", String.format(Locale.ROOT, "rocket at y=%.1f cruise=%.1f speed=%.1f b/s",
            mc.player.getY(), cruiseY, mc.player.getVelocity().horizontalLength() * 20));
    }

    /** True while we fly rockets ourselves: the vanilla client-side rocket push must not also apply. */
    public boolean ownsRocketBoost() {
        return isEnabled() && !mode.is(Mode.Bounce) && grimBoost.on() && setbackPause == 0 && mc.player != null && mc.player.isGliding();
    }

    // ------------------------------------------------------------------ shared

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!(e.packet instanceof PlayerPositionLookS2CPacket)) return;
        if (!is(Mode.Bounce)) {
            setbackPause = 20;
            Recorder.log("EFLY", "setback during boost flight");
            return;
        }
        rubberbanded = true;
        restartTimer = restartDelay.getI();
        mc.execute(() -> {
            if (mc.player != null && mc.player.isGliding()) mc.player.stopGliding();
        });
    }

    private boolean jumpDown;
    private boolean holdingForward;

    /** Gives W back to the keyboard: pressed only if you are physically holding it. */
    private void releaseForward() {
        if (!holdingForward) return;
        holdingForward = false;
        if (mc.options == null || mc.getWindow() == null) return;
        var key = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(mc.options.forwardKey);
        boolean physical = key.getCategory() == net.minecraft.client.util.InputUtil.Type.KEYSYM
            && net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
        mc.options.forwardKey.setPressed(physical);
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
}
