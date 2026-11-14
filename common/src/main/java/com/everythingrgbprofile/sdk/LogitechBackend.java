package com.everythingrgbprofile.sdk;

import com.sun.jna.Native;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * The constants and the byte order come from Logitech's SDK header and their
 * own {@code logiPy} wrapper; the DLL location is where a current G HUB
 * install actually puts it, as opposed to where the docs say it goes.
 *
 * <h2>Threading matters more here than anywhere else</h2>
 * Logitech document {@code LogiLedInit} as initialising the SDK <i>for the
 * current thread</i>. Read that again, because it is unusual.
 *
 * <p>Every call in this class runs on the SDK worker thread, which
 * {@link LightingBackend} already guarantees. But it means this backend must
 * never be touched from anywhere else, up to and including one "quick" call
 * from the game thread while debugging something unrelated.
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
 * <p>What none of that can show is a key actually changing colour, because, as
 * {@link #push} explains at greater length, the SDK returns true quite happily
 * with no keyboard attached to the machine at all.
 *
 * <p>So if a Logitech board stays dark: G HUB has a setting controlling whether
 * applications are allowed to drive the lighting, and that is the first thing
 * worth checking before anything in this file is suspected.
 */
public final class LogitechBackend extends GridBackend {

    private LogitechLedSdk sdk;
    private boolean initialised;
    private final byte[] bitmap = new byte[LogitechLedSdk.BITMAP_SIZE];
    private int refusedInARow;

    /**
     * Where the logo and the badge get their colour: two cells of the top row,
     * out past Pause. The key table in Logitech's SDK doc stops that row at
     * Pause (column 15), so columns 16 to 20 are cells with no key in them that
     * every pattern has been faithfully colouring in anyway.
     */
    private static final int LOGO_CELL = 18;
    private static final int BADGE_CELL = 19;

    /** Last colour sent to each, as packed percentages. -1 is "nothing yet". */
    private int lastLogo = -1;
    private int lastBadge = -1;
    /** Goes false if the DLL turns out not to export the per-key call at all. */
    private boolean offBitmapLights = true;

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
        // Same belt-and-braces as CueSdkBridge: keep JNA on the jnidispatch
        // inside its own jar (Minecraft's) rather than a system-wide one.
        // Already JNA's default; see the note there.
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
        lastLogo = -1;
        lastBadge = -1;
        offBitmapLights = true;

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
            if (offBitmapLights) {
                lastLogo = pushOffBitmapLight(LogitechLedSdk.G_LOGO, rgb[LOGO_CELL], lastLogo);
                lastBadge = pushOffBitmapLight(LogitechLedSdk.G_BADGE, rgb[BADGE_CELL], lastBadge);
            }
        } else {
            refusedInARow++;
        }
        return true;
    }

    /**
     * The lights that are not keys.
     *
     * <p>The bitmap is keys and only keys. Anything else on the board has to be
     * asked for by name, and the header offers two names: {@code G_LOGO} and
     * {@code G_BADGE}. So those two ride along with every frame, wearing the
     * colour of a top-row cell each.
     *
     * <p>This went in for the G512, which has lighting next to its keys. Does
     * G HUB send either name to that lighting? Unknown. Logitech's doc does not
     * list the board and nobody here has one to look at, so this is the
     * documented route and a hope. On a board with no logo the call just has nothing to
     * do.
     *
     * <p>Sent only when the percentage actually changes, since each one is its
     * own separate trip into G HUB, and the return value is ignored on purpose:
     * the bitmap call a line earlier already said whether G HUB is listening.
     *
     * @return what to remember as the last colour sent
     */
    private int pushOffBitmapLight(int keyName, int rgb, int last) {
        int r = percent(rgb >> 16), g = percent(rgb >> 8), b = percent(rgb);
        int packed = r << 16 | g << 8 | b;
        if (packed == last) return last;
        try {
            sdk.LogiLedSetLightingForKeyWithKeyName(keyName, r, g, b);
        } catch (UnsatisfiedLinkError olderDll) {
            offBitmapLights = false;
        }
        return packed;
    }

    private static int percent(int channel) {
        return ((channel & 0xFF) * 100 + 127) / 255;
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
     * Letter and digit cells as {row, col}.
     *
     * <p>From the US-layout table in Logitech's SDK doc
     * ({@code LogitechGamingLEDSDK.pdf}, under
     * {@code LogiLedSetLightingFromBitmap}), which lists each key's byte offset
     * into the bitmap: Q is 172, A is 256, Z is 344. Aurora's
     * {@code Logitech_keyboardBitmapKeys} table carries the same offsets.
     * Divide by four for the cell, then by 21 for the row.
     * Row 4 starts a column later because column 1 there is the ISO key
     * between left shift and Z.
     *
     * <p>Until this table existed the backend named no keys at all, so a
     * warning aimed at one key had no key to find. GitHub user Dreips is the
     * one who discovered that. Thank you, genuinely.
     *
     * <p>Nobody here has watched this on a Logitech board yet. Step 7 of
     * {@code /rgbprofiles test} lights W, A, S and D and nothing else, which is
     * exactly the check for it.
     */
    @Override
    protected Map<Character, int[]> namedCells() {
        Map<Character, int[]> m = new HashMap<>();
        row(m, "1234567890", 1, 1);
        row(m, "QWERTYUIOP", 2, 1);
        row(m, "ASDFGHJKL", 3, 1);
        row(m, "ZXCVBNM", 4, 2);
        return m;
    }

    private static void row(Map<Character, int[]> m, String keys, int row, int firstCol) {
        for (int i = 0; i < keys.length(); i++) {
            m.put(keys.charAt(i), new int[]{row, firstCol + i});
        }
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
