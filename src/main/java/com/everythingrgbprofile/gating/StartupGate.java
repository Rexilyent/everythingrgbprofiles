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
 * The bouncer. Four checks, cheapest first, and nothing in this mod touches
 * JNA or a native class until every one of them passes. This is the
 * <b>only</b> place allowed to decide "yeah alright, we're talking to hardware
 * today."
 *
 * <h2>Why the order is the order</h2>
 * Every step costs more than the one before it, so the enormous number of
 * people who are never going to use this mod bail out having done
 * approximately nothing:
 *
 * <ol>
 *   <li>An enum comparison.</li>
 *   <li>A boolean read.</li>
 *   <li>A system property lookup.</li>
 *   <li>Spawning the worker thread, which goes and asks each lighting backend
 *       whether its vendor software is even installed.</li>
 * </ol>
 *
 * <p>Somebody running a dedicated server pays for one enum comparison and then
 * never hears from this class again. "Zero cost when irrelevant", except
 * literally.
 *
 * <h2>RIP the iCUE check</h2>
 * An earlier version had an extra gate in here that scanned the running
 * process list for iCUE and stopped dead if it wasn't there. Which was fine
 * while Corsair was the only backend, because back then "no iCUE" and "no
 * lighting" genuinely were the same sentence.
 *
 * <p>Then Razer, Logitech, SteelSeries and OpenRGB got added and that check
 * became one vendor answering on behalf of five. No iCUE? Cool, nothing to
 * see here, everybody go home, pay no attention to the Synapse sitting right
 * there in the tray. It would have turned away a completely working Razer
 * setup without ever running a line of the Razer code.
 *
 * <p>So it's gone and the gate just lets all of them have a go. Every backend
 * already does its own cheap "are you even here" check inside {@code connect}:
 * a file lookup, a localhost request, or a DLL path. Asking each one directly
 * is more correct AND less code than having this class guess on their behalf,
 * which it was never qualified to do.
 *
 * <p>Called exactly once, from {@code FMLClientSetupEvent}, which already
 * guarantees we're on the client. Step 1 checks again regardless. See step 1.
 */
public final class StartupGate {

    private static volatile boolean started = false;

    public static synchronized void run() {
        if (started) {
            // Idempotent on purpose. If a "recheck my lighting" command ever
            // gets added, it can call this as many times as it likes without
            // spawning a second worker thread and starting a turf war over
            // one hardware connection.
            return;
        }

        // --- Step 1: are we a dedicated server? ---------------------------
        // Unconditional. The lighting layer has no server-side presence at
        // all, and since the sculk relay got deleted, neither does anything
        // else in this mod.
        //
        // Yes, this is the third time this exact condition gets checked: the
        // client setup event only firing on the client is the first, and the
        // guard in RGBProfileMod.clientSetup is the second. It's staying.
        // The failure mode being insured against is "load a vendor's Windows
        // DLL on somebody's headless Linux server", and there is no amount of
        // paranoia about that which counts as excessive.
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            RGBProfileMod.LOGGER.debug("RGB Profile: dedicated server side, skipping lighting pipeline entirely.");
            return;
        }

        // --- Step 2: did someone turn us off? -----------------------------
        // INFO rather than DEBUG. If you deliberately switched the mod off you
        // should be able to confirm that it took, without first having to
        // enable debug logging to investigate why the thing you disabled is
        // disabled.
        if (!RGBProfileConfig.GENERAL_ENABLED.get()) {
            RGBProfileMod.LOGGER.info("RGB Profile: disabled via config (general.enabled = false), skipping lighting pipeline.");
            BackendHealth.gateClosed("The mod is turned off in the config, so no lighting software was tried.",
                    "Set enabled = true in the [general] section of everythingrgbprofiles-common.toml.");
            return;
        }

        // --- Step 3: is this Windows? -------------------------------------
        // Corsair's and Logitech's SDKs ship as Windows DLLs, and Synapse and
        // SteelSeries GG are Windows desktop apps. OpenRGB is the one backend
        // that genuinely does exist on Linux and macOS, but nobody on this
        // project has ever tested it there, and shipping an untested path so
        // it can fail in exciting new ways on a stranger's machine helps
        // precisely nobody. Windows only for now.
        //
        // Locale.ROOT on the lowercase: in a Turkish locale, uppercase 'I'
        // lowercases to a dotless 'ı', so "WINDOWS".toLowerCase() comes back
        // as "wındows", which does not contain "win", and the mod quietly
        // switches itself off for an entire country.
        //
        // Full disclosure though: os.name is "Windows 11" here, and its 'i' is
        // already lowercase, so it survives a Turkish lowercase completely
        // fine. This particular call was almost certainly never broken. It's
        // Locale.ROOT anyway because it costs nothing, and because the day
        // somebody copies this pattern somewhere the input isn't already
        // conveniently shaped is the day it stops being free.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            RGBProfileMod.LOGGER.info("RGB Profile: non-Windows OS ({}) detected; the lighting backends are Windows-only, skipping.", os);
            BackendHealth.gateClosed("Keyboard lighting only works on Windows so far, and this is "
                    + System.getProperty("os.name", "another system") + ".", null);
            return;
        }

        // --- Step 4: hand off to the worker. ------------------------------
        // The first moment in this mod's entire lifetime that a JNA or native
        // class gets loaded, and it happens over on the worker thread rather
        // than here. Every line above exists to protect this one.
        //
        // There is deliberately no retry or polling loop. Start iCUE after
        // Minecraft and the mod stays dark for that session, with the log
        // saying which backends it tried. Having your keyboard spontaneously
        // burst into colour ten minutes in, because a background poll finally
        // caught something, is a worse experience than "restart the game" and
        // considerably more code to get wrong.
        started = true;
        Path extractDir = com.everythingrgbprofile.RGBProfileFiles.dllExtractDirectory();
        SdkWorkerThread.startAsync(extractDir);
    }

    private StartupGate() {
    }
}
