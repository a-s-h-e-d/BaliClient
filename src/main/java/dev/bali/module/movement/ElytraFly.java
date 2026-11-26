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
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

public final class ElytraFly extends Module {
    public enum Mode { Bounce, Boost, Pitch40, Packet }

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
    private final NumberSetting packetSpeed = number("Speed", 40, 1, 100, 1, "Horizontal speed in blocks/s (Packet; not Grim-safe)").visibleWhen(() -> is(Mode.Packet));
    private final NumberSetting packetVertical = number("Vertical Speed", 20, 1, 60, 1, "Up (jump) and down (sneak) speed in blocks/s (Packet)").visibleWhen(() -> is(Mode.Packet));
    private final NumberSetting packetFall = number("Fall Speed", 0, 0, 5, 0.1, "Sink this fast in blocks/s when not pressing jump or sneak; 0 = hover (Packet)").visibleWhen(() -> is(Mode.Packet));

    private int rocketTimer;
    private boolean holdingJump;
    private volatile boolean rubberbanded;
    private int restartTimer;
    private boolean lastGround, lastGlide;

    private double cruiseY = Double.NaN;
    private volatile int setbackPause;

    private double p40Top = Double.NaN, p40Home;
    private boolean p40Diving, p40Rose, p40Kickstart;
    private float p40Pitch;
    private int p40Stalls, p40StallRockets;
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

    private static final float RISE_PITCH = 89f;

    @Override
    protected void onDisable() {
        releaseForward();
        releaseJump();
        rubberbanded = false;
        cruiseY = Double.NaN;
        p40Top = Double.NaN;
        lastFacePitch = Float.NaN;
        packetMove = null;
        packetOpenWait = 0;
    }

    @Override
    protected void onEnable() {
        p40Stalls = p40StallRockets = 0;
    }

    private boolean wearingElytra() {
        if (Bali.MODULES.get(ElytraRecast.class).busy()) return true;
        var chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        return chest.contains(DataComponentTypes.GLIDER) && chest.getDamage() < chest.getMaxDamage() - 1;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        packetMove = null;
        if (rocketTimer > 0) rocketTimer--;
        if (!wearingElytra() || mc.player.hasVehicle() || mc.player.getAbilities().flying
            || Bali.MODULES.get(RocketControl.class).steering() || Bali.MODULES.get(dev.bali.module.combat.ElytraMace.class).busy()) {
            releaseForward();
            releaseJump();
            return;
        }
        if (Bali.MODULES.get(ElytraRecast.class).reopening()) {
            if (!mode.is(Mode.Bounce) && !Float.isNaN(lastFacePitch)) face(lastFaceYaw, lastFacePitch, null);
            return;
        }
        if (autoForward.on()) {
            mc.options.forwardKey.setPressed(true);
            holdingForward = true;
        } else {
            releaseForward();
        }
        if (mode.is(Mode.Bounce)) bounce();
        else if (mode.is(Mode.Boost)) boost();
        else if (mode.is(Mode.Packet)) packet();
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
            setJump(false);
            if (restartTimer-- > 0) return;
            rubberbanded = false;
            Recorder.log("EFLY", "restarting after setback");
        }

        if (mc.player.isOnGround() || mc.player.isGliding()) {
            setJump(true);
        } else {
            setJump(!jumpDown);
            if (jumpDown) Recorder.log("EFLY", String.format(Locale.ROOT, "jump press to open elytra at y=%.2f vy=%.3f", mc.player.getY(), mc.player.getVelocity().y));
        }
    }

    private float bouncePitch() {
        if (!twoPhase.on()) return pitch.getF();
        return mc.player.isOnGround() || mc.player.getVelocity().y > 0 ? RISE_PITCH : fallPitch.getF();
    }

    private float lockedYaw() {
        return yawLock.on() ? Math.round(mc.player.getYaw() / 45f) * 45f : mc.player.getYaw();
    }

    private void boost() {
        openWhenFalling();
        if (!mc.player.isGliding()) {
            cruiseY = Double.NaN;
            return;
        }
        double y = mc.player.getY();
        if (Double.isNaN(cruiseY)) cruiseY = y;
        if (setbackPause > 0) {
            setbackPause--;
            return;
        }
        boolean up = mc.options.jumpKey.isPressed() && !holdingJump;
        boolean down = mc.options.sneakKey.isPressed();
        if (up || down) cruiseY = y;

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

    private Vec3d packetMove;
    private int packetOpenWait;

    private void packet() {
        packetMove = null;
        if (packetOpenWait > 0) packetOpenWait--;
        if (mc.player.isOnGround() || mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isClimbing()) return;
        if (setbackPause > 0) {
            setbackPause--;
            return;
        }
        if (!mc.player.isGliding()) {
            if (packetOpenWait > 0) return;
            if (!mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().stretch(0, -0.5, 0))) return;
            if (mc.player.checkGliding()) {
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                Recorder.log("EFLY", String.format(Locale.ROOT, "packet open at y=%.2f", mc.player.getY()));
            }
            packetOpenWait = 5;
            if (!mc.player.isGliding()) return;
        }

        int f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        int s = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double h = 0, x = 0, z = 0;
        if (f != 0 || s != 0) {
            float yaw = lockedYaw() + (float) Math.toDegrees(Math.atan2(-s, f));
            h = packetSpeed.getD() / 20;
            x = -MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE) * h;
            z = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE) * h;
        }
        boolean up = mc.options.jumpKey.isPressed(), down = mc.options.sneakKey.isPressed();
        double y = up == down ? -packetFall.getD() / 20 : (up ? 1 : -1) * packetVertical.getD() / 20;
        packetMove = new Vec3d(x, y, z);
        mc.player.setVelocity(packetMove);
    }

    @EventHandler
    private void onMove(Events.Move e) {
        if (packetMove == null || e.type != net.minecraft.entity.MovementType.SELF || !is(Mode.Packet)) return;
        if (!mc.player.isGliding()) return;
        e.movement = packetMove;
        mc.player.setVelocity(packetMove);
    }

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
        if (mc.options.jumpKey.isPressed() && !holdingJump) p40Home = p40Top = Math.min(maxY(), p40Top + 0.5);
        if (mc.options.sneakKey.isPressed()) p40Home = p40Top -= 0.5;

        float yaw = lockedYaw();
        boolean rocket = GrimBoost.rocketAttached();
        Runnable fire = null;
        float target;
        if (rocket) {
            if (grimBoost.on()) {
                double vy = MathHelper.clamp(p40Top - y, 0, rocketClimb.getD() / 20);
                GrimBoost.request(vy, boostSpeed.getD() / 20);
                target = GrimBoost.burnPitch(vy);
                p40Pitch = target;
            } else {
                target = p40Climb.getF();
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
                    p40Diving = true;
                    p40Stalls++;
                    if (Bali.MODULES.get(ElytraRecast.class).busy()) {
                    } else if (p40Rockets.on() && y < p40Home - p40Drop.getD() && rocketTimer <= 0 && InvUtil.count(Items.FIREWORK_ROCKET) > 0) {
                        fire = this::fireRocket;
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

    private void syncBounds(String why) {
        p40Home = p40Top = Math.min(mc.player.getY(), maxY());
        p40Diving = true;
        p40Rose = false;
        p40Pitch = Float.isNaN(lastFacePitch) ? mc.player.getPitch() : lastFacePitch;
        Recorder.log("EFLY", String.format(Locale.ROOT, "pitch40 bounds %.1f..%.1f (%s)", p40Top - p40Bounds.getD(), p40Top, why));
    }

    public int[] pitch40Stats() { return new int[]{p40Stalls, p40StallRockets}; }

    private void openWhenFalling() {
        if (autoOpen.on() && !mc.player.isOnGround() && !mc.player.isGliding() && mc.player.getVelocity().y < -0.1 && !mc.player.isTouchingWater()) {
            setJump(!jumpDown);
        } else {
            releaseJump();
        }
    }

    private double maxY() {
        double c = ceiling.getD();
        if (mc.world.getDimension().hasCeiling() && mc.player.getY() < 127) c = Math.min(c, 118);
        return c;
    }

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
        if (rocketTimer > 0 || Bali.MODULES.get(ElytraRecast.class).busy()) return;
        Runnable use = () -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        int hot = InvUtil.findHotbar(Items.FIREWORK_ROCKET);
        if (hot != -1) {
            InvUtil.silentSwap(hot, use);
        } else {
            int inv = InvUtil.canClickInventory() ? InvUtil.findInventory(Items.FIREWORK_ROCKET) : -1;
            if (inv == -1) return;
            if (!InvUtil.invSwap(inv, use)) return;
        }
        rocketTimer = rocketDelay.getI();
        Recorder.log("EFLY", String.format(Locale.ROOT, "rocket at y=%.1f cruise=%.1f speed=%.1f b/s",
            mc.player.getY(), cruiseY, mc.player.getVelocity().horizontalLength() * 20));
    }

    public boolean ownsRocketBoost() {
        return isEnabled() && !mode.is(Mode.Bounce) && !mode.is(Mode.Packet) && grimBoost.on() && setbackPause == 0 && mc.player != null && mc.player.isGliding();
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!(e.packet instanceof PlayerPositionLookS2CPacket)) return;
        if (is(Mode.Packet)) {
            setbackPause = 5;
            Recorder.log("EFLY", "setback during packet flight");
            return;
        }
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
