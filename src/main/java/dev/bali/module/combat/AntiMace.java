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
import dev.bali.setting.NumberSetting;
import dev.bali.util.ChatUtil;
import dev.bali.util.EntityUtil;
import dev.bali.util.GrimBoost;
import dev.bali.util.InvUtil;
import dev.bali.util.MaceUtil;
import dev.bali.util.Render3D;
import dev.bali.util.RotationUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Defends against mace smashes (elytra dive-bombs, wind-charge drops, MaceDtap-style chains).
 *
 * A threat is an enemy player holding a mace, above us, coming down, whose fall will be past the
 * 1.5 blocks a smash needs by the time their eye is within reach of us. When one is due within
 * {@code React} ticks:
 * - Shield: raise a shield (offhand, or the hotbar) and keep our sent yaw on them. A shield takes 5
 *   ticks to come up, then blocks the whole smash (mace_smash is not in bypasses_shield) when the
 *   attacker is within 90 degrees of our head yaw, horizontally.
 * - Chestplate: standing in an elytra, swap a chestplate in (an elytra has no armor).
 * - Dodge: while gliding, burn a rocket sideways out of their dive line (Grim's box, see GrimBoost).
 */
public final class AntiMace extends Module {
    private final NumberSetting range = number("Range", 32, 8, 64, 1, "Watch players within this distance");
    private final NumberSetting react = number("React", 12, 5, 30, 1, "Respond when a smash could land within this many ticks (a shield needs 5 plus your ping)");
    private final BoolSetting requireMace = bool("Require Mace", true, "Only mace users: holding one, or seen holding or smashing with one in the last 5 minutes (off: anyone diving at you)");
    private final BoolSetting shield = bool("Shield", true, "Raise a shield facing the attacker");
    private final BoolSetting chestplate = bool("Chestplate", true, "Standing in an elytra: swap a chestplate in");
    private final BoolSetting dodge = bool("Dodge", true, "Gliding: rocket sideways out of the dive");
    private final BoolSetting alert = bool("Alert", true, "Chat warning when someone dives at you");
    private final ColorSetting color = color("Color", 0x60FF2020, "Attacker highlight");

    private static final class Track {
        Vec3d prev, vel = Vec3d.ZERO;
        double fall;
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    /**
     * Entity id -> until when we count them as a mace user. Divers usually hold a totem and swap to
     * the mace only for the hit, so holding one right now misses them; a smash they land (on us or
     * anyone we can see) gives them away. Written from the network thread.
     */
    private final Map<Integer, Long> maceUsers = new ConcurrentHashMap<>();
    private static final long MACE_MEMORY_MS = 5 * 60_000;
    private PlayerEntity threat;
    private double threatEta;
    private boolean shielding, alerted;
    private int restoreSlot = -1, calmTicks, rocketTimer;

    public AntiMace() {
        super("AntiMace", "Shields, armors up or dodges when someone mace-dives at you", Category.COMBAT);
    }

    @Override
    public String getInfo() { return threat == null ? null : String.format(Locale.ROOT, "%s %.0ft", threat.getGameProfile().name(), threatEta); }

    /** The attacker we are reacting to, or null. */
    public PlayerEntity threat() { return isEnabled() ? threat : null; }

    @Override
    protected void onDisable() {
        lowerShield();
        tracks.clear();
        threat = null;
    }

    // runs after the other modules so a dodge overrides their flight request this tick
    @EventHandler(priority = -100)
    private void onTick(Events.TickPre e) {
        if (nullCheck()) return;
        if (rocketTimer > 0) rocketTimer--;
        updateTracks();
        PlayerEntity t = findThreat();
        if (t != null) {
            calmTicks = 0;
            if (threat != t) {
                alerted = false;
                Recorder.log("ANTIMACE", String.format(Locale.ROOT, "threat %s eta=%.1f fall=%.1f vy=%.2f above=%.1f",
                    t.getGameProfile().name(), threatEta, tracks.get(t.getId()).fall, tracks.get(t.getId()).vel.y, t.getY() - mc.player.getY()));
            }
            threat = t;
            if (alert.on() && !alerted) {
                ChatUtil.info(String.format(Locale.ROOT, "AntiMace: %s is mace-diving at you", t.getGameProfile().name()));
                alerted = true;
            }
            respond(t);
        } else if (threat != null && ++calmTicks > 8) {
            threat = null;
            lowerShield();
        } else if (threat != null && shielding) {
            faceAttacker(threat); // keep the shield on them for a few ticks after they land or hit
        }
    }

    // ------------------------------------------------------------------ detection

    private void updateTracks() {
        Map<Integer, Track> seen = new HashMap<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Track tr = tracks.getOrDefault(p.getId(), new Track());
            Vec3d pos = p.getEntityPos();
            if (tr.prev != null) {
                Vec3d raw = pos.subtract(tr.prev);
                if (raw.lengthSquared() > 25) raw = tr.vel; // teleport
                tr.vel = tr.vel.multiply(0.4).add(raw.multiply(0.6));
                // the fall the server is building for them: down moves add, up moves and landing reset
                if (p.isOnGround() || raw.y > 0.01) tr.fall = 0;
                else if (raw.y < 0) tr.fall -= raw.y;
            }
            tr.prev = pos;
            seen.put(p.getId(), tr);
            isMaceUser(p); // remembers anyone we catch holding one, even for a tick
        }
        tracks.clear();
        tracks.putAll(seen);
    }

    private boolean isMaceUser(PlayerEntity p) {
        if (MaceUtil.isMace(p.getMainHandStack()) || MaceUtil.isMace(p.getOffHandStack())) {
            maceUsers.put(p.getId(), System.currentTimeMillis() + MACE_MEMORY_MS);
            return true;
        }
        Long until = maceUsers.get(p.getId());
        return until != null && until > System.currentTimeMillis();
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (e.packet instanceof EntityDamageS2CPacket d && d.sourceType().matchesKey(DamageTypes.MACE_SMASH)
                && d.sourceCauseId() >= 0 && (mc.player == null || d.sourceCauseId() != mc.player.getId())) {
            maceUsers.put(d.sourceCauseId(), System.currentTimeMillis() + MACE_MEMORY_MS);
        }
    }

    @EventHandler
    private void onJoin(Events.Join e) { maceUsers.clear(); } // entity ids are per server

    private PlayerEntity findThreat() {
        PlayerEntity best = null;
        double bestEta = react.getD();
        double rangeSq = range.getD() * range.getD();
        Box me = mc.player.getBoundingBox();
        Vec3d myVel = mc.player.getVelocity();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !EntityUtil.isAliveTarget(p) || EntityUtil.isFriend(p)) continue;
            if (mc.player.squaredDistanceTo(p) > rangeSq) continue;
            if (requireMace.on() && !isMaceUser(p)) continue;
            Track tr = tracks.get(p.getId());
            if (tr == null || p.getY() < mc.player.getY() + 0.5 || tr.vel.y > -0.15) continue;

            Vec3d eye = p.getEyePos();
            Vec3d closest = RotationUtil.closestPoint(me, eye);
            double dist = eye.distanceTo(closest);
            Vec3d toMe = closest.subtract(eye);
            double closing = toMe.lengthSquared() < 1e-6 ? -tr.vel.y : tr.vel.subtract(myVel).dotProduct(toMe.normalize());
            double eta;
            if (dist <= 3.2) eta = 0;
            else if (closing < 0.05) continue;
            else eta = (dist - 3.2) / closing;
            if (tr.fall + Math.max(0, -tr.vel.y) * eta <= 1.5) continue; // not a smash yet
            if (eta < bestEta) {
                bestEta = eta;
                best = p;
            }
        }
        threatEta = bestEta;
        return best;
    }

    // ------------------------------------------------------------------ responses

    private void respond(PlayerEntity t) {
        ElytraMace em = Bali.MODULES.get(ElytraMace.class);
        boolean gliding = mc.player.isGliding();

        if (chestplate.on() && !gliding && !em.busy() && MaceUtil.wearingElytra()
            && !Bali.MODULES.get(dev.bali.module.movement.ElytraRecast.class).busy()) {
            int chest = MaceUtil.findChestplate();
            if (chest != -1 && dev.bali.util.ClickGate.tryClick() && MaceUtil.equipChest(chest)) Recorder.log("ANTIMACE", "chestplate in");
        }

        if (gliding) {
            if (dodge.on() && !em.busy()) dodgeFrom(t);
            return; // can't block while gliding
        }
        if (shield.on()) raiseShield(t);
    }

    private void raiseShield(PlayerEntity t) {
        faceAttacker(t);
        if (mc.player.isUsingItem() && mc.player.getActiveItem().isOf(Items.SHIELD)) {
            mc.options.useKey.setPressed(true); // keep holding it
            return;
        }
        Hand hand;
        if (mc.player.getOffHandStack().isOf(Items.SHIELD)) {
            hand = Hand.OFF_HAND;
        } else {
            int slot = InvUtil.findHotbar(Items.SHIELD);
            if (slot == -1) return;
            if (restoreSlot == -1) restoreSlot = InvUtil.selected();
            InvUtil.select(slot);
            hand = Hand.MAIN_HAND;
        }
        if (mc.player.isUsingItem()) mc.interactionManager.stopUsingItem(mc.player); // eating etc.
        mc.interactionManager.interactItem(mc.player, hand);
        mc.options.useKey.setPressed(true);
        shielding = true;
        Recorder.log("ANTIMACE", "shield up (" + hand + ")");
    }

    /** Server yaw on the attacker: the shield blocks within 90 degrees of the head yaw, horizontally. */
    private void faceAttacker(PlayerEntity t) {
        float yaw = RotationUtil.rotationsTo(mc.player.getEyePos(), t.getEyePos())[0];
        Vec3d d = t.getEntityPos().subtract(mc.player.getEntityPos());
        if (d.horizontalLengthSquared() < 0.01) yaw = mc.player.getYaw(); // straight above: any yaw blocks
        Bali.ROTATIONS.rotate(yaw, mc.player.getPitch(), RotationPriority.ANTI_MACE, null);
    }

    private void lowerShield() {
        if (shielding && mc.player != null) {
            mc.options.useKey.setPressed(false);
            if (mc.player.isUsingItem() && mc.player.getActiveItem().isOf(Items.SHIELD)) mc.interactionManager.stopUsingItem(mc.player);
            Recorder.log("ANTIMACE", "shield down");
        }
        shielding = false;
        if (restoreSlot != -1 && mc.player != null) InvUtil.select(restoreSlot);
        restoreSlot = -1;
    }

    private void dodgeFrom(PlayerEntity t) {
        Track tr = tracks.get(t.getId());
        // sideways to their approach, away from the side they are on
        Vec3d approach = tr != null && tr.vel.horizontalLengthSquared() > 0.01 ? tr.vel : mc.player.getEntityPos().subtract(t.getEntityPos());
        Vec3d side = new Vec3d(-approach.z, 0, approach.x);
        if (side.lengthSquared() < 1e-6) side = new Vec3d(1, 0, 0);
        side = side.normalize();
        Vec3d offset = mc.player.getEntityPos().subtract(t.getEntityPos());
        if (side.dotProduct(offset) < 0) side = side.multiply(-1);
        Vec3d want = side.multiply(1.7).add(0, -0.3, 0);
        float[] r = GrimBoost.lookAlong(want);
        if (GrimBoost.rocketAttached()) {
            GrimBoost.requestVelocity(want);
            GrimBoost.face(r[0], r[1], RotationPriority.ANTI_MACE, null);
        } else if (rocketTimer <= 0) {
            rocketTimer = 10;
            GrimBoost.face(r[0], r[1], RotationPriority.ANTI_MACE, GrimBoost::fireRocket);
        }
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (threat == null) return;
        Vec3d p = threat.getLerpedPos(e.tickDelta);
        Box b = threat.getBoundingBox().offset(p.subtract(threat.getEntityPos()));
        Render3D.box(b, color.argb(), color.withAlpha(255), 2f);
    }
}
