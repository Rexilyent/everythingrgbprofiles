package com.everythingrgbprofile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.compat.betterendisland.BetterEndIslandCompat;
import com.everythingrgbprofile.compat.legendarymonsters.LegendaryMonstersCompat;
import com.everythingrgbprofile.compat.aether.AetherCompat;
import com.everythingrgbprofile.compat.twilightforest.TwilightForestCompat;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.gating.StartupGate;

/**
 * RGB Profile: Terraria-style reactive keyboard lighting for biomes, bosses
 * and events. Your keyboard becomes a mood ring for Minecraft.
 *
 * <p>Corsair, Razer, Logitech and SteelSeries get driven through their own
 * vendor software, everything else through OpenRGB; see
 * {@code BackendRegistry}. The only keyboard this project has been tested on
 * is a Corsair K70 RGB RAPIDFIRE.
 *
 * <h2>Why this class does almost nothing</h2>
 * Because it really, really must not. Somewhere downstream of here this mod
 * loads a vendor's native DLL through JNA and starts handing it raw pointers,
 * which is an outstanding way to hard-crash the JVM with no stack trace, no
 * crash report and no dignity. It is especially outstanding at doing that on
 * a dedicated server that has never been within a mile of a keyboard.
 *
 * <p>So none of that pipeline gets touched until a run of gating checks has
 * signed off on it. All of them live in {@link StartupGate}, which gets called
 * from exactly one place per loader: its client startup.
 *
 * <h2>Where the loaders come in</h2>
 * Not here. The entry points live in the loader projects,
 * {@code RGBProfileNeoForge} and {@code RGBProfileFabric}, and each one does
 * the same three things in its own dialect: load the config, call
 * {@link #init}, and hand client startup to {@link StartupGate}. Nothing in
 * this class knows or cares which loader it is running on, and the build for
 * common has no loader on its classpath, so it couldn't care if it tried.
 */
public final class RGBProfileMod {

    public static final String MODID = "everythingrgbprofiles";
    public static final Logger LOGGER = LogManager.getLogger("RGBProfile");

    /**
     * The part of startup that is the same on every loader.
     *
     * <p>Saying this out loud because this is the exact spot where mods get it
     * wrong: on NeoForge this runs on BOTH sides, from common setup. Nothing
     * SDK-shaped or JNA-shaped goes in here, and nothing would catch it if it
     * did. The dist guard in front of StartupGate and StartupGate's step 1 only
     * protect the client path; code added to this method never goes near
     * either of them. This comment is the whole backstop. (Fabric only ever
     * calls it on the client, which is nice, and also irrelevant, because the
     * NeoForge jar still exists.)
     *
     * <p>Mod-compat detection is fine here. It's a mod-list lookup, meaning a
     * handful of string comparisons against a list that has already been
     * built. On a server the only thing it accomplishes is logging which
     * supported mods are present, which happens to be the first line anybody
     * wants when a pack's lighting is doing something baffling.
     */
    public static void init() {
        ModCompatRegistry.detectAll();
        Feature.logBuildStatus();
        // Every one of these was written as a diagnostic and then never
        // actually wired up, so they had collectively printed nothing to
        // nobody. Each one says what a detected mod actually means for the
        // lighting, which is the question everybody asks first.
        LegendaryMonstersCompat.logStatusIfPresent();
        BetterEndIslandCompat.logStatusIfPresent();
        TwilightForestCompat.logStatusIfPresent();
        AetherCompat.logStatusIfPresent();
        LOGGER.info("RGB Profile common setup complete. Client-side lighting pipeline gating happens separately (see StartupGate).");
    }

    private RGBProfileMod() {
    }
}
