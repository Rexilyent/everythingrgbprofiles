package com.everythingrgbprofile.fabric.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.everythingrgbprofile.event.ClientEventHandlers;

/**
 * Every toast as it's queued, for the advancement flash. Fabric's stand-in
 * for NeoForge's {@code ToastAddEvent}, from the same spot: the top of
 * {@code addToast}.
 *
 * <p>Fabric has no toast event, so a mod there that hides toasts has to
 * cancel this same method with a mixin of its own. Whether it runs before or
 * after this one is down to mixin ordering, so a toast hider can take the
 * flash with it here, which NeoForge's {@code receiveCanceled} avoids. Nothing
 * this side can fix that without fighting the other mod, and fighting another
 * mod over a toast is not a hill worth standing on.
 *
 * <p>{@code ToastComponent}, not {@code ToastManager}. Same class, older name:
 * 1.21.2 renamed it, so every branch from there up mixes into ToastManager and
 * this one gets to be the odd one out. The method didn't change, at least.
 */
@Mixin(ToastComponent.class)
abstract class ToastComponentMixin {

    @Inject(method = "addToast", at = @At("HEAD"))
    private void rgbprofiles$onAddToast(Toast toast, CallbackInfo ci) {
        ClientEventHandlers.onAdvancementToast(toast);
    }
}
