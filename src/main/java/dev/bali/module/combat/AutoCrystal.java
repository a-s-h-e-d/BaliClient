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
import dev.bali.util.BlockUtil;
import dev.bali.util.DamageUtil;
import dev.bali.util.EntityUtil;
import dev.bali.util.InvUtil;
import dev.bali.util.Render3D;
import dev.bali.util.RotationUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * AutoCrystal: damage-based place + break built around what Grim checks (read from its source).
 *
 * Aim Lock. Grim validates an attack against the rotation of the last movement packet (Reach /
 * Hitboxes: the look ray must enter the crystal's 2x2x2 box within 3 blocks), and a placement
 * against a look ray that enters the clicked block (RotationPlace). Neither ray is stopped by
 * blocks. So for every place spot we look for a click point whose ray also runs on into the box
 * of the crystal that will stand there, and hold that one rotation. Then the break needs no turn:
 * it can go out the moment Grim knows the crystal, off the tick, and the next crystal can be
 * placed in the same breath. 2b2t recordings showed the old alternating rotations getting 26-80%
 * of instant attacks dropped (every dropped one was sent while not looking at the crystal).
 *
 * Instant break. Grim only accepts attacks on entities it knows the client has, which it confirms
 * with a ping/pong after the spawn; we attack right after our pong. An attack is only ever sent
 * when the server-side rotation already hits the crystal, because one bad hit makes Grim cancel
 * the following ones.
 *
 * Moving. The rotation goes out with this tick's movement packet, so Grim tests it from where we
 * will be after the move. Aims are therefore worked out from the predicted eye position (this
 * tick's position plus last tick's displacement), and lock points are preferred where the ray
 * passes well inside the crystal's box.
 *
 * Packet order. With the aim already held, clicks are sent at the start of the tick, before the
 * movement packet, as vanilla does. Per tick at most one crystal is attacked and one spot clicked
 * (Grim: MultiInteractA, MultiPlace).
 */
public final class AutoCrystal extends Module {
    public enum Swap { None, Normal, Silent }
    public enum Instant { Off, Pong, Packet }

    // targeting
    private final NumberSetting targetRange = number("Target Range", 12, 4, 20, 0.5, "Max distance to targets");
    // place
    private final BoolSetting place = bool("Place", true, "Place crystals");
    private final NumberSetting placeRange = number("Place Range", 4.5, 1, 6, 0.1, "Eye-to-block reach for placing");
    private final NumberSetting placeDelay = number("Place Delay", 0, 0, 20, 1, "Ticks between places");
    private final EnumSetting<Swap> swap = mode("Swap", Swap.Silent, "How to get crystals in hand");
    // break
    private final BoolSetting doBreak = bool("Break", true, "Break crystals");
    private final NumberSetting breakRange = number("Break Range", 3.0, 1, 6, 0.05, "Eye-to-hitbox reach for breaking (survival 3.0)");
    private final NumberSetting breakDelay = number("Break Delay", 0, 0, 20, 1, "Ticks between breaks");
    private final EnumSetting<Instant> instant = mode("Instant", Instant.Pong, "Pong = right after Grim confirms the spawn; Packet = on spawn (Grim drops it)");
    private final BoolSetting instantPlace = bool("Instant Place", true, "Place the next crystal right after an instant break instead of waiting for the tick");
    private final BoolSetting combo = bool("Break+Place", true, "Break and re-place in the same tick when one rotation covers both");
    private final BoolSetting setDead = bool("Set Dead", false, "Remove broken crystals client-side immediately");
    private final BoolSetting hurtSync = bool("Hurt Sync", true, "Hold the break while the target is in its 10-tick hit cooldown (crystals then only add the difference), so the crystal goes off the moment it ends");
    // damage
    private final NumberSetting minDamage = number("Min Damage", 6, 0, 36, 0.5, "Min damage to the target");
    private final NumberSetting maxSelf = number("Max Self", 8, 0, 36, 0.5, "Max damage to yourself");
    private final BoolSetting antiSuicide = bool("Anti Suicide", true, "Never take lethal damage");
    private final NumberSetting facePlace = number("Face Place HP", 8, 0, 36, 0.5, "Ignore min damage below this target health");
    private final NumberSetting armorBreak = number("Armor Break %", 15, 0, 100, 1, "Face place when target armor is below this durability");
    // rotations
    private final BoolSetting rotate = bool("Rotate", true, "Silent rotations (needed on Grim)");
    private final BoolSetting aimLock = bool("Aim Lock", true, "Hold one rotation that is valid for both the place and the break, so instant breaks are never dropped");
    private final BoolSetting strict = bool("Strict Direction", true, "Only click visible faces (Grim)");
    // misc
    private final BoolSetting pauseEating = bool("Pause Eating", true, "Pause while eating");
    private final BoolSetting lootSafe = bool("Loot Safe", true, "Stop at the killing blow and turn off when a player dies nearby, so no crystal blows up the drops");
    private final BoolSetting render = bool("Render", true, "Show the place position");
    private final ColorSetting color = color("Color", 0x502BB5C9, "Render color");

    private int placeTimer, breakTimer;
    private PlayerEntity target;
    private BlockPos renderPos;
    private float renderDamage;
    private long renderTime;

    private final Map<BlockPos, Long> placed = new ConcurrentHashMap<>();
    private final Map<Integer, Long> attacked = new ConcurrentHashMap<>();
    /** Spots we placed on whose crystal hasn't spawned yet (don't send duplicate places). */
    private final Map<BlockPos, Long> awaitingSpawn = new ConcurrentHashMap<>();
    /** crystals whose spawn arrived but no ping has followed yet. */
    private final ConcurrentLinkedQueue<Integer> awaitingPing = new ConcurrentLinkedQueue<>();
    /** ping parameter -> crystals to hit once we've answered that ping. */
    private final Map<Integer, List<Integer>> afterPong = new ConcurrentHashMap<>();
    /** Where each spawned crystal stands (the entity may not be in the client world yet). */
    private final Map<Integer, Vec3d> spawnPos = new ConcurrentHashMap<>();
    /** Crystals the server has told us about, by the block they stand in: id and when (the entity reaches the client world a moment later). */
    private final Map<BlockPos, long[]> known = new ConcurrentHashMap<>();
    /** Measured place -> spawn round trip in ms (2b2t hides tab ping). */
    private volatile double rtt = 80;

    /** The spot we are working this tick and how to click it, for the off-tick instant place. */
    private PlaceResult current;
    /** Last tick's spot: it keeps a small edge so equal spots don't make the aim flip back and forth. */
    private BlockPos lastBase;
    private Hand currentHand = Hand.MAIN_HAND;
    private int currentSlot = -1;
    // what went out since the last tick-end packet (Grim counts per client tick)
    private int tickAttackId = -1;
    private PlaceResult tickPlace;

    public AutoCrystal() {
        super("AutoCrystal", "Places and breaks end crystals", Category.COMBAT);
    }

    @Override
    public String getInfo() {
        return target == null ? null : target.getGameProfile().name() + " " + String.format("%.1f", renderDamage);
    }

    public PlayerEntity target() { return isEnabled() ? target : null; }

    /** Last time a place or attack went out. */
    private volatile long lastActionMs;

    /**
     * True while AutoCrystal is fighting: it has a spot to work (placing, or holding one for Hurt
     * Sync) or acted in the last 300 ms. KillAura stays out of the way then (no sword swap, no hit:
     * Grim allows one entity per tick and a hit would reset the attack cooldown). When AutoCrystal
     * has a target but nothing it can place, it is not busy and KillAura may hit.
     */
    public boolean busy() {
        return isEnabled() && target != null && (current != null || System.currentTimeMillis() - lastActionMs < 300);
    }

    @Override
    protected void onDisable() {
        target = null;
        renderPos = null;
        current = null;
        placed.clear();
        attacked.clear();
        awaitingSpawn.clear();
        awaitingPing.clear();
        afterPong.clear();
        spawnPos.clear();
        known.clear();
        killLockUntil = 0;
    }

    // ------------------------------------------------------------------ loot safe

    /**
     * Loot Safe. Drops appear the moment the target dies on the server, but we only hear about it
     * half a round trip later, and by then the next crystal is already placed (Instant Place goes
     * out right behind the killing attack) and would be broken on spawn, right on the loot.
     *
     * So when an attack is about to send a crystal that does lethal damage, nothing else goes out
     * until the server answers: a pop means they survived and we carry on, a death turns
     * AutoCrystal off. If a crystal we did not see as lethal kills anyway, the death status still
     * reaches us before the spawn of anything placed behind it (the server handles our packets in
     * order), so we are off before we would break it; that crystal is left standing near the drops.
     */
    private volatile long killLockUntil;
    /** Where the lethal crystal stands. */
    private volatile Vec3d killLockPos;

    private boolean killLocked() { return System.currentTimeMillis() < killLockUntil; }

    /**
     * Would this crystal kill a target? Only someone with no totem in either hand can die (their held
     * items are visible to us), so ordinary pops cost nothing. Our break lands at least a hit cooldown
     * after their last pop, and by then the empty hand of a player who ran out has reached us.
     */
    private boolean lethal(Vec3d crystalPos) {
        for (PlayerEntity t : mc.world.getPlayers()) {
            if (!EntityUtil.isAliveTarget(t) || t.squaredDistanceTo(crystalPos) > 12 * 12) continue;
            if (t.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || t.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) continue;
            if (t.hurtTime * 50.0 > rtt + 50) continue; // lands well inside its cooldown: only the difference applies
            if (DamageUtil.crystalDamage(t, crystalPos) >= DamageUtil.health(t)) return true;
        }
        return false;
    }

    @EventHandler
    private void onPop(Events.TotemPop e) {
        if (e.player != mc.player) killLockUntil = 0; // they survived: back to work
    }

    @EventHandler
    private void onDeath(Events.Death e) {
        if (!lootSafe.on() || e.player == mc.player || mc.player == null) return;
        if (e.player.squaredDistanceTo(mc.player) > targetRange.getD() * targetRange.getD()) return;
        Vec3d at = e.player.getEntityPos();
        int standing = 0;
        for (Entity ent : mc.world.getEntities()) if (ent instanceof EndCrystalEntity c && c.isAlive() && c.squaredDistanceTo(at) < 12 * 12) standing++;
        String name = e.player.getGameProfile().name();
        dbg("loot safe: " + name + " died, turning off (" + standing + " crystals still standing near the drops)");
        dev.bali.util.ChatUtil.info("AutoCrystal off: " + name + " died, protecting the loot"
            + (standing > 0 ? ". " + standing + (standing == 1 ? " crystal is" : " crystals are") + " still standing near it, don't hit " + (standing == 1 ? "it" : "them") : ""));
        Bali.MODULES.get(dev.bali.module.client.Notifications.class).alert(net.minecraft.util.Formatting.YELLOW + "AutoCrystal off"
            + net.minecraft.util.Formatting.GRAY + ": protecting " + name + "'s loot", false);
        setEnabled(false);
    }

    // ------------------------------------------------------------------ main loop

    @EventHandler(priority = 50)
    private void onTick(Events.TickPre e) {
        if (placeTimer > 0) placeTimer--;
        if (breakTimer > 0) breakTimer--;
        long now = System.currentTimeMillis();
        placed.values().removeIf(t -> now - t > 1000);
        // If an attack didn't kill the crystal (dropped, lag), retry after ~one round trip.
        long retry = (long) Math.max(50, rtt * 1.5 + 30);
        attacked.values().removeIf(t -> now - t > retry);
        if (spawnPos.size() > 64) spawnPos.clear();
        // a destroy we never saw (out of range, dimension change) must not block a spot forever
        known.values().removeIf(k -> now - k[1] > 1000 && mc.world.getEntityById((int) k[0]) == null);

        current = null;
        List<PlayerEntity> targets = targets();
        target = targets.isEmpty() ? null : targets.get(0);
        if (targets.isEmpty()) return;
        if (killLocked()) {
            dbg("loot safe: lethal crystal sent, waiting for pop or death");
            return;
        }
        if (pauseEating.on() && ((mc.player.isUsingItem() && !mc.player.getActiveItem().isOf(Items.END_CRYSTAL)) || autoEatEating())) {
            dbg("paused: eating");
            return;
        }

        boolean hold = holdForCooldown();
        if (hold) dbg(String.format(Locale.ROOT, "hold break: %s hurtTime=%d rtt=%.0f", target.getGameProfile().name(), target.hurtTime, rtt));
        EndCrystalEntity crystal = doBreak.on() && breakTimer <= 0 && !hold ? bestCrystal(targets) : null;
        PlaceResult best = place.on() ? bestPlace(targets, crystal) : null;
        Hand hand = mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) ? Hand.OFF_HAND : Hand.MAIN_HAND;
        int slot = hand == Hand.MAIN_HAND && !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL) ? InvUtil.findHotbar(Items.END_CRYSTAL) : InvUtil.selected();
        boolean haveCrystals = !(hand == Hand.MAIN_HAND && (slot == -1 || slot != InvUtil.selected() && swap.is(Swap.None)));
        boolean canPlace = best != null && haveCrystals && !best.pending && placeTimer <= 0;
        lastBase = best == null ? null : best.base;
        if (best != null && haveCrystals) {
            current = best;
            currentHand = hand;
            currentSlot = slot;
        }

        Vec3d eye = aimEye();
        if (crystal != null) {
            Box box = crystalBox(crystal.getEntityPos());
            // One rotation for both: the click ray of our spot runs on into this crystal's box.
            boolean together = combo.on() && canPlace && looksAt(eye, best.hitVec.subtract(eye), box, breakRange.getD());
            Vec3d aim;
            if (together) aim = best.hitVec;
            else if (serverLooksAt(eye, box, breakRange.getD()) && serverLooksAt(mc.player.getEyePos(), box, breakRange.getD())) aim = null; // already on it: don't move
            else aim = crystalAim(eye, box);
            dbg(String.format(Locale.ROOT, "BREAK%s id=%d at %s dmg=%.1f self=%.1f rtt=%.0f",
                together ? "+PLACE" : "", crystal.getId(), crystal.getBlockPos().toShortString(),
                DamageUtil.crystalDamage(target, crystal.getEntityPos()), DamageUtil.crystalDamage(mc.player, crystal.getEntityPos()), rtt));
            Runnable act = together
                ? () -> { attackCrystal(crystal.getId(), crystal, crystal.getEntityPos()); placeCrystal(best, hand, slot); }
                : () -> attackCrystal(crystal.getId(), crystal, crystal.getEntityPos());
            run(aim, together ? best : null, box, act);
            breakTimer = breakDelay.getI();
            if (together) {
                markRender(best, now);
                placeTimer = placeDelay.getI();
            }
            return; // one rotation per tick
        }

        if (!place.on()) return;
        if (best == null) {
            dbg(String.format(Locale.ROOT, "no place: target=%s hp=%.1f need=%.1f at=%.1f,%.1f,%.1f dist=%.1f bestLow=%.1f | bases=%d occupied=%d blocked=%d noFace=%d range=%d selfDmg=%d lowDmg=%d",
                target.getGameProfile().name(), DamageUtil.health(target), requiredDamage(target), target.getX(), target.getY(), target.getZ(),
                target.distanceTo(mc.player), cBestLow, cBases, cOccupied, cEntity, cFace, cRange, cSelf, cLow));
            return;
        }
        if (!haveCrystals) {
            dbg(slot == -1 ? "no place: no crystals in hotbar/offhand" : "no place: swap is None and not holding crystals");
            return;
        }
        if (!canPlace) {
            // waiting for our crystal to appear (or the place delay): keep looking at the spot so the break is instant
            if (rotate.on()) aimAt(best.hitVec, null);
            return;
        }
        markRender(best, now);
        dbg(String.format(Locale.ROOT, "PLACE %s side=%s dmg=%.1f lock=%b hand=%s slot=%d", best.base.toShortString(), best.side, best.damage, best.locked, hand, slot));
        run(best.hitVec, best, null, () -> placeCrystal(best, hand, slot));
        placeTimer = placeDelay.getI();
    }

    /**
     * Performs {@code act} with the rotation it needs. If the server already has a rotation that is
     * valid for it, the packets go out now (start of tick, before the movement packet, like vanilla)
     * and the aim is just held; otherwise we turn first and act right after the movement packet.
     *
     * @param aim       where to look, or null to keep the current server rotation
     * @param placing   spot being clicked, or null
     * @param attacking box of the crystal being attacked, or null
     */
    private void run(Vec3d aim, PlaceResult placing, Box attacking, Runnable act) {
        if (!rotate.on()) {
            act.run();
            return;
        }
        Vec3d eye = mc.player.getEyePos();
        boolean ready = (attacking == null || serverLooksAt(eye, attacking, breakRange.getD()))
            && (placing == null || serverLooksAt(eye, new Box(placing.base), placeRange.getD()));
        if (ready) {
            act.run();
            // an unrequested tick would send the camera rotation again: keep holding the aim
            if (aim != null) aimAt(aim, null);
            else Bali.ROTATIONS.rotate(Bali.ROTATIONS.serverYaw(), Bali.ROTATIONS.serverPitch(), RotationPriority.AUTO_CRYSTAL, null);
        } else if (aim != null) {
            aimAt(aim, act);
        }
    }

    private void markRender(PlaceResult r, long now) {
        renderPos = r.base;
        renderDamage = r.damage;
        renderTime = now;
    }

    private static boolean autoEatEating() {
        return Bali.MODULES.get(dev.bali.module.player.AutoEat.class).isEating();
    }

    /**
     * Hurt Sync. After a hit the server ignores damage for 10 ticks unless it is bigger than that
     * hit, and then only applies the difference, so a crystal broken in that window is mostly wasted.
     * The client sees the hit half a round trip late (hurtTime 10 counting down) and our attack lands
     * half a round trip after we send it, so the break may go out once hurtTime * 50 ms <= rtt. One
     * tick of margin on top: early costs one crystal (the cycle goes on), late costs damage.
     */
    private boolean holdForCooldown() {
        return hurtSync.on() && target != null && target.hurtTime > 0 && target.hurtTime * 50.0 > rtt + 50;
    }

    private static void dbg(String msg) {
        Recorder.log("AC", msg);
    }

    // ------------------------------------------------------------------ look checks (what Grim tests)

    /** Where our eyes will be when the server evaluates the rotation we send this tick. */
    private Vec3d aimEye() {
        double dx = mc.player.getX() - mc.player.lastX, dy = mc.player.getY() - mc.player.lastY, dz = mc.player.getZ() - mc.player.lastZ;
        if (dx * dx + dy * dy + dz * dz > 4) return mc.player.getEyePos(); // teleport, not movement
        return mc.player.getEyePos().add(dx, mc.player.isOnGround() ? 0 : dy, dz);
    }

    /** Requests the rotation that looks at {@code point} from the predicted eye position. */
    private void aimAt(Vec3d point, Runnable afterSend) {
        float[] r = RotationUtil.rotationsTo(aimEye(), point);
        Bali.ROTATIONS.rotate(r[0], r[1], RotationPriority.AUTO_CRYSTAL, afterSend);
    }

    /** Grim's box for an end crystal: 2x2x2 from its feet. Shrunk a hair so we never sit on the edge. */
    private static Box crystalBox(Vec3d pos) {
        return new Box(pos.x - 1, pos.y, pos.z - 1, pos.x + 1, pos.y + 2, pos.z + 1).contract(0.02);
    }

    private static boolean looksAt(Vec3d eye, Vec3d dir, Box box, double reach) {
        double d = RotationUtil.rayBox(eye, dir, box);
        return d >= 0 && d <= reach - 0.02;
    }

    /** True if the rotation the server has right now points into {@code box} within {@code reach}. */
    private boolean serverLooksAt(Vec3d eye, Box box, double reach) {
        double d = RotationUtil.rayBox(eye, Bali.ROTATIONS.serverYaw(), Bali.ROTATIONS.serverPitch(), box);
        return d >= 0 && d <= reach - 0.02;
    }

    /** Point to look at to hit a crystal: the closest point of its box, pulled slightly inside. */
    private static Vec3d crystalAim(Vec3d eye, Box box) {
        return RotationUtil.closestPoint(box.contract(0.05), eye);
    }

    // ------------------------------------------------------------------ actions

    private void placeCrystal(PlaceResult r, Hand hand, int slot) {
        if (tickPlace != null && !tickPlace.sameClick(r)) return; // one click target per tick
        if (dev.bali.util.GrimTick.otherClicked()) return; // Surround & co. clicked in this Grim tick
        if (killLocked()) return; // Loot Safe: nothing goes down behind a lethal crystal
        BlockHitResult hit = new BlockHitResult(r.hitVec, r.side, r.base, false);
        Runnable click = () -> {
            var result = mc.interactionManager.interactBlock(mc.player, hand, hit);
            mc.player.swingHand(hand);
            long t = System.currentTimeMillis();
            placed.put(r.base.up(), t);
            lastActionMs = t;
            awaitingSpawn.put(r.base.up(), t);
            tickPlace = r;
            dbg(String.format(Locale.ROOT, "placed -> %s (server rot %.1f,%.1f)", result.getClass().getSimpleName(),
                Bali.ROTATIONS.serverYaw(), Bali.ROTATIONS.serverPitch()));
        };
        if (hand == Hand.OFF_HAND || slot == InvUtil.selected()) click.run();
        else if (swap.is(Swap.Silent)) InvUtil.silentSwap(slot, click);
        else {
            InvUtil.select(slot);
            click.run();
        }
    }

    /**
     * Sends the attack directly; usable off the tick (instant break).
     *
     * @return false if it was not sent (already attacked, a different crystal was hit this tick,
     *         or the server-side rotation does not point at it)
     */
    private boolean attackCrystal(int id, Entity entity, Vec3d pos) {
        if (attacked.containsKey(id) || mc.getNetworkHandler() == null) return false;
        if (killLocked()) return false; // Loot Safe: waiting to hear whether the last one killed
        if (tickAttackId != -1 && tickAttackId != id) return false; // Grim: one entity per tick
        if (dev.bali.util.GrimTick.otherClicked()) return false; // Surround & co. clicked in this Grim tick
        if (rotate.on() && !serverLooksAt(mc.player.getEyePos(), crystalBox(pos), breakRange.getD())) {
            dbg("attack id=" + id + " skipped: server rotation is not on the crystal");
            return false;
        }
        if (lootSafe.on() && lethal(pos)) {
            // the answer (pop or death) is back within a round trip; the cap only covers a missed packet
            killLockUntil = System.currentTimeMillis() + (long) Math.max(400, rtt * 3 + 100);
            killLockPos = pos;
            dbg("loot safe: lethal crystal id=" + id + ", holding everything until pop or death");
        }
        attacked.put(id, System.currentTimeMillis());
        lastActionMs = System.currentTimeMillis();
        tickAttackId = id;
        Entity e = entity;
        if (e == null) {
            e = new EndCrystalEntity(mc.world, 0, 0, 0);
            e.setId(id);
        }
        dbg("attack id=" + id + (entity == null ? " (entity not in client world)" : ""));
        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(e, mc.player.isSneaking()));
        mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        if (setDead.on() && entity != null) {
            Entity finalE = entity;
            mc.execute(() -> { if (mc.world != null) mc.world.removeEntity(finalE.getId(), Entity.RemovalReason.KILLED); });
        }
        return true;
    }

    // ------------------------------------------------------------------ instant break

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (mc.player == null) return;
        // The server sends a pop or death status while it processes the explosion, before the explosion
        // packet. So our lethal crystal's explosion arriving with no pop or death ahead of it means the
        // target lived (hit cooldown, absorption we could not see): release the lock. One tick of
        // slack so a status handled on the main thread right behind this still lands first.
        if (e.packet instanceof net.minecraft.network.packet.s2c.play.ExplosionS2CPacket ex) {
            Vec3d lp = killLockPos;
            if (lp != null && killLocked() && ex.center().squaredDistanceTo(lp) < 1) killLockUntil = Math.min(killLockUntil, System.currentTimeMillis() + 50);
            return;
        }
        if (e.packet instanceof CommonPingS2CPacket ping) {
            // everything that spawned before this ping is known to Grim once we answer it
            if (awaitingPing.isEmpty()) return;
            List<Integer> ids = new ArrayList<>();
            Integer id;
            while ((id = awaitingPing.poll()) != null) ids.add(id);
            afterPong.put(ping.getParameter(), ids);
            return;
        }
        if (e.packet instanceof EntitiesDestroyS2CPacket d) {
            if (!known.isEmpty()) known.values().removeIf(k -> d.getEntityIds().contains((int) k[0]));
            return;
        }
        if (!(e.packet instanceof EntitySpawnS2CPacket p) || p.getEntityType() != EntityType.END_CRYSTAL) return;
        BlockPos pos = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
        known.put(pos, new long[]{p.getEntityId(), System.currentTimeMillis()});
        awaitingSpawn.remove(pos);
        Long placedAt = placed.get(pos);
        if (placedAt == null) return;
        double sample = System.currentTimeMillis() - placedAt;
        if (sample > 0 && sample < 1000) rtt = rtt * 0.7 + sample * 0.3;
        if (instant.is(Instant.Off) || !doBreak.on()) return;

        Vec3d crystalPos = new Vec3d(p.getX(), p.getY(), p.getZ());
        Vec3d eye = mc.player.getEyePos();
        double dist = eye.distanceTo(RotationUtil.closestPoint(crystalBox(crystalPos), eye));
        if (dist > breakRange.getD()) return;
        if (target != null && !selfSafe(DamageUtil.crystalDamage(mc.player, crystalPos))) return;
        spawnPos.put(p.getEntityId(), crystalPos);

        if (instant.is(Instant.Packet)) {
            dbg(String.format(Locale.ROOT, "INSTANT(packet) id=%d dist=%.2f", p.getEntityId(), dist));
            int id = p.getEntityId();
            mc.send(() -> instantBreak(id, "packet"));
        } else {
            awaitingPing.add(p.getEntityId());
        }
    }

    @EventHandler
    private void onSend(Events.PacketSend e) {
        if (e.packet instanceof ClientTickEndC2SPacket) {
            tickAttackId = -1;
            tickPlace = null;
            return;
        }
        if (!(e.packet instanceof CommonPongC2SPacket pong)) return;
        List<Integer> ids = afterPong.remove(pong.getParameter());
        if (ids == null) return;
        // queued, so it runs after this pong has actually been sent
        mc.send(() -> {
            for (int id : ids) instantBreak(id, "pong");
        });
    }

    /** Main thread, off the tick: break our crystal and, with Instant Place, put the next one down. */
    private void instantBreak(int id, String how) {
        if (mc.world == null || mc.player == null || !isEnabled()) return;
        Vec3d pos = spawnPos.remove(id);
        Entity ent = mc.world.getEntityById(id);
        if (pos == null || ent != null && !ent.isAlive()) return;
        if (holdForCooldown()) return; // the tick loop breaks it when the cooldown is about to end
        if (!attackCrystal(id, ent, pos)) return;
        dbg("INSTANT(" + how + ") id=" + id);

        PlaceResult r = current;
        if (!instantPlace.on() || !place.on() || r == null || placeDelay.getI() > 0) return;
        if (!r.base.up().equals(BlockPos.ofFloored(pos))) return;
        // crystals must already be in hand: a slot switch here is not worth the risk
        boolean inHand = currentHand == Hand.OFF_HAND ? mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) : mc.player.getMainHandStack().isOf(Items.END_CRYSTAL);
        if (!inHand) return;
        if (rotate.on() && !serverLooksAt(mc.player.getEyePos(), new Box(r.base), placeRange.getD())) return;
        if (!selfSafe(DamageUtil.crystalDamage(mc.player, pos))) return;
        dbg("INSTANT PLACE " + r.base.toShortString());
        placeCrystal(r, currentHand, InvUtil.selected());
        markRender(r, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ selection

    private List<PlayerEntity> targets() {
        List<PlayerEntity> out = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!EntityUtil.isAliveTarget(p) || EntityUtil.isFriend(p)) continue;
            if (p.squaredDistanceTo(mc.player) > targetRange.getD() * targetRange.getD()) continue;
            out.add(p);
        }
        out.sort((a, b) -> Double.compare(a.squaredDistanceTo(mc.player), b.squaredDistanceTo(mc.player)));
        return out;
    }

    private boolean selfSafe(float self) {
        if (self > maxSelf.getF()) return false;
        return !antiSuicide.on() || self < DamageUtil.health(mc.player) - 0.5f;
    }

    private float requiredDamage(PlayerEntity t) {
        if (DamageUtil.health(t) <= facePlace.getF()) return 1.5f;
        if (armorBreak.getD() > 0 && lowestArmorPct(t) <= armorBreak.getD()) return 1.5f;
        return minDamage.getF();
    }

    private static double lowestArmorPct(PlayerEntity t) {
        double lowest = 100;
        for (var slot : new net.minecraft.entity.EquipmentSlot[]{net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
            var s = t.getEquippedStack(slot);
            if (s.isEmpty() || !s.isDamageable()) continue;
            lowest = Math.min(lowest, 100.0 * (s.getMaxDamage() - s.getDamage()) / s.getMaxDamage());
        }
        return lowest;
    }

    private EndCrystalEntity bestCrystal(List<PlayerEntity> targets) {
        EndCrystalEntity best = null;
        float bestDmg = 0;
        Vec3d eye = mc.player.getEyePos();
        for (Entity ent : mc.world.getEntities()) {
            if (!(ent instanceof EndCrystalEntity c) || !c.isAlive()) continue;
            if (eye.distanceTo(crystalAim(eye, crystalBox(c.getEntityPos()))) > breakRange.getD() - 0.03) continue;
            if (attacked.containsKey(c.getId())) continue;
            float self = DamageUtil.crystalDamage(mc.player, c.getEntityPos());
            if (!selfSafe(self)) continue;
            boolean ours = placed.containsKey(c.getBlockPos());
            for (PlayerEntity t : targets) {
                float dmg = DamageUtil.crystalDamage(t, c.getEntityPos());
                if ((dmg >= requiredDamage(t) || ours && dmg >= 1.5f) && dmg > bestDmg) {
                    bestDmg = dmg;
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * @param locked  the click ray also enters the box of the crystal that will stand here (Aim Lock)
     * @param pending we already clicked here and that crystal has not appeared yet, or it stands there waiting to be broken
     */
    private record PlaceResult(BlockPos base, Direction side, Vec3d hitVec, float damage, boolean locked, boolean pending) {
        boolean sameClick(PlaceResult o) {
            return base.equals(o.base) && side == o.side && hitVec.equals(o.hitVec);
        }
    }

    // why-not counters for the last bestPlace() call (Recorder logs)
    private int cBases, cOccupied, cEntity, cFace, cRange, cSelf, cLow;
    /** Best damage among spots rejected for being under Min Damage. */
    private float cBestLow;

    /** @param breaking crystal being attacked this tick (treated as already gone), may be null */
    private PlaceResult bestPlace(List<PlayerEntity> targets, EndCrystalEntity breaking) {
        Vec3d eye = aimEye();
        double range = placeRange.getD();
        int r = (int) Math.ceil(range);
        BlockPos center = BlockPos.ofFloored(eye);
        PlaceResult best = null;
        float bestScore = 0;
        cBases = cOccupied = cEntity = cFace = cRange = cSelf = cLow = 0;
        cBestLow = 0;
        long now = System.currentTimeMillis();
        long pendingWindow = (long) (rtt * 1.5 + 50);

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos base = center.add(x, y, z);
                    BlockState st = mc.world.getBlockState(base);
                    if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) continue;
                    cBases++;
                    BlockPos up = base.up();
                    if (!mc.world.getBlockState(up).isAir()) { cOccupied++; continue; }
                    // Blocked by anything except crystals we are getting rid of. A live crystal standing on this very
                    // spot does not rule it out: it is ours to break, and until then the spot just waits.
                    boolean[] waiting = {false};
                    if (!mc.world.getOtherEntities(null, new Box(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1), ent -> {
                        if (ent == breaking || !(ent instanceof EndCrystalEntity c)) return ent != breaking;
                        if (attacked.containsKey(c.getId())) return false;
                        if (c.getBlockPos().equals(up)) {
                            waiting[0] = true;
                            return false;
                        }
                        return true;
                    }).isEmpty()) { cEntity++; continue; }
                    // same for one the server has spawned here that is not in the client world yet
                    long[] k = known.get(up);
                    if (k != null && !attacked.containsKey((int) k[0]) && (breaking == null || breaking.getId() != (int) k[0])) waiting[0] = true;

                    Vec3d crystal = new Vec3d(up.getX() + 0.5, up.getY(), up.getZ() + 0.5);
                    float self = DamageUtil.crystalDamage(mc.player, crystal);
                    if (!selfSafe(self)) { cSelf++; continue; }
                    float dmg = 0;
                    for (PlayerEntity t : targets) {
                        if (t.squaredDistanceTo(crystal) > 12 * 12) continue;
                        float d = DamageUtil.crystalDamage(t, crystal);
                        if (d >= requiredDamage(t)) dmg = Math.max(dmg, d);
                        else cBestLow = Math.max(cBestLow, d);
                    }
                    if (dmg <= 0) { cLow++; continue; }
                    // cheap bound first: even with a perfect click this spot cannot win
                    float score = dmg - self * 0.5f;
                    if (base.equals(lastBase)) score *= 1.15f;
                    if (score <= bestScore) continue;

                    Click click = click(base, eye, crystal, range);
                    if (click == null) continue; // counted in click()
                    // a spot whose break needs a second rotation is worth roughly half the crystals per second
                    if (!click.locked) score *= 0.6f;
                    if (score > bestScore) {
                        bestScore = score;
                        // already clicked and the crystal hasn't shown up yet: keep the spot, don't click again
                        Long t0 = awaitingSpawn.get(up);
                        best = new PlaceResult(base, click.side, click.hit, dmg, click.locked, waiting[0] || t0 != null && now - t0 < pendingWindow);
                    }
                }
            }
        }
        return best;
    }

    private record Click(Direction side, Vec3d hit, boolean locked) {}

    /** Fractions across a face to try. The ones near the edges matter: from below, only a click near the top edge of a side face sends the ray up into the crystal quickly. */
    private static final double[] GRID = {0.5, 0.3, 0.7, 0.1, 0.9, 0.02, 0.98};

    /**
     * Where to click {@code base}. With Aim Lock, looks on every face we may click for a point
     * whose ray from the eye continues into the box of the crystal that will stand on the block
     * (within break reach), preferring the one whose ray gets there soonest. Falls back to the centre
     * of the best visible face.
     */
    private Click click(BlockPos base, Vec3d eye, Vec3d crystal, double range) {
        Box box = crystalBox(crystal);
        double reach = Math.min(breakRange.getD(), 3.0);
        Direction fallback = null;
        double fallbackDist = Double.MAX_VALUE;
        boolean inRange = false;
        Click best = null;
        double bestOff = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            if (strict.on()) {
                if (!BlockUtil.faceVisible(eye, base, d)) continue;
                if (d != Direction.UP && !mc.world.getBlockState(base.offset(d)).isReplaceable()) continue;
            } else if (d != Direction.UP) {
                continue;
            }
            Vec3d centre = faceCenter(base, d);
            double dist = eye.squaredDistanceTo(centre);
            if (dist <= range * range) {
                inRange = true;
                // UP first, as before; otherwise the closest side
                double rank = d == Direction.UP ? -1 : dist;
                if (rank < fallbackDist) {
                    fallbackDist = rank;
                    fallback = d;
                }
            }
            if (!aimLock.on()) continue;
            for (double u : GRID) {
                for (double v : GRID) {
                    Vec3d p = facePoint(base, d, u, v);
                    if (eye.squaredDistanceTo(p) > range * range) continue;
                    Vec3d dir = p.subtract(eye);
                    double enter = RotationUtil.rayBox(eye, dir, box);
                    if (enter < 0 || enter > reach - 0.02) continue;
                    // Best is the ray that reaches the crystal soonest (most reach to spare when we move);
                    // one that only grazes the box is lost as soon as we move a little, so those rank last.
                    boolean deep = looksAt(eye, dir, box.contract(0.2), reach);
                    double off = enter + p.squaredDistanceTo(centre) * 0.05 + (deep ? 0 : 10);
                    if (off < bestOff) {
                        bestOff = off;
                        best = new Click(d, p, true);
                    }
                }
            }
        }
        if (best != null) return best;
        if (fallback == null) {
            if (inRange || !anyFace(base, eye)) cFace++;
            else cRange++;
            return null;
        }
        return new Click(fallback, faceCenter(base, fallback), false);
    }

    private boolean anyFace(BlockPos base, Vec3d eye) {
        if (!strict.on()) return true;
        for (Direction d : Direction.values()) if (BlockUtil.faceVisible(eye, base, d)) return true;
        return false;
    }

    private static Vec3d faceCenter(BlockPos pos, Direction d) {
        return Vec3d.ofCenter(pos).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
    }

    /** Point on a face at fractions u, v (0..1) of its two in-plane axes. */
    private static Vec3d facePoint(BlockPos pos, Direction d, double u, double v) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return switch (d.getAxis()) {
            case Y -> new Vec3d(x + u, y + (d == Direction.UP ? 1 : 0), z + v);
            case X -> new Vec3d(x + (d == Direction.EAST ? 1 : 0), y + u, z + v);
            case Z -> new Vec3d(x + u, y + v, z + (d == Direction.SOUTH ? 1 : 0));
        };
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (!render.on() || renderPos == null) return;
        long age = System.currentTimeMillis() - renderTime;
        if (age > 500) return;
        float fade = 1f - MathHelper.clamp(age / 500f, 0f, 1f);
        int fill = color.withAlpha((int) (color.a() * fade));
        int line = color.withAlpha((int) (255 * fade));
        Render3D.box(new Box(renderPos), fill, line, 1.5f);
    }
}
