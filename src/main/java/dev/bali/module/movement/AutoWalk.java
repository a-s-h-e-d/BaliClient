package dev.bali.module.movement;

import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.ChatUtil;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * W-lock for highway travel. Everything it does is something a player could do with the keyboard
 * (holding W, pressing space, turning), so it is Grim-safe.
 */
public final class AutoWalk extends Module {
    private final BoolSetting yawSnap = bool("Yaw Snap", true, "Snap to the nearest 45 degrees once when enabled (highways)");
    private final BoolSetting jump = bool("Jump Obstacles", true, "Jump when walking into a 1-block step");
    private final BoolSetting pauseEating = bool("Pause Eating", true, "Stop walking while you eat/use items");
    private final BoolSetting safety = bool("Safety", true, "Stop before drops, lava and the void");
    private final NumberSetting maxDrop = number("Max Drop", 3, 1, 20, 1, "Biggest drop Safety allows").visibleWhen(() -> safety != null && safety.on());

    private boolean holding;
    private boolean warned;
    private int jumpTicks;

    public AutoWalk() {
        super("AutoWalk", "Holds W for you (highway walker)", Category.MOVEMENT);
    }

    @Override
    protected void onEnable() {
        warned = false;
        if (!nullCheck() && yawSnap.on()) mc.player.setYaw(Math.round(mc.player.getYaw() / 45f) * 45f);
    }

    @Override
    protected void onDisable() {
        if (holding && mc.options != null) mc.options.forwardKey.setPressed(false);
        if (jumpTicks > 0 && mc.options != null) mc.options.jumpKey.setPressed(false);
        jumpTicks = 0;
        holding = false;
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        if (jumpTicks > 0 && --jumpTicks == 0) mc.options.jumpKey.setPressed(false);
        boolean go = true;
        if (pauseEating.on() && mc.player.isUsingItem()) go = false;
        boolean bouncing = dev.bali.Bali.MODULES.get(ElytraFly.class).isEnabled();
        if (go && safety.on() && !bouncing && !mc.player.isGliding() && !mc.player.getAbilities().flying) {
            String danger = dangerAhead();
            if (danger != null) {
                go = false;
                if (!warned) ChatUtil.warn("AutoWalk stopped: " + danger + " ahead");
                warned = true;
            } else {
                warned = false;
            }
        }
        mc.options.forwardKey.setPressed(go);
        holding = true;

        if (go && jump.on() && mc.player.isOnGround() && mc.player.horizontalCollision && !mc.player.isSneaking()) {
            // tap jump for one tick through the input, like a player would; vanilla does the jump
            mc.options.jumpKey.setPressed(true);
            jumpTicks = 2;
        }
    }

    /** Looks at the ground just ahead of us. Returns a reason to stop, or null if it's fine. */
    private String dangerAhead() {
        Vec3d dir = Vec3d.fromPolar(0, mc.player.getYaw()).normalize();
        Vec3d front = mc.player.getEntityPos().add(dir.multiply(0.9));
        BlockPos feet = BlockPos.ofFloored(front.x, mc.player.getY() + 0.01, front.z);
        for (int dy = 0; dy <= 1; dy++) {
            if (isLava(feet.up(dy))) return "lava";
        }
        int drop = maxDrop.getI();
        for (int d = 1; d <= drop + 1; d++) {
            BlockPos below = feet.down(d);
            if (below.getY() < mc.world.getBottomY()) return "void";
            BlockState st = mc.world.getBlockState(below);
            if (isLava(below)) return "lava";
            if (!st.getCollisionShape(mc.world, below).isEmpty() || !st.getFluidState().isEmpty()) return null;
        }
        return "a drop over " + drop + " blocks";
    }

    private boolean isLava(BlockPos pos) {
        var f = mc.world.getFluidState(pos).getFluid();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }
}
