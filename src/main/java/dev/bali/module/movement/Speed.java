package dev.bali.module.movement;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.EntityUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * GrimCollide: Grim can't fully predict entity pushing, so standing in other entities lets you add a
 *   little velocity per entity. Works in crowds / near your own mobs or boats. Grim-safe.
 * Bhop: legit sprint-jumping, also Grim-safe.
 * Strafe: classic speed, will be set back by Grim; for other servers.
 */
public final class Speed extends Module {
    public enum Mode { GrimCollide, Bhop, Strafe }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.GrimCollide, "Speed mode");
    private final NumberSetting collideBoost = number("Collide Boost", 0.08, 0.01, 0.2, 0.005, "Velocity per colliding entity").visibleWhen(() -> is(Mode.GrimCollide));
    private final NumberSetting strafeSpeed = number("Strafe Speed", 0.29, 0.2, 1.0, 0.01, "Blocks/tick (Strafe)").visibleWhen(() -> is(Mode.Strafe));
    private final BoolSetting autoJump = bool("Auto Jump", true, "Jump while moving (Bhop/Strafe)").visibleWhen(() -> !is(Mode.GrimCollide));
    private final BoolSetting inLiquid = bool("In Liquid", false, "Work in water/lava");

    public Speed() {
        super("Speed", "Move faster", Category.MOVEMENT);
    }

    private boolean is(Mode m) { return mode != null && mode.is(m); }

    @Override
    public String getInfo() { return mode.get().name(); }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (!EntityUtil.isMoving() || mc.player.isSneaking() || mc.player.isGliding() || mc.player.hasVehicle()) return;
        if (!inLiquid.on() && (mc.player.isTouchingWater() || mc.player.isInLava())) return;

        switch (mode.get()) {
            case GrimCollide -> {
                Box box = mc.player.getBoundingBox().expand(1.0);
                int count = 0;
                for (Entity ent : mc.world.getOtherEntities(mc.player, box)) {
                    if (ent instanceof ArmorStandEntity) continue;
                    if (ent instanceof LivingEntity || ent instanceof AbstractBoatEntity) count++;
                }
                if (count == 0) return;
                Vec3d dir = moveDirection();
                double boost = collideBoost.getD() * count;
                mc.player.addVelocity(dir.x * boost, 0, dir.z * boost);
            }
            case Bhop -> {
                if (autoJump.on() && mc.player.isOnGround()) mc.player.jump();
            }
            case Strafe -> {
                if (autoJump.on() && mc.player.isOnGround()) mc.player.jump();
            }
        }
    }

    @EventHandler
    private void onMove(Events.Move e) {
        if (!is(Mode.Strafe) || !EntityUtil.isMoving() || mc.player.isGliding() || mc.player.hasVehicle()) return;
        if (!inLiquid.on() && (mc.player.isTouchingWater() || mc.player.isInLava())) return;
        Vec3d dir = moveDirection();
        double s = Math.max(strafeSpeed.getD(), Math.sqrt(e.movement.x * e.movement.x + e.movement.z * e.movement.z));
        e.movement = new Vec3d(dir.x * s, e.movement.y, dir.z * s);
    }

    /** Unit horizontal vector of the direction WASD is pushing relative to the camera yaw. */
    private Vec3d moveDirection() {
        Vec2f in = mc.player.input.getMovementInput(); // x = strafe (left +), y = forward
        double yaw = Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double sx = Math.cos(yaw), sz = Math.sin(yaw);
        double x = fx * in.y + sx * in.x;
        double z = fz * in.y + sz * in.x;
        double len = Math.sqrt(x * x + z * z);
        return len < 1e-6 ? Vec3d.ZERO : new Vec3d(x / len, 0, z / len);
    }
}
