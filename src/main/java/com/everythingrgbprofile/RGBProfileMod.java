package com.everythingrgbprofile;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.compat.betterendisland.BetterEndIslandCompat;
import com.everythingrgbprofile.compat.legendarymonsters.LegendaryMonstersCompat;
import com.everythingrgbprofile.compat.aether.AetherCompat;
import com.everythingrgbprofile.compat.twilightforest.TwilightForestCompat;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.config.RGBProfileConfig;
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
 * from exactly one place: {@link FMLClientSetupEvent}.
 *
 * <h2>Yes, this uses FMLCommonSetupEvent</h2>
 * {@link #commonSetup} is right there, so let's be accurate about it. What
 * this class does not do is put anything SDK-shaped inside it, because
 * "common" sounds cosy and neutral when what it actually means is "also runs
 * on dedicated servers". Loading a lighting SDK in there is the single most
 * efficient way to convert this mod into somebody's server crash report. Mod
 * compat detection and a few log lines, that's the lot.
 */
@Mod(RGBProfileMod.MODID)
public final class RGBProfileMod {

    public static final String MODID = "everythingrgbprofiles";
    public static final Logger LOGGER = LogManager.getLogger("RGBProfile");

    public RGBProfileMod(IEventBus modEventBus, ModContainer modContainer) {
        // COMMON rather than CLIENT. An earlier version had an optional
        // server-side sculk relay that read a couple of these values, and
        // COMMON was what let it. That relay is gone, because watching block
        // state on the client turned out to do the entire job by itself (see
        // SculkBlockWatcher), so nothing reads this config server-side now.
        //
        // It stays COMMON regardless. The only thing switching to CLIENT would
        // achieve is a dedicated server no longer bothering to load a file it
        // was already ignoring, and then the filename would be lying about
        // itself. It lands in config/ under a name that says what it is,
        // because nobody enjoys archaeology.
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, RGBProfileConfig.SPEC, "everythingrgbprofiles-common.toml");

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        // Nothing gets registered on the game bus here, and no network payload
        // gets registered anywhere at all. An earlier version did both, for
        // the sculk relay. With that deleted this mod has no server-side
        // behaviour and no network surface whatsoever, which is precisely why
        // a client carrying it can walk onto any server, vanilla or NeoForge,
        // with nothing to negotiate and nothing to argue about.
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Saying this out loud because this is the exact spot where mods get
        // it wrong: this method runs on BOTH sides. Nothing SDK-shaped or
        // JNA-shaped goes in here, and nothing would catch it if it did. The
        // dist guard in clientSetup and StartupGate's step 1 only protect the
        // client path; code added to this method never goes near either of
        // them. This comment is the whole backstop.
        //
        // Mod-compat detection is fine here. It's a ModList lookup, meaning a
        // handful of string comparisons against a list that has already been
        // built. It runs server-side too, where the only thing it accomplishes
        // is logging which supported mods are present, which happens to be the
        // first line anybody wants when a pack's lighting is doing something
        // baffling.
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

    private void clientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            // Yes, this is unreachable. FMLClientSetupEvent fires on the
            // client; that is the entire personality of the event. StartupGate
            // then goes and checks the same condition again as its own first
            // and cheapest gate.
            //
            // Three redundant checks for one condition looks ridiculous right
            // up until some launcher or coremod does something creative with
            // the dist, at which point it is the reason a vendor DLL did not
            // get loaded on a headless box. Cheap insurance.
            return;
        }
        // enqueueWork rather than just calling it: client setup handlers run
        // in parallel, off the main thread, alongside every other mod's. This
        // runs StartupGate on the main thread once that's over instead, so the
        // one place that decides whether to start the hardware pipeline isn't
        // doing it in the middle of everybody else's setup.
        event.enqueueWork(StartupGate::run);
    }
}
