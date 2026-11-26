package dev.bali.module.misc;

import com.mojang.authlib.GameProfile;
import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.util.DamageUtil;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;

import java.util.UUID;

public final class FakePlayer extends Module {
    private final BoolSetting copyInventory = bool("Copy Inventory", true, "Wear your armor and items");
    private final BoolSetting simulate = bool("Simulate Damage", true, "Take explosion damage and pop totems");
    private final dev.bali.setting.NumberSetting totems = number("Totems", 0, 0, 64, 1, "Totems it can pop before it dies (0 = never dies)");
    private final BoolSetting hitCooldown = bool("Hit Cooldown", true, "Vanilla 10-tick damage cooldown: hits inside it only add the difference (like a real player)");

    private static final int ID = -1_000_001;
    private OtherClientPlayerEntity fake;
    private int pops;
    private float lastDamage;
    private int hits;

    public int hits() { return hits; }
    public int pops() { return pops; }

    public FakePlayer() {
        super("FakePlayer", "Spawns a dummy player for testing", Category.MISC);
    }

    @Override
    public String getInfo() { return fake == null ? null : String.format("%.1f hp, %d pops", DamageUtil.health(fake), pops); }

    @Override
    protected void onEnable() {
        if (nullCheck()) {
            toggle();
            return;
        }
        fake = new OtherClientPlayerEntity(mc.world, new GameProfile(UUID.randomUUID(), "FakePlayer"));
        fake.setId(ID);
        fake.copyPositionAndRotation(mc.player);
        fake.setHeadYaw(mc.player.getHeadYaw());
        fake.setBodyYaw(mc.player.getBodyYaw());
        if (copyInventory.on()) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                fake.equipStack(slot, mc.player.getEquippedStack(slot).copy());
            }
        }
        fake.setHealth(20f);
        fake.setAbsorptionAmount(16f);
        if (simulate.on()) fake.equipStack(EquipmentSlot.OFFHAND, new ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        pops = 0;
        hits = 0;
        lastDamage = 0;
        mc.world.addEntity(fake);
    }

    @Override
    protected void onDisable() {
        if (fake != null && mc.world != null) mc.world.removeEntity(ID, Entity.RemovalReason.DISCARDED);
        fake = null;
    }

    @EventHandler
    private void onLeave(Events.Leave e) {
        fake = null;
        toggle();
    }

    @EventHandler
    private void onReceive(Events.PacketReceive e) {
        if (!simulate.on() || !(e.packet instanceof ExplosionS2CPacket p)) return;
        mc.execute(() -> {
            if (fake == null || mc.world == null || fake.isDead()) return;
            float dmg = DamageUtil.explosionDamage(fake, fake.getBoundingBox(), p.center(), p.radius(), null);
            if (dmg <= 0) return;
            boolean fresh = !hitCooldown.on() || fake.hurtTime <= 0;
            if (!fresh) {
                if (dmg <= lastDamage) return;
                float extra = dmg - lastDamage;
                lastDamage = dmg;
                dmg = extra;
            } else {
                lastDamage = dmg;
                fake.hurtTime = 10;
            }
            hits++;
            float absorb = fake.getAbsorptionAmount();
            float left = dmg - absorb;
            fake.setAbsorptionAmount(Math.max(0, absorb - dmg));
            if (left > 0) fake.setHealth(fake.getHealth() - left);
            boolean holdsTotem = fake.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)
                || fake.getMainHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);
            if (fake.getHealth() <= 0 && !holdsTotem) {
                fake.setHealth(0f);
                fake.setAbsorptionAmount(0f);
                fake.equipStack(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                Bali.EVENTS.post(new Events.Death(fake, pops));
                return;
            }
            if (fake.getHealth() <= 0) {
                ItemStack totem = new ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING);
                fake.setHealth(1f);
                fake.setAbsorptionAmount(8f);
                pops++;
                mc.particleManager.addEmitter(fake, net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING, 30);
                Bali.EVENTS.post(new Events.TotemPop(fake, pops));
                boolean more = totems.getI() == 0 || pops < totems.getI();
                fake.equipStack(EquipmentSlot.OFFHAND, more ? totem : ItemStack.EMPTY);
            }
        });
    }
}
