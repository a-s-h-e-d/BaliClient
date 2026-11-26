package dev.bali.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;

public final class DamageUtil {
    private DamageUtil() {}

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final float CRYSTAL_POWER = 6f;

    public static float crystalDamage(LivingEntity target, Vec3d crystalPos) {
        return explosionDamage(target, target.getBoundingBox(), crystalPos, CRYSTAL_POWER, null);
    }

    public static float crystalDamage(LivingEntity target, Box targetBox, Vec3d crystalPos, BlockPos ignore) {
        return explosionDamage(target, targetBox, crystalPos, CRYSTAL_POWER, ignore);
    }

    public static float explosionDamage(LivingEntity target, Box box, Vec3d pos, float power, BlockPos ignore) {
        if (target instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return 0f;
        double radius = power * 2.0;
        Vec3d feet = new Vec3d((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
        double dist = Math.sqrt(feet.squaredDistanceTo(pos)) / radius;
        if (dist > 1.0) return 0f;
        double exposure = exposure(pos, box, target, ignore);
        double impact = (1.0 - dist) * exposure;
        float damage = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1.0);

        if (target instanceof PlayerEntity) {
            Difficulty d = mc.world.getDifficulty();
            if (d == Difficulty.PEACEFUL) damage = 0f;
            else if (d == Difficulty.EASY) damage = Math.min(damage / 2f + 1f, damage);
            else if (d == Difficulty.HARD) damage = damage * 1.5f;
        }
        return applyReductions(target, damage, true);
    }

    public static float applyReductions(LivingEntity target, float damage, boolean explosion) {
        if (damage <= 0) return 0;
        float armor = (float) target.getAttributeValue(EntityAttributes.ARMOR);
        float toughness = (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        float f = 2.0f + toughness / 4.0f;
        float g = MathHelper.clamp(armor - damage / f, armor * 0.2f, 20.0f);
        damage *= 1.0f - g / 25.0f;

        StatusEffectInstance res = target.getStatusEffect(StatusEffects.RESISTANCE);
        if (res != null) {
            int lvl = res.getAmplifier() + 1;
            damage = Math.max(damage * (25 - lvl * 5) / 25f, 0f);
        }

        int epf = protectionPoints(target, explosion);
        damage = net.minecraft.entity.DamageUtil.getInflictedDamage(damage, epf);
        return Math.max(damage, 0f);
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public static int protectionPoints(LivingEntity target, boolean explosion) {
        int epf = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack s = target.getEquippedStack(slot);
            if (s.isEmpty()) continue;
            epf += enchantLevel(s, Enchantments.PROTECTION);
            if (explosion) epf += enchantLevel(s, Enchantments.BLAST_PROTECTION) * 2;
        }
        return Math.min(epf, 20);
    }

    public static int enchantLevel(ItemStack stack, net.minecraft.registry.RegistryKey<Enchantment> key) {
        ItemEnchantmentsComponent ench = EnchantmentHelper.getEnchantments(stack);
        for (RegistryEntry<Enchantment> e : ench.getEnchantments()) {
            if (e.matchesKey(key)) return ench.getLevel(e);
        }
        return 0;
    }

    public static double exposure(Vec3d source, Box box, Entity entity, BlockPos ignore) {
        double d = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double e = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double f = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double g = (1.0 - Math.floor(1.0 / d) * d) / 2.0;
        double h = (1.0 - Math.floor(1.0 / f) * f) / 2.0;
        if (d < 0 || e < 0 || f < 0) return 0;
        int hit = 0, total = 0;
        for (double k = 0.0; k <= 1.0; k += d) {
            for (double l = 0.0; l <= 1.0; l += e) {
                for (double m = 0.0; m <= 1.0; m += f) {
                    Vec3d v = new Vec3d(MathHelper.lerp(k, box.minX, box.maxX) + g, MathHelper.lerp(l, box.minY, box.maxY), MathHelper.lerp(m, box.minZ, box.maxZ) + h);
                    if (rayMiss(v, source, entity, ignore)) hit++;
                    total++;
                }
            }
        }
        return total == 0 ? 0 : (double) hit / total;
    }

    private static boolean rayMiss(Vec3d from, Vec3d to, Entity entity, BlockPos ignore) {
        if (ignore == null) {
            return mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, entity)).getType() == HitResult.Type.MISS;
        }
        HitResult r = net.minecraft.world.BlockView.raycast(from, to, null, (ctx, pos) -> {
            if (pos.equals(ignore)) return null;
            var state = mc.world.getBlockState(pos);
            var shape = state.getCollisionShape(mc.world, pos);
            var hitRes = mc.world.raycastBlock(from, to, pos, shape, state);
            return hitRes;
        }, ctx -> null);
        return r == null || r.getType() == HitResult.Type.MISS;
    }

    public static float health(LivingEntity e) {
        return e.getHealth() + e.getAbsorptionAmount();
    }
}
