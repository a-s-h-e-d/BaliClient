package dev.bali.module.movement;

import com.mojang.authlib.GameProfile;
import dev.bali.event.EventHandler;
import dev.bali.event.events.Events;
import dev.bali.module.Category;
import dev.bali.module.Module;
import dev.bali.setting.BoolSetting;
import dev.bali.setting.ColorSetting;
import dev.bali.setting.EnumSetting;
import dev.bali.setting.NumberSetting;
import dev.bali.util.ChatUtil;
import dev.bali.util.Render3D;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Holds outgoing packets and sends them all at once later. To the server (and Grim) you froze and
 * then caught up, like a lag spike.
 *
 * All: hold every packet, keeping Grim's transaction order intact. Safest on Grim.
 * Movement: hold only movement; other packets (attacks, chat, pings) still go out live.
 *
 * Disable sends everything. With "Return" on, disabling discards the movement instead and puts you
 * back where you started (the server never saw you leave).
 */
public final class Blink extends Module {
    public enum Mode { All, Movement }

    private final EnumSetting<Mode> mode = mode("Mode", Mode.All, "What to hold back");
    private final NumberSetting maxTicks = number("Max Ticks", 100, 10, 300, 5, "Auto release after this long (keepalive timeout is ~15s = 300)");
    private final BoolSetting pulse = bool("Pulse", false, "Release every Pulse Ticks and keep blinking");
    private final NumberSetting pulseTicks = number("Pulse Ticks", 20, 2, 100, 1, "Ticks between releases").visibleWhen(() -> pulse != null && pulse.on());
    private final BoolSetting returnOnDisable = bool("Return", false, "Disable = go back to the start instead of sending movement");
    private final BoolSetting ghost = bool("Ghost", true, "Show where the server thinks you are");
    private final ColorSetting color = color("Color", 0x402BB5C9, "Ghost box color");

    public static final int GHOST_ID = -1_000_002;
    private final ConcurrentLinkedQueue<Packet<?>> held = new ConcurrentLinkedQueue<>();
    private volatile boolean flushing;
    private int ticks;
    private Vec3d start;
    private float startYaw, startPitch;
    private OtherClientPlayerEntity ghostEntity;

    public Blink() {
        super("Blink", "Holds packets so you teleport when it releases", Category.MOVEMENT);
    }

    @Override
    public String getInfo() { return held.size() + " / " + ticks; }

    @Override
    protected void onEnable() {
        if (nullCheck()) {
            toggle();
            return;
        }
        held.clear();
        ticks = 0;
        markStart();
    }

    @Override
    protected void onDisable() {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            held.clear();
            removeGhost();
            return;
        }
        if (returnOnDisable.on() && start != null) {
            held.removeIf(Blink::isMovement);
            flush();
            mc.player.refreshPositionAndAngles(start.x, start.y, start.z, startYaw, startPitch);
            mc.player.setVelocity(Vec3d.ZERO);
        } else {
            flush();
        }
        removeGhost();
    }

    @EventHandler(priority = -10_000) // last, so other listeners see packets when they are created
    private void onSend(Events.PacketSend e) {
        if (flushing) return;
        if (e.packet instanceof KeepAliveC2SPacket && mode.is(Mode.Movement)) return;
        if (mode.is(Mode.Movement) && !isMovement(e.packet)) return;
        held.add(e.packet);
        e.cancel();
    }

    @EventHandler
    private void onTick(Events.TickPost e) {
        ticks++;
        if (pulse.on() && ticks >= pulseTicks.getI()) {
            flush();
            ticks = 0;
            markStart();
            return;
        }
        if (ticks >= maxTicks.getI()) {
            ChatUtil.info("Blink released (max ticks)");
            toggle();
        }
    }

    @EventHandler
    private void onLeave(Events.Leave e) {
        held.clear();
        ghostEntity = null;
        toggle();
    }

    @EventHandler
    private void onRender(Events.Render3D e) {
        if (!ghost.on() || start == null) return;
        Box b = mc.player.getDimensions(mc.player.getPose()).getBoxAt(start);
        Render3D.box(b, color.argb(), color.withAlpha(255), 1.5f);
    }

    private static boolean isMovement(Packet<?> p) {
        return p instanceof PlayerMoveC2SPacket || p instanceof VehicleMoveC2SPacket
            || p instanceof PlayerInputC2SPacket || p instanceof ClientCommandC2SPacket;
    }

    private void flush() {
        if (mc.getNetworkHandler() == null) {
            held.clear();
            return;
        }
        List<Packet<?>> out = new ArrayList<>(held);
        held.clear();
        flushing = true;
        try {
            for (Packet<?> p : out) mc.getNetworkHandler().getConnection().send(p, null, false);
            mc.getNetworkHandler().getConnection().flush();
        } finally {
            flushing = false;
        }
    }

    private void markStart() {
        start = mc.player.getEntityPos();
        startYaw = mc.player.getYaw();
        startPitch = mc.player.getPitch();
        if (!ghost.on()) return;
        removeGhost();
        ghostEntity = new OtherClientPlayerEntity(mc.world, new GameProfile(UUID.randomUUID(), mc.player.getGameProfile().name()));
        ghostEntity.setId(GHOST_ID);
        ghostEntity.copyPositionAndRotation(mc.player);
        ghostEntity.setHeadYaw(mc.player.getHeadYaw());
        ghostEntity.setBodyYaw(mc.player.getBodyYaw());
        ghostEntity.getInventory().clone(mc.player.getInventory());
        mc.world.addEntity(ghostEntity);
    }

    private void removeGhost() {
        if (ghostEntity != null && mc.world != null) mc.world.removeEntity(GHOST_ID, Entity.RemovalReason.DISCARDED);
        ghostEntity = null;
    }
}
