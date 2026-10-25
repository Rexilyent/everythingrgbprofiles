package com.everythingrgbprofile.fabric.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.everythingrgbprofile.event.ClientEventHandlers;

/**
 * A new client level, the moment it exists. Fabric's stand-in for NeoForge's
 * {@code LevelEvent.Load}, which NeoForge fires from the end of this exact
 * constructor.
 *
 * <p>Not Fabric API's world-change event, tempting as it is. That one fires
 * after {@code Minecraft#setLevel}, which puts it behind Iris and friends
 * rebuilding everything on a dimension change, and being behind them is the
 * precise bug {@code ClientEventHandlers.onLevelLoad} exists to avoid. Its doc
 * has the measurements.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void rgbprofiles$onConstructed(CallbackInfo ci) {
        ClientEventHandlers.onLevelLoad((ClientLevel) (Object) this);
    }
}
