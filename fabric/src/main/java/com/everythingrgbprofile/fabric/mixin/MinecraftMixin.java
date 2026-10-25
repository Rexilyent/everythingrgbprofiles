package com.everythingrgbprofile.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.everythingrgbprofile.event.ClientEventHandlers;

/**
 * Leaving a world. Fabric's stand-in for NeoForge's
 * {@code ClientPlayerNetworkEvent.LoggingOut}, from the same spot: inside
 * {@code disconnect}, just before the game mode is cleared.
 *
 * <p>A mixin rather than Fabric API's {@code DISCONNECT}, because that one is
 * raised from the connection's own disconnect handling, which can happen on
 * the network thread. The handler this calls resets caches the client thread
 * owns, and resetting them from another thread mid-tick is how you get a
 * board stuck showing the world you just left. {@code disconnect} always runs
 * on the client thread.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/Minecraft;gameMode:Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;",
                    opcode = Opcodes.PUTFIELD,
                    ordinal = 0))
    private void rgbprofiles$onDisconnect(CallbackInfo ci) {
        ClientEventHandlers.onLoggingOut();
    }
}
