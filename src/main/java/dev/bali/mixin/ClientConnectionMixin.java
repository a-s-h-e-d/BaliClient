package dev.bali.mixin;

import dev.bali.Bali;
import dev.bali.event.events.Events;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Shadow public abstract NetworkSide getSide();

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void bali$onSend(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (getSide() != NetworkSide.CLIENTBOUND) return;
        if (Bali.EVENTS.post(new Events.PacketSend(packet)).isCancelled()) ci.cancel();
    }

    @Inject(method = "handlePacket", at = @At("HEAD"), cancellable = true)
    private static void bali$onReceive(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (listener.getSide() != NetworkSide.CLIENTBOUND) return;
        if (Bali.EVENTS.post(new Events.PacketReceive(packet)).isCancelled()) {
            ci.cancel();
            return;
        }
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> sub : bundle.getPackets()) Bali.EVENTS.post(new Events.PacketReceive(sub));
        }
    }
}
