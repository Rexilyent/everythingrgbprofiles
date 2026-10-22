package com.everythingrgbprofile.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.gating.StartupGate;

/**
 * The NeoForge front door. Registers the config, runs the shared setup, and
 * hands client startup to {@link StartupGate}. Everything with an opinion
 * lives in common; see {@link RGBProfileMod} for why the opinions stay out of
 * the entry point.
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
public final class RGBProfileNeoForge {

    public RGBProfileNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeoForgeConfig.register(modContainer);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        // Nothing gets registered on the game bus here, and no network payload
        // gets registered anywhere at all. An earlier version did both, for
        // the sculk relay. Commits 0807383 and 4695679 deleted it (the
        // receipts are in SculkBlockWatcher's class doc), and with it gone
        // this mod has no server-side behaviour and no network surface
        // whatsoever, which is precisely why a client carrying it can walk
        // onto any server, vanilla or NeoForge, with nothing to negotiate and
        // nothing to argue about. The client-side game events go through
        // NeoForgeClientEvents, which registers itself.
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Runs on BOTH sides. RGBProfileMod.init's doc says what that means
        // for anything added there, at length, because it matters.
        RGBProfileMod.init();
    }

    private void clientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
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
