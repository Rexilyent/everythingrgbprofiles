package com.everythingrgbprofile;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
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
import com.everythingrgbprofile.network.NetworkHandler;

/**
 * RGB Profile — Terraria-style reactive keyboard lighting for biomes, bosses,
 * and events. Your keyboard becomes a mood ring for Minecraft.
 *
 * <p>Corsair, Razer, Logitech and SteelSeries are driven through their own
 * vendor software, and anything else through OpenRGB; see
 * {@code BackendRegistry}. The only keyboard this project has been tested on
 * is a Corsair K70 RGB RAPIDFIRE.
 *
 * <h2>Why this class does almost nothing</h2>
 * Because it absolutely must not. Somewhere downstream of here we load a
 * vendor's native DLL through JNA and hand it raw pointers. That is a genuinely great
 * way to hard-crash the JVM with no stack trace, no crash report, and no
 * dignity — and it is <i>especially</i> great at doing that on a dedicated
 * server that has never seen a keyboard in its life.
 *
 * <p>So none of that pipeline gets touched until an ordered sequence of gating
 * checks has signed off on it. Every one of those checks lives in
 * {@link StartupGate}, and {@code StartupGate} is invoked from exactly one
 * place: {@link FMLClientSetupEvent}.
 *
 * <p>Note what we do <b>not</b> use: {@link FMLCommonSetupEvent}. "Common"
 * sounds harmless and neutral and it is neither — it runs on dedicated servers
 * too. Putting SDK code there is the single easiest way to turn this mod into
 * a server-crashing bug report, so we simply don't.
 */
@Mod(RGBProfileMod.MODID)
public final class RGBProfileMod {

    public static final String MODID = "everythingrgbprofiles";
    public static final Logger LOGGER = LogManager.getLogger("RGBProfile");

    public RGBProfileMod(IEventBus modEventBus, ModContainer modContainer) {
        // COMMON rather than CLIENT because some of these values (the sculk
        // relay's knobs) are read server-side. The file lands in config/ with
        // a name that says what it is, because nobody enjoys archaeology.
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, RGBProfileConfig.SPEC, "everythingrgbprofiles-common.toml");

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        // Payload registration has to be on the mod bus, and it's genuinely
        // side-agnostic — it registers a record and two codecs and touches
        // precisely zero JNA/SDK classes. Safe everywhere, cheap everywhere,
        // no notes.
        modEventBus.addListener(NetworkHandler::register);

        // Optional server-side relay. Careful: this goes on the GAME
        // bus (NeoForge.EVENT_BUS), not the mod bus — different bus, different
        // events, mixing them up gets you listeners that silently never fire
        // and an afternoon you don't get back.
        //
        // Client-only sculk detection turned out to be enough (see
        // SculkServerRelay's class doc), so this only logs one line at server
        // start. It is kept in case a future block needs server help.
        NeoForge.EVENT_BUS.register(new com.everythingrgbprofile.server.SculkServerRelay());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Reminder, because this is the exact spot where mods get this wrong:
        // this method runs on BOTH sides. Nothing SDK- or JNA-shaped is
        // allowed past this line. StartupGate's step 1 (the dedicated-server
        // check) exists to catch it if someone forgets, and so does the guard
        // at the clientSetup call site below. Two guards for one mistake,
        // which tells you how often that mistake gets made.
        //
        // Mod-compat detection IS fine here: it's a ModList lookup, i.e. a
        // couple of string comparisons against an already-built list. And the
        // server genuinely wants it too, since the sculk relay checks
        // soft-compat state.
        ModCompatRegistry.detectAll();
        Feature.logBuildStatus();
        // These were written as diagnostics and then never called, so neither
        // line had ever printed. Each says what a detected mod actually means
        // for the lighting, which is the question anyone asks first.
        LegendaryMonstersCompat.logStatusIfPresent();
        BetterEndIslandCompat.logStatusIfPresent();
        TwilightForestCompat.logStatusIfPresent();
        AetherCompat.logStatusIfPresent();
        LOGGER.info("RGB Profile common setup complete. Client-side lighting pipeline gating happens separately (see StartupGate).");
    }

    private void clientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            // Yes, this is unreachable. FMLClientSetupEvent fires on the
            // client, that's the whole personality of the event. StartupGate
            // then re-checks the exact same thing as its own first and
            // cheapest gate.
            //
            // Three redundant checks for one condition looks excessive right
            // up until the day some launcher or coremod does something
            // unexpected with the dist, at which point it's the reason we
            // didn't load a vendor DLL on a headless box. Cheap insurance.
            return;
        }
        // enqueueWork, not a direct call: client setup can be dispatched off
        // the main thread, and StartupGate ends up poking Minecraft state.
        // This defers it to the main thread where that's actually legal.
        event.enqueueWork(StartupGate::run);
    }
}
