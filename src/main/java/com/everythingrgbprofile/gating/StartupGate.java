package com.everythingrgbprofile.gating;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;

import java.nio.file.Path;
import java.util.Locale;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.sdk.BackendHealth;
import com.everythingrgbprofile.sdk.SdkWorkerThread;

/**
 * The bouncer. Three checks, cheapest first, and nothing touches JNA or a
 * native class until all three pass. This is the <b>only</b> place in the
 * entire mod allowed to decide "yes, we're talking to hardware today."
 *
 * <h2>Why the ordering is deliberate</h2>
 * Each step is more expensive than the one before it, so the common
 * "this machine will never use this mod" cases bail out having done almost
 * nothing:
 *
 * <ol>
 *   <li>An enum comparison.</li>
 *   <li>A boolean read.</li>
 *   <li>A system property lookup.</li>
 *   <li>Spawning the worker thread, which then asks each lighting backend
 *       whether its vendor software is present.</li>
 * </ol>
 *
 * <p>Someone on a dedicated server pays for an enum comparison and then this
 * class is done with them forever. "Zero cost when irrelevant", made literal.
 *
 * <h2>Why there is no "is iCUE running" check any more</h2>
 * An earlier version had a fourth step that scanned the process list for iCUE
 * and stopped if it wasn't found. That was a reasonable shortcut while Corsair
 * was the only backend. Once Razer, Logitech, SteelSeries and OpenRGB were
 * added, it became a bug: a user with a Razer keyboard and no iCUE would be
 * turned away here, before the code that knows how to talk to Razer ever ran.
 * Every backend's {@code connect} is already its own cheap presence check
 * (a file lookup, a localhost request, or a DLL path), so the gate no longer
 * guesses on their behalf.
 *
 * <p>Called exactly once, from {@code FMLClientSetupEvent} — which already
 * guarantees we're client-side. Step 1 re-checks anyway.
 */
public final class StartupGate {

    private static volatile boolean started = false;

    public static synchronized void run() {
        if (started) {
            // Idempotent, so a future manual "recheck" command could call
            // this again without starting a second worker thread and having
            // two of them fight over one hardware connection.
            return;
        }

        // --- Step 1: are we a dedicated server? ---------------------------
        // Unconditional. The lighting layer has zero server-side presence
        // regardless of the optional sculk relay. This is the third redundant
        // check of this exact condition (see RGBProfileMod) and it is staying
        // there, because the failure mode is "load a vendor DLL on a headless
        // Linux box" and no amount of redundancy is too much for that.
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            RGBProfileMod.LOGGER.debug("RGB Profile: dedicated server side, skipping lighting pipeline entirely.");
            return;
        }

        // --- Step 2: did the user turn us off? ----------------------------
        // INFO not DEBUG: someone who disabled this deliberately should be able
        // to confirm it took effect without enabling debug logging.
        if (!RGBProfileConfig.GENERAL_ENABLED.get()) {
            RGBProfileMod.LOGGER.info("RGB Profile: disabled via config (general.enabled = false), skipping lighting pipeline.");
            BackendHealth.gateClosed("The mod is turned off in the config, so no lighting software was tried.",
                    "Set enabled = true in the [general] section of everythingrgbprofiles-common.toml.");
            return;
        }

        // --- Step 3: is this Windows? -------------------------------------
        // Corsair's and Logitech's SDKs are Windows DLLs, and Razer Synapse
        // and SteelSeries GG are Windows desktop programs. OpenRGB is the one
        // backend that exists elsewhere, but it has not been tried on Linux or
        // macOS, so rather than ship an untested path the mod stays
        // Windows-only for now.
        //
        // Locale.ROOT on the lowercase because Turkish locales lowercase 'I'
        // to a dotless 'ı', which would make "WINDOWS".toLowerCase() not
        // contain "win". A well-known bug that has shipped in real software
        // more than once.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            RGBProfileMod.LOGGER.info("RGB Profile: non-Windows OS ({}) detected; the lighting backends are Windows-only, skipping.", os);
            BackendHealth.gateClosed("Keyboard lighting only works on Windows so far, and this is "
                    + System.getProperty("os.name", "another system") + ".", null);
            return;
        }

        // --- Step 4: hand over to the worker. -----------------------------
        // First point in the entire mod's lifetime that a JNA or native class
        // gets loaded, and it happens on the worker thread, not here.
        // Everything above exists to protect this line.
        //
        // Note there is deliberately NO retry or polling loop. If the vendor
        // software starts after Minecraft does, the mod stays off for the
        // session and the log says which backends were tried. A background
        // poll that might spring the lighting to life ten minutes into a
        // session is a worse experience than "restart the game", and a lot
        // more code.
        started = true;
        Path extractDir = com.everythingrgbprofile.RGBProfileFiles.dllExtractDirectory();
        SdkWorkerThread.startAsync(extractDir);
    }

    private StartupGate() {
    }
}
