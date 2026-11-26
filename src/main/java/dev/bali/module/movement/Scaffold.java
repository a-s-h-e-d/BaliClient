package dev.bali.module.movement;

import dev.bali.Bali;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.manager.RotationPriority;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.module.client.Recorder;
import dev.bali.module.client.Rotations;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.util.BlockUtil;
import dev.bali.util.GrimTick;
import dev.bali.util.InvUtil;
import dev.bali.util.Placer;
import dev.bali.util.Render3D;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.MagmaBlock;
import net.minecraft.block.TntBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Locale;

public final class Scaffold extends Module {
    private final BoolSetting keepRotation = bool("Keep Rotation", true,
            "Keep facing back while you move or jump, not only on the ticks that place: no flick between camera and placement every block, and a steady walk");
    private final BoolSetting safeWalk = bool("Safe Walk", true,
            "Sneak at an edge when the next step would leave the block before the new one is down (also with no blocks left)");
    private final BoolSetting silentSwap = bool("Silent Swap", true, "Hold the blocks only for the click. Off: switch to them");
    private final BoolSetting render = bool("Render", true, "Show where the next block goes");
    private final ColorSetting color = color("Color", 0x4033CC66, "Render color");

    private static final float PITCH_STEP = 0.5f;

    private record Plan(BlockPos target, BlockPos support, Direction face, float yaw, float pitch) {}

    private final Placer placer = new Placer();
    private boolean sneakThisTick;
    private float lastPitch = 80f;
    private BlockPos shown;

    public Scaffold() {
        super("Scaffold", "Places blocks under you as you walk or jump (Grim-legit bridging)", Category.MOVEMENT);
    }

    @Override
    public String getInfo() {
        if (nullCheck()) return null;
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (usable(s)) n += s.getCount();
        }
        return String.valueOf(n);
    }

    @Override
    protected void onEnable() {
        placer.clear();
        sneakThisTick = false;
        shown = null;
    }

    @Override
    protected void onDisable() {
        sneakThisTick = false;
        shown = null;
    }

    @EventHandler
    private void onJoin(Events.Join e) {
        placer.clear();
        sneakThisTick = false;
        shown = null;
    }

    public PlayerInput filterInput(PlayerInput in) {
        if (!isEnabled() || !sneakThisTick || in.sneak()) return in;
        return new PlayerInput(in.forward(), in.backward(), in.left(), in.right(), in.jump(), true, in.sprint());
    }

    @EventHandler
    private void onTick(Events.TickPre e) {
        sneakThisTick = false;
        if (shown != null && !BlockUtil.isReplaceable(shown)) shown = null;
        if (mc.player.getAbilities().flying || mc.player.isGliding() || mc.player.hasVehicle() || mc.player.isSpectator()) return;

        int f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        int s = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        boolean jump = mc.options.jumpKey.isPressed();
        boolean userSneak = mc.options.sneakKey.isPressed();
        boolean moving = f != 0 || s != 0;
        float wanted = mc.player.getYaw() + (float) Math.toDegrees(Math.atan2(-s, f));
        float yaw = moving ? MathHelper.wrapDegrees(wanted + 180f) : Bali.ROTATIONS.serverYaw();
        Vec3d dir = moving ? new Vec3d(-MathHelper.sin(wanted * MathHelper.RADIANS_PER_DEGREE), 0, MathHelper.cos(wanted * MathHelper.RADIANS_PER_DEGREE)) : Vec3d.ZERO;
        boolean moveFix = Bali.MODULES.get(Rotations.class).moveFix.on();
        boolean rawDiagonal = !moveFix && f != 0 && s != 0;

        Vec3d move = predictMove(dir, rawDiagonal, moveFix, jump, userSneak);
        if (safeWalk.on() && !userSneak
                && (leavesGround(move) || leavesGround(predictMove(dir, f != 0 && s != 0, false, jump, false)))) {
            sneakThisTick = true;
            move = predictMove(dir, rawDiagonal, moveFix, jump, true);
        }

        int slot = blockSlot();
        if (slot == -1) {
            placer.tick(List.of());
            return;
        }

        boolean crouch = userSneak || sneakThisTick;
        Vec3d feet = mc.player.getEntityPos().add(move);
        Vec3d eye = feet.add(0, mc.player.getEyeHeight(crouch ? EntityPose.CROUCHING : EntityPose.STANDING), 0);
        Box body = mc.player.getBoundingBox().offset(move);

        BlockPos under = BlockPos.ofFloored(feet.x, Math.floor(feet.y + 1e-4) - 1, feet.z);
        Plan plan = null;
        if (fits(under, body)) {
            plan = plan(under, eye, yaw, moving);
            if (plan == null && !hasSupport(under)) plan = helper(under, eye, yaw, moving, body, feet);
        }
        placer.tick(plan == null ? List.of() : List.of(plan.target));

        if (plan != null && placer.ready(plan.target)) {
            Plan p = plan;
            shown = p.target;
            lastPitch = p.pitch;
            Bali.ROTATIONS.rotate(p.yaw, p.pitch, RotationPriority.SCAFFOLD, () -> click(p, slot));
        } else if (keepRotation.on() && (moving || jump)) {
            Bali.ROTATIONS.rotate(yaw, lastPitch, RotationPriority.SCAFFOLD, null);
        }
    }

    private void click(Plan p, int slot) {
        if (nullCheck() || !isEnabled()) return;
        if (!BlockUtil.isReplaceable(p.target) || !BlockUtil.noEntityCollision(p.target)) return;
        if (!usable(mc.player.getInventory().getStack(slot))) return;
        Vec3d eye = mc.player.getEyePos();
        BlockHitResult hit = raycast(eye, Bali.ROTATIONS.serverYaw(), Bali.ROTATIONS.serverPitch());
        if (hit == null || !hit.getBlockPos().offset(hit.getSide()).equals(p.target)
                || BlockUtil.isClickable(mc.world.getBlockState(hit.getBlockPos()))) {
            Recorder.log("SCAFFOLD", String.format(Locale.ROOT, "skip %s: look ray hits %s, not its %s face",
                    p.target.toShortString(), hit == null ? "nothing" : hit.getBlockPos().toShortString() + " " + hit.getSide(), p.face));
            return;
        }
        Runnable clicked = placer.clicked(p.target);
        Runnable act = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            GrimTick.markOther();
            clicked.run();
        };
        if (silentSwap.on()) {
            InvUtil.silentSwap(slot, act);
        } else {
            if (InvUtil.selected() != slot) InvUtil.select(slot);
            act.run();
        }
        Recorder.log("SCAFFOLD", String.format(Locale.ROOT, "place %s on %s %s, eye (%.3f %.3f %.3f) look %.1f/%.1f",
                p.target.toShortString(), hit.getBlockPos().toShortString(), hit.getSide(), eye.x, eye.y, eye.z,
                Bali.ROTATIONS.serverYaw(), Bali.ROTATIONS.serverPitch()));
    }

    private Plan plan(BlockPos target, Vec3d eye, float yaw, boolean moving) {
        float[] offsets = moving ? new float[]{0, 7.5f, -7.5f, 15f, -15f} : new float[]{0};
        for (float o : offsets) {
            Plan p = bestPitch(target, eye, yaw + o);
            if (p != null) return p;
        }
        if (moving) return null;
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            Vec3d face = Vec3d.ofCenter(support).add(Vec3d.of(d.getOpposite().getVector()).multiply(0.5));
            Plan p = bestPitch(target, eye, (float) Math.toDegrees(Math.atan2(face.z - eye.z, face.x - eye.x)) - 90f);
            if (p != null) return p;
        }
        return null;
    }

    private Plan bestPitch(BlockPos target, Vec3d eye, float yaw) {
        double reach = mc.player.getBlockInteractionRange();
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            BlockState st = mc.world.getBlockState(support);
            if (st.isReplaceable() || BlockUtil.isClickable(st)) continue;
            VoxelShape shape = st.getOutlineShape(mc.world, support);
            if (shape.isEmpty()) continue;
            Direction face = d.getOpposite();
            if (!BlockUtil.faceVisible(eye, support, face)) continue;
            Vec3d center = Vec3d.ofCenter(support).add(Vec3d.of(face.getVector()).multiply(0.5));
            for (float pitch = 90f; pitch >= 0f; pitch -= PITCH_STEP) {
                Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(reach));
                BlockHitResult hit = shape.raycast(eye, end, support);
                if (hit == null || hit.getSide() != face) continue;
                double dist = hit.getPos().squaredDistanceTo(center);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new Plan(target, support, face, yaw, pitch);
                }
            }
        }
        if (best == null) return null;
        BlockHitResult hit = raycast(eye, best.yaw, best.pitch);
        if (hit == null || !hit.getBlockPos().equals(best.support) || hit.getSide() != best.face) return null;
        return best;
    }

    private Plan helper(BlockPos target, Vec3d eye, float yaw, boolean moving, Box body, Vec3d feet) {
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos h = target.offset(d);
            if (!fits(h, body)) continue;
            Plan p = plan(h, eye, yaw, moving);
            if (p == null) continue;
            double dist = Vec3d.ofCenter(h).squaredDistanceTo(feet.x, h.getY() + 0.5, feet.z);
            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }
        return best;
    }

    private boolean hasSupport(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState st = mc.world.getBlockState(pos.offset(d));
            if (!st.isReplaceable() && !BlockUtil.isClickable(st)) return true;
        }
        return false;
    }

    private boolean fits(BlockPos pos, Box body) {
        if (!BlockUtil.isReplaceable(pos)) return false;
        Box b = new Box(pos);
        if (body.intersects(b)) return false;
        for (Entity e : mc.world.getOtherEntities(mc.player, b)) {
            if (e instanceof ItemEntity || e instanceof ExperienceOrbEntity) continue;
            if (!e.isSpectator() && e.isAlive()) return false;
        }
        return true;
    }

    private BlockHitResult raycast(Vec3d eye, float yaw, float pitch) {
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(mc.player.getBlockInteractionRange()));
        BlockHitResult r = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return r.getType() == HitResult.Type.BLOCK ? r : null;
    }

    private Vec3d predictMove(Vec3d dir, boolean rawDiagonal, boolean sprintStops, boolean jump, boolean sneak) {
        Vec3d v = mc.player.getVelocity();
        double vx = v.x, vy = v.y, vz = v.z;
        if (v.horizontalLengthSquared() < 9.0E-6) {
            vx = 0;
            vz = 0;
        }
        if (Math.abs(vy) < 0.003) vy = 0;
        if (jump && mc.player.isOnGround() && mc.player.jumpingCooldown == 0) {
            vy = Math.max(vy, mc.player.getAttributeValue(EntityAttributes.JUMP_STRENGTH) + mc.player.getJumpBoostVelocityModifier());
        }
        if (dir.lengthSquared() > 0) {
            double input = 0.98;
            if (mc.player.input.playerInput.sneak() || mc.player.isCrawling()) input *= mc.player.getAttributeValue(EntityAttributes.SNEAKING_SPEED);
            if (rawDiagonal) input = Math.min(input * Math.sqrt(2), 1);
            boolean sprinting = mc.player.isSprinting() && !sprintStops;
            double accel;
            if (mc.player.isOnGround()) {
                double speed = mc.player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
                if (mc.player.isSprinting() && !sprinting) speed /= 1.3;
                float slip = mc.world.getBlockState(mc.player.getVelocityAffectingPos()).getBlock().getSlipperiness();
                accel = speed * (0.21600002f / (slip * slip * slip));
            } else {
                accel = sprinting ? 0.026 : 0.02;
            }
            vx += dir.x * input * accel;
            vz += dir.z * input * accel;
        }
        Vec3d move = new Vec3d(vx, vy, vz);
        if (sneak) move = clipAtLedge(move);
        return Entity.adjustMovementForCollisions(mc.player, move, mc.player.getBoundingBox(), mc.world, List.of());
    }

    private boolean leavesGround(Vec3d move) {
        if (!mc.player.isOnGround() || move.y > 0) return false;
        if (move.x == 0 && move.z == 0) return false;
        return spaceBelowEmpty(move.x, move.z, mc.player.getStepHeight());
    }

    private Vec3d clipAtLedge(Vec3d m) {
        if (m.y > 0 || !mc.player.isOnGround()) return m;
        double step = mc.player.getStepHeight();
        double d = m.x, e = m.z;
        double h = Math.signum(d) * 0.05, i = Math.signum(e) * 0.05;
        while (d != 0 && spaceBelowEmpty(d, 0, step)) {
            if (Math.abs(d) <= 0.05) {
                d = 0;
                break;
            }
            d -= h;
        }
        while (e != 0 && spaceBelowEmpty(0, e, step)) {
            if (Math.abs(e) <= 0.05) {
                e = 0;
                break;
            }
            e -= i;
        }
        while (d != 0 && e != 0 && spaceBelowEmpty(d, e, step)) {
            if (Math.abs(d) <= 0.05) d = 0;
            else d -= h;
            if (Math.abs(e) <= 0.05) e = 0;
            else e -= i;
        }
        return new Vec3d(d, m.y, e);
    }

    private boolean spaceBelowEmpty(double dx, double dz, double step) {
        Box b = mc.player.getBoundingBox();
        return mc.world.isSpaceEmpty(mc.player, new Box(
                b.minX + 1.0E-7 + dx, b.minY - step - 1.0E-7, b.minZ + 1.0E-7 + dz,
                b.maxX - 1.0E-7 + dx, b.minY, b.maxZ - 1.0E-7 + dz));
    }

    private int blockSlot() {
        int sel = InvUtil.selected();
        if (usable(mc.player.getInventory().getStack(sel))) return sel;
        return InvUtil.findHotbar(Scaffold::usable);
    }

    public static boolean usable(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock || b instanceof TntBlock || b instanceof MagmaBlock) return false;
        BlockState st = b.getDefaultState();
        if (BlockUtil.isClickable(st)) return false;
        return st.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (!render.on() || shown == null) return;
        Render3D.box(new Box(shown), color.argb(), color.withAlpha(255), 1f);
    }
}
