package com.everythingrgbprofile.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ToastAddEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.event.ClientEventHandlers;
import com.everythingrgbprofile.event.DiagnosticsCommands;

/**
 * NeoForge's events, forwarded to common. No logic lives here and none
 * should: every method is one line of "this happened, go deal with it", and
 * the dealing happens in {@link ClientEventHandlers}, where the Fabric side
 * can reach it too. The docs on the handlers there say why each hook is the
 * hook it is.
 */
@EventBusSubscriber(modid = RGBProfileMod.MODID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientEventHandlers.onClientTick();
        DiagnosticsCommands.onClientTick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientEventHandlers.onLoggingOut();
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        ClientEventHandlers.onRaidHorn(event.getOriginalSound());
    }

    // receiveCanceled, because mods hide toasts by cancelling exactly this
    // event, and a hidden popup is still an earned advancement.
    @SubscribeEvent(receiveCanceled = true)
    public static void onToastAdd(ToastAddEvent event) {
        ClientEventHandlers.onAdvancementToast(event.getToast());
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        ClientEventHandlers.onLevelLoad(event.getLevel());
    }

    @SubscribeEvent
    public static void onClientPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        ClientEventHandlers.onClientPlayerClone(event.getNewPlayer());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        DiagnosticsCommands.register(event.getDispatcher());
    }

    private NeoForgeClientEvents() {
    }
}
