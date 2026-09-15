package com.everythingrgbprofile.sdk;

import com.sun.jna.Native;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Logitech G HUB, through the LED Illumination SDK.
 *
 * <p>The one native backend besides Corsair. There is no local REST server for
 * Logitech lighting; the SDK is a DLL that G HUB installs next to itself and
 * which talks to G HUB over its own channel. Nothing is bundled with this mod —
 * if G HUB is installed, the DLL is already on the machine.
 *
 * <p>Per-key control is a 21x6 bitmap in B, G, R, A byte order, set with
 * {@code LogiLedSetLightingFromBitmap} after targeting per-key RGB devices.
 * Constants and byte order are from Logitech's SDK header and their own
 * {@code logiPy} wrapper; the DLL location is where a current G HUB install
 * actually puts it.
 *
 * <h2>Threading matters more than usual here</h2>
 * Logitech document {@code LogiLedInit} as initialising the SDK <i>for the
 * current thread</i>. Every call in this class runs on the SDK worker thread,
 * which {@link LightingBackend} already guarantees — but it means this backend
 * must never be touched from anywhere else, including a "quick" call from the
 * game thread for debugging.
 *
 * <h2>What has been checked against a real G HUB install</h2>
 * The only keyboard this project has been tested on is a Corsair K70 RGB
 * RAPIDFIRE, so this backend was checked against a real G HUB install with no
 * Logitech keyboard attached. Against it: the
 * DLL is where {@link #findDll} now looks (the {@code sdks} subfolder — see
 * there for why that mattered), it is a 64-bit build exporting every function
 * in {@link LogitechLedSdk}, init, save, target, bitmap and restore all return
 * true, and forty-nine changing frames went through this class without a
 * refusal.
 *
 * <p>What that cannot show is a key changing colour, because, as {@link #push}
 * explains, the SDK says true even with no keyboard attached. If a Logitech
 * board stays dark, G HUB has a setting that allows applications to control
 * lighting, and that is the first thing to check.
 */
public final class LogitechBackend extends GridBackend {

    private LogitechLedSdk sdk;
    private boolean initialised;
    private final byte[] bitmap = new byte[LogitechLedSdk.BITMAP_SIZE];
    private int refusedInARow;

    public LogitechBackend() {
        // Native call, no transport to protect: push every frame the render
        // loop produces. No keep-alive exists or is needed.
        super("logitech:keyboard", LogitechLedSdk.BITMAP_WIDTH, LogitechLedSdk.BITMAP_HEIGHT,
                60, 3_600_000L, true);
    }

    @Override
    public String id() {
        return "logitech";
    }

    @Override
    public String displayName() {
        return "Logitech G HUB";
    }

    @Override
    protected String modelName() {
        return "Logitech LED SDK keyboard";
    }

    @Override
    protected void open() throws BackendHealth.Unavailable {
        Path dll = findDll();
        if (dll == null) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_INSTALLED,
                    "Logitech G HUB does not look installed: its LED SDK was not found.",
                    "If you have a Logitech keyboard, install Logitech G HUB. If not, ignore this line.",
                    "Looked for: " + candidates());
        }
        // Loading the DLL and every call below run native code that can take
        // the game down with it, so they run inside the crash guard.
        List<Path> libraries = List.of(dll);
        NativeCrashGuard.checkBefore(workDir(), id(), displayName(), libraries);
        NativeCrashGuard.enter(workDir(), id(), libraries);
        try {
            openNative(dll);
        } finally {
            NativeCrashGuard.exit(workDir(), id());
        }
    }

    private void openNative(Path dll) throws BackendHealth.Unavailable {
        // Same reason CueSdkBridge sets it: without it JNA may prefer a
        // system-wide jnidispatch over the one bundled with the mod.
        System.setProperty("jna.nosys", "true");
        try {
            sdk = Native.load(dll.toString(), LogitechLedSdk.class);
        } catch (UnsatisfiedLinkError e) {
            if ("32".equals(System.getProperty("sun.arch.data.model"))) throw e;
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "G HUB's LED SDK was found but could not be loaded.",
                    "Try reinstalling G HUB. If that does not help, " + BackendHealth.SEND_REPORT,
                    dll + "\n" + BackendHealth.describe(e));
        }

        boolean ok;
        try {
            ok = sdk.LogiLedInitWithName("Everything RGB Profiles") != 0;
        } catch (UnsatisfiedLinkError olderDll) {
            ok = sdk.LogiLedInit() != 0;
        }
        if (!ok) {
            // G HUB installed but closed is by far the likeliest reason: the
            // DLL is on disk either way, and init is where it looks for G HUB.
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_RUNNING,
                    "G HUB is installed, but its LED SDK would not start, which usually means G HUB is not running.",
                    "Start G HUB, check its settings allow games and applications to control lighting, "
                            + "then restart Minecraft.",
                    "SDK: " + dll);
        }
        initialised = true;

        // Snapshot whatever the user had, so shutdown can put it back.
        sdk.LogiLedSaveCurrentLighting();
        sdk.LogiLedSetTargetDevice(LogitechLedSdk.LOGI_DEVICETYPE_PERKEY_RGB);
    }

    @Override
    protected String notRunningSummary() {
        return "G HUB did not answer, so it is probably not running.";
    }

    @Override
    protected String notRunningFix() {
        return "Start G HUB, then restart Minecraft.";
    }

    @Override
    protected String transportDetail() {
        return "G HUB refused " + refusedInARow + " frames in a row.";
    }

    @Override
    protected boolean push(int[] rgb) {
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i];
            int o = i * 4;
            bitmap[o] = (byte) (c & 0xFF);             // blue
            bitmap[o + 1] = (byte) ((c >> 8) & 0xFF);  // green
            bitmap[o + 2] = (byte) ((c >> 16) & 0xFF); // red
            bitmap[o + 3] = (byte) 0xFF;               // alpha
        }
        // What the return value means, from watching a real G HUB: it is true
        // on a machine with no Logitech keyboard at all. So it says G HUB took
        // the call, not that any key changed, and it can never detect a missing
        // keyboard. A false is therefore G HUB genuinely refusing — closed or
        // restarting — and a long enough run of those means the session is gone.
        if (sdk.LogiLedSetLightingFromBitmap(bitmap) != 0) {
            refusedInARow = 0;
        } else {
            refusedInARow++;
        }
        return true;
    }

    @Override
    protected boolean transportLost() {
        // A couple of seconds of refusals at the render loop's rate: long enough
        // to ride out G HUB switching profiles, short enough not to keep calling
        // into a dead session for the rest of the game.
        return refusedInARow >= 60;
    }

    @Override
    protected void close() {
        if (sdk == null) return;
        if (initialised) {
            try {
                sdk.LogiLedRestoreLighting();
            } catch (Throwable ignored) {
            }
        }
        try {
            sdk.LogiLedShutdown();
        } catch (Throwable ignored) {
        }
        initialised = false;
    }

    /**
     * G HUB first, then the Logitech Gaming Software it replaced.
     *
     * <p>Current G HUB keeps the DLL in an {@code sdks} subfolder. The path most
     * write-ups give — straight under {@code LGHUB} — is from older builds, and
     * checking only that one made a fresh G HUB install report "not available"
     * in zero milliseconds, which is to say it never even tried. Both are kept,
     * newest first.
     */
    private static Path findDll() {
        for (Path p : candidates()) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** Every place the DLL is looked for, newest install layout first. */
    private static List<Path> candidates() {
        String programFiles = System.getenv("ProgramW6432");
        if (programFiles == null) programFiles = System.getenv("ProgramFiles");
        if (programFiles == null) programFiles = "C:\\Program Files";
        return List.of(
                Path.of(programFiles, "LGHUB", "sdks", "sdk_legacy_led_x64.dll"),
                Path.of(programFiles, "LGHUB", "sdk_legacy_led_x64.dll"),
                Path.of(programFiles, "Logitech Gaming Software", "SDK", "LED", "x64", "LogitechLed.dll"));
    }
}
