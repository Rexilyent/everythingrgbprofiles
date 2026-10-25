package com.everythingrgbprofile.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.everythingrgbprofile.event.ClientEventHandlers;

/**
 * The player being swapped for a new one on respawn or a dimension change.
 * Fabric's stand-in for NeoForge's {@code ClientPlayerNetworkEvent.Clone}.
 *
 * <p>Same spot NeoForge fires from: right before the new player is added to
 * the level. By then {@code minecraft.player} already is the new player, so
 * that's what gets handed over.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

    @Inject(method = "handleRespawn",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;addEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void rgbprofiles$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ClientEventHandlers.onClientPlayerClone(Minecraft.getInstance().player);
    }
}
