package dev.bali.event.events;

import dev.bali.event.Cancellable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.math.Vec3d;

public final class Events {
    private Events() {}

    public static final class TickPre { public static final TickPre INSTANCE = new TickPre(); }

    public static final class TickPost { public static final TickPost INSTANCE = new TickPost(); }

    public static final class PacketSend extends Cancellable {
        public final Packet<?> packet;
        public PacketSend(Packet<?> packet) { this.packet = packet; }
    }

    public static final class PacketReceive extends Cancellable {
        public final Packet<?> packet;
        public PacketReceive(Packet<?> packet) { this.packet = packet; }
    }

    public static final class Render3D {
        public final MatrixStack matrices;
        public final float tickDelta;
        public final Vec3d cameraPos;
        public Render3D(MatrixStack matrices, float tickDelta, Vec3d cameraPos) {
            this.matrices = matrices;
            this.tickDelta = tickDelta;
            this.cameraPos = cameraPos;
        }
    }

    public static final class Render2D {
        public final DrawContext context;
        public final float tickDelta;
        public Render2D(DrawContext context, float tickDelta) {
            this.context = context;
            this.tickDelta = tickDelta;
        }
    }

    public static final class Key extends Cancellable {
        public final int key;
        public final int action;
        public final boolean mouse;
        public Key(int key, int action, boolean mouse) {
            this.key = key;
            this.action = action;
            this.mouse = mouse;
        }
    }

    public static final class Move {
        public final MovementType type;
        public Vec3d movement;
        public Move(MovementType type, Vec3d movement) {
            this.type = type;
            this.movement = movement;
        }
    }

    public static final class Join { public static final Join INSTANCE = new Join(); }
    public static final class Leave { public static final Leave INSTANCE = new Leave(); }

    public static final class TotemPop {
        public final PlayerEntity player;
        public final int pops;
        public TotemPop(PlayerEntity player, int pops) {
            this.player = player;
            this.pops = pops;
        }
    }

    public static final class Death {
        public final PlayerEntity player;
        public final int pops;
        public Death(PlayerEntity player, int pops) {
            this.player = player;
            this.pops = pops;
        }
    }

    public static final class Attack extends Cancellable {
        public final Entity target;
        public Attack(Entity target) { this.target = target; }
    }
}
