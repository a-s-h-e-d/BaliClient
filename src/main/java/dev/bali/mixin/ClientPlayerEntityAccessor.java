package dev.bali.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {
    /** The sprint state the server was last told (START/STOP_SPRINTING). */
    @Accessor("lastSprinting")
    boolean bali$lastSprinting();
}
