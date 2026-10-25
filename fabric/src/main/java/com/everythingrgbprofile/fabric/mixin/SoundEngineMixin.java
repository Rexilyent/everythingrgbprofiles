package com.everythingrgbprofile.fabric.mixin;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.everythingrgbprofile.event.ClientEventHandlers;

/**
 * Every sound the client is about to play, for the raid horn. Fabric's
 * stand-in for NeoForge's {@code PlaySoundEvent}.
 *
 * <p>Same spot NeoForge fires from: after the "is the sound engine even
 * running" check and before the sound gets a say in whether it plays. So,
 * like on NeoForge, the board lights up for somebody playing muted, and the
 * sound handed over is the original, before another mod can swap or silence
 * it.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {

    @Inject(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SoundInstance;canPlaySound()Z"))
    private void rgbprofiles$beforePlay(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        ClientEventHandlers.onRaidHorn(instance);
    }
}
