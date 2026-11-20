package dev.bali.module.combat;

import dev.bali.manager.RotationPriority;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.DamageUtil;
import dev.bali.util.EntityUtil;
import dev.bali.util.InvUtil;
import dev.bali.util.Render3D;
import dev.bali.util.RotationUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class KillAura extends Module {
    public enum Priority { Distance, Health, Angle }
    public enum Weapon { Any, SwordAxe, Switch }

    private final BoolSetting players = bool("Players", true, "Attack players");
    private final BoolSetting hostiles = bool("Hostiles", false, "Attack hostile mobs");
    private final BoolSetting passives = bool("Passives", false, "Attack passive mobs");
    private final NumberSetting range = number("Range", 3.0, 1, 6, 0.05, "Attack range to the hitbox (survival is 3.0)");
    // Hitting through blocks is legal: the server only checks distance and Grim's reach ray ignores blocks.
    private final NumberSetting wallRange = number("Wall Range", 3.0, 0, 6, 0.05, "Range when the target is behind blocks (same as Range = hit through walls)");
    private final NumberSetting cooldown = number("Cooldown", 0.95, 0, 1, 0.01, "Attack-charge needed before hitting");
    private final EnumSetting<Priority> priority = mode("Priority", Priority.Distance, "Target selection");
    private final EnumSetting<Weapon> weapon = mode("Weapon", Weapon.Switch, "Switch = swap to your sword (best weapon) when a target is in range; SwordAxe = only hit while holding one");
    private final BoolSetting rotate = bool("Rotate", true, "Silent rotate to the target (needed on Grim)");
    private final BoolSetting pauseEating = bool("Pause Eating", true, "Don't attack while using an item");
    private final BoolSetting offhandGap = bool("Offhand Gap", true, "Hold right click with a weapon (or nothing) in hand to put a gapple in your offhand and eat it (AutoTotem does the swap, so keep it on)");
    private final NumberSetting gapHealth = number("Gap Health", 12, 1, 36, 0.5, "Below this health+absorption the totem stays in your offhand instead");
    private final BoolSetting render = bool("Render", true, "Highlight the target");
    private final ColorSetting color = color("Color", 0x60FF3030, "Target highlight");

    private LivingEntity target;

    public KillAura() {
        super("KillAura", "Attacks nearby entities", Category.COMBAT);
    }

    @Override
    public String getInfo() { return target instanceof PlayerEntity p ? p.getGameProfile().name() : null; }

    @Override
    protected void onDisable() { target = null; }

    public LivingEntity target() { return isEnabled() ? target : null; }

    /**
     * The gapple to put in the offhand right now (enchanted first), or null: Offhand Gap on, right
     * click held with a weapon (or nothing) in the main hand, health above Gap Health and one in the
     * inventory. Right click then eats from the offhand. AutoTotem asks this and still puts the totem
     * back on any danger. Holding blocks, a bow, XP bottles etc. never moves the totem.
     */
    public net.minecraft.item.Item offhandGapple() {
        if (!isEnabled() || !offhandGap.on() || mc.player == null || !mc.options.useKey.isPressed()) return null;
        if (!weaponOrEmpty(mc.player.getMainHandStack())) return null;
        if (DamageUtil.health(mc.player) <= gapHealth.getF()) return null;
        if (InvUtil.findInventory(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE) != -1 || mc.player.getOffHandStack().isOf(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE))
            return net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE;
        if (InvUtil.findInventory(net.minecraft.item.Items.GOLDEN_APPLE) != -1 || mc.player.getOffHandStack().isOf(net.minecraft.item.Items.GOLDEN_APPLE))
            return net.minecraft.item.Items.GOLDEN_APPLE;
        return null;
    }

    /** Main hand items whose right click does nothing in the air, so it falls through to the offhand. */
    private static boolean weaponOrEmpty(net.minecraft.item.ItemStack s) {
        return s.isEmpty() || s.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) || s.isIn(net.minecraft.registry.tag.ItemTags.AXES)
                || s.isOf(net.minecraft.item.Items.MACE);
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        target = findTarget();
        if (target == null) return;
        if (pauseEating.on() && (mc.player.isUsingItem() || Bali.MODULES.get(dev.bali.module.player.AutoEat.class).isEating())) return;
        // AutoCrystal first: while it is working a spot we neither swap away from its crystals nor hit
        if (Bali.MODULES.get(AutoCrystal.class).busy()) return;

        if (weapon.is(Weapon.SwordAxe) && !isWeapon(mc.player.getMainHandStack())) return;
        if (weapon.is(Weapon.Switch)) {
            // keep a sword, or a mace you picked on purpose (mace modules); anything else -> best sword
            ItemStack held = mc.player.getMainHandStack();
            int slot = held.isIn(ItemTags.SWORDS) || held.isOf(net.minecraft.item.Items.MACE) ? -1 : bestWeaponSlot();
            if (slot != -1 && slot != InvUtil.selected()) InvUtil.select(slot);
            if (!isWeapon(mc.player.getMainHandStack())) return;
        }

        if (mc.player.getAttackCooldownProgress(0.5f) < cooldown.getF()) return;

        Criticals crits = Bali.MODULES.get(Criticals.class);
        if (crits.isEnabled() && crits.shouldWait()) return;

        LivingEntity t = target;
        Vec3d aim = RotationUtil.closestPoint(t.getBoundingBox(), mc.player.getEyePos());
        if (rotate.on()) Bali.ROTATIONS.rotate(aim, RotationPriority.KILL_AURA, () -> attack(t));
        else attack(t);
    }

    private void attack(LivingEntity t) {
        if (!t.isAlive() || !inRange(t)) return;
        mc.interactionManager.attackEntity(mc.player, t);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity ent : mc.world.getEntities()) {
            if (!EntityUtil.isAliveTarget(ent) || EntityUtil.isFriend(ent)) continue;
            switch (EntityUtil.kind(ent)) {
                case PLAYER -> { if (!players.on()) continue; }
                case HOSTILE -> { if (!hostiles.on()) continue; }
                case PASSIVE -> { if (!passives.on()) continue; }
                default -> { continue; }
            }
            LivingEntity l = (LivingEntity) ent;
            if (!inRange(l)) continue;
            double score = switch (priority.get()) {
                case Distance -> mc.player.squaredDistanceTo(l);
                case Health -> DamageUtil.health(l);
                case Angle -> RotationUtil.angleTo(mc.player.getYaw(), mc.player.getPitch(), mc.player.getEyePos(), l.getBoundingBox().getCenter());
            };
            if (score < bestScore) {
                bestScore = score;
                best = l;
            }
        }
        return best;
    }

    private boolean inRange(LivingEntity l) {
        Vec3d eye = mc.player.getEyePos();
        Box box = l.getBoundingBox();
        Vec3d closest = RotationUtil.closestPoint(box, eye);
        double dist = eye.distanceTo(closest);
        if (dist > range.getD()) return false;
        if (dist > wallRange.getD()) {
            HitResult hr = mc.world.raycast(new RaycastContext(eye, closest, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            return hr.getType() == HitResult.Type.MISS;
        }
        return true;
    }

    /** Hotbar slot of the best weapon: a sword first (highest tier, then sharpness), else an axe, mace or spear. */
    private int bestWeaponSlot() {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!isWeapon(s)) continue;
            double type = s.isIn(ItemTags.SWORDS) ? 3 : s.isIn(ItemTags.AXES) ? 2 : 1;
            double score = type * 100_000 + DamageUtil.enchantLevel(s, net.minecraft.enchantment.Enchantments.SHARPNESS) * 10_000 + s.getMaxDamage();
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private boolean isWeapon(ItemStack s) {
        return s.isIn(ItemTags.SWORDS) || s.isIn(ItemTags.AXES) || s.isOf(net.minecraft.item.Items.MACE) || s.isIn(ItemTags.SPEARS);
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (!render.on() || target == null) return;
        Vec3d p = target.getLerpedPos(e.tickDelta);
        Box b = target.getBoundingBox().offset(p.subtract(target.getEntityPos()));
        Render3D.box(b, color.argb(), color.withAlpha(255), 1.5f);
    }
}
