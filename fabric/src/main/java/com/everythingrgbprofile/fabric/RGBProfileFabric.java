package com.everythingrgbprofile.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.event.ClientEventHandlers;
import com.everythingrgbprofile.event.DiagnosticsCommands;
import com.everythingrgbprofile.fabric.config.FabricConfig;
import com.everythingrgbprofile.gating.StartupGate;

/**
 * The Fabric front door, and the Fabric twin of {@code RGBProfileNeoForge}:
 * load the config, run the shared setup, hand client startup to
 * {@link StartupGate}. Everything with an opinion lives in common.
 *
 * <p>Client-only from top to bottom. {@code fabric.mod.json} marks the whole
 * jar {@code "environment": "client"}, so a dedicated server skips it without
 * loading a single class, and this is a client entrypoint on top of that.
 * NeoForge's version has to be careful about common setup running on servers;
 * this one never gets the chance to be careless.
 *
 * <p>The game hooks come from two places. Fabric API covers the client tick,
 * startup and the client commands. The rest are mixins in {@code fabric.mixin},
 * each sitting on the same spot in vanilla that NeoForge fires its event
 * from, because for a couple of them (the level load especially) the timing
 * is the entire feature.
 */
public final class RGBProfileFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // First, because everything after it reads the config. NeoForge has
        // the file loaded before common setup runs, so this keeps the order.
        FabricConfig.load();
        RGBProfileMod.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FabricConfig.reloadIfChanged();
            ClientEventHandlers.onClientTick();
            DiagnosticsCommands.onClientTick();
        });

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> DiagnosticsCommands.register(dispatcher));

        // NeoForge runs StartupGate from client setup, on the main thread once
        // every mod's setup is done. CLIENT_STARTED is Fabric's nearest
        // equivalent: main thread, after every mod has initialised.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> StartupGate.run());
    }
}
