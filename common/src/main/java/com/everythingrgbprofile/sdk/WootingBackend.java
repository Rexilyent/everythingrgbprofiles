package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Wooting keyboards, through Wooting's own RGB SDK.
 *
 * <p>No vendor app in the middle this time. The SDK is a small DLL that finds
 * the keyboard over USB HID and writes colours to it directly, so Wootility
 * does not need to be running, or even installed. The mod bundles the DLL:
 * Wooting's v1.8.0 release build, unmodified, under the Mozilla Public License
 * 2.0. The notice and the link to its source sit right next to it in the
 * jar's {@code native} folder, because the MPL asks for exactly that and it is
 * a very reasonable ask.
 *
 * <h2>Why this is not a {@link GridBackend}</h2>
 * It looks like one. Every Wooting board is drawn into the same 6x21 matrix,
 * same as Logitech's rectangle. The difference is that this SDK can actually
 * see what is plugged in. It enumerates real devices, tells you which model
 * each one is and how many matrix columns it uses, and comes back empty when
 * there is no Wooting on the desk. The grid vendors would answer "connected"
 * to an empty USB hub. So this gets per-device surfaces and the honest kind of
 * "no devices" diagnosis, and {@link BackendRegistry} can trust it like it
 * trusts Corsair.
 *
 * <h2>Geometry</h2>
 * Cell (row, col) is the position, the same approximation the grid backends
 * make, and a {@link KeyLayout} matched on the model name can correct it
 * using {@code R2C6}-style cell names. Every cell inside the board's reported
 * width becomes an LED, including the holes in Wooting's matrix (there is no
 * key at row 0, column 1, for one). Lighting a key that does not exist costs
 * nothing, and guessing wrong about which holes a 60HE has would cost a real
 * key.
 *
 * <p>Colour depth is worth knowing about: the SDK packs every colour to 16-bit
 * RGB565 before it goes over the wire. Five bits of red, six of green, five of
 * blue. Bright patterns will not notice. Very slow, very dark fades can step
 * visibly, and nothing on this side of the DLL can fix that.
 *
 * <h2>What has been checked</h2>
 * The only keyboard this project has been tested on is a Corsair K70 RGB
 * RAPIDFIRE. No Wooting hardware has been anywhere near this file. What has
 * been checked: the bundled DLL matches Wooting's signed release byte for
 * byte, it exports every function in {@link WootingRgbSdk}, and every call
 * here was read against the SDK's own source rather than its header comments,
 * which is how the NULL-pointer trap in {@link WootingRgbSdk} got found. Whether
 * a single key actually changes colour is unproven. Experimental.
 */
public final class WootingBackend implements LightingBackend {

    private static final String DLL_RESOURCE = "/everythingrgbprofiles/native/wooting-rgb-sdk64.dll";
    private static final String DLL_FILE_NAME = "wooting-rgb-sdk64.dll";

    /**
     * Frame-rate cap. One frame per board is a single USB write the SDK builds
     * itself, so this sits with Logitech's native call rather than the
     * cautious 30 of the COM backends. Untimed on real hardware, like
     * everything else in here.
     */
    private static final long MIN_PUSH_NANOS = 1_000_000_000L / 60;

    /** Boards narrower than this do not follow the full-size diagram {@link #namedCells} is built from. */
    private static final int NAMED_KEYS_MIN_COLS = 17;

    private static final String[] LAYOUTS = {"ANSI", "ISO", "JIS", "ANSI split", "ISO split"};

    private static final class Device {
        byte index;
        String id;
        String model;
        KeyGrid.DeviceClass deviceClass;
        int rows;
        int cols;
        /** What {@link WootingRgbSdk#wooting_rgb_array_set_full} gets: R, G, B per cell, always 6x21. */
        final byte[] buffer = new byte[WootingRgbSdk.FULL_BUFFER_SIZE];
        boolean dirty = true;
    }

    private WootingRgbSdk sdk;
    private final List<Device> devices = new ArrayList<>();
    private final Map<String, Device> devicesById = new HashMap<>();
    private final List<String> skippedByClass = new ArrayList<>();
    private final StringBuilder inventory = new StringBuilder();
    private KeyGrid grid = KeyGrid.empty();
    private boolean connected;
    private boolean pending;
    /** True once a write failed and the SDK closed every handle on us. Shutdown has to reopen to reset. */
    private boolean lostMidGame;
    private long lastPushNanos;

    @Override
    public String id() {
        return "wooting";
    }

    @Override
    public String displayName() {
        return "Wooting";
    }

    @Override
    public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        try {
            Path dll = CueSdkBridge.extractBundledDll(DLL_RESOURCE, DLL_FILE_NAME, workDir);
            if (dll == null) {
                throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                        "This copy of the mod is missing its bundled Wooting SDK.",
                        "Download the mod again from its official page. If that does not help, "
                                + BackendHealth.SEND_REPORT,
                        DLL_RESOURCE + " is not in the jar");
            }
            List<Path> libraries = List.of(dll);
            NativeCrashGuard.checkBefore(workDir, id(), displayName(), libraries);
            NativeCrashGuard.enter(workDir, id(), libraries);
            try {
                open(dll, deviceEnabled);
            } finally {
                NativeCrashGuard.exit(workDir, id());
            }
        } catch (Throwable t) {
            // Throwable for the usual native reason: a DLL that will not load
            // throws an Error, and an Error sails straight past catch (Exception).
            BackendHealth.recordFailure(id(), displayName(), "while connecting", t,
                    "Wooting's SDK did not answer.", BackendHealth.SEND_REPORT);
            shutdown();
            return false;
        }

        connected = true;
        StringBuilder summary = new StringBuilder();
        for (Device d : devices) {
            summary.append(summary.isEmpty() ? "" : ", ").append(d.model)
                    .append(" (").append(d.cols).append('x').append(d.rows).append(')');
        }
        if (!skippedByClass.isEmpty()) summary.append("; skipped as turned off in [devices]: ").append(skippedByClass);
        BackendHealth.record(id(), displayName(), BackendHealth.State.CONNECTED, summary.append('.').toString(),
                null, inventory.toString());
        return true;
    }

    private void open(Path dll, Predicate<KeyGrid.DeviceClass> deviceEnabled) throws BackendHealth.Unavailable {
        // Same as LogitechBackend: JNA's jnidispatch from its own jar, not
        // one some other program left on the system path.
        System.setProperty("jna.nosys", "true");
        inventory.append("SDK: ").append(dll).append('\n');
        try {
            sdk = Native.load(dll.toString(), WootingRgbSdk.class);
        } catch (UnsatisfiedLinkError e) {
            if ("32".equals(System.getProperty("sun.arch.data.model"))) throw e;
            // Its only dependency outside Windows itself is the Visual C++
            // runtime, which Java normally has loaded already. Normally.
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "Wooting's SDK, bundled with this mod, could not be loaded.",
                    "Installing the latest Microsoft Visual C++ Redistributable (x64) may fix this. If not, "
                            + BackendHealth.SEND_REPORT,
                    inventory + BackendHealth.describe(e));
        }

        // Has to be the first call. See WootingRgbSdk for what happens otherwise.
        //
        // NOT_INSTALLED, not NO_DEVICES, on purpose. The DLL ships with the mod,
        // so this runs on every PC that has the mod, and nearly all of those
        // have never seen a Wooting. NO_DEVICES counts as a problem worth
        // flagging; "there is no Wooting here" is just a PC.
        if (sdk.wooting_rgb_kbd_connected() == 0) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_INSTALLED,
                    "No Wooting keyboard found.",
                    "If you have one, check it is plugged in and shows up in Wootility, then restart Minecraft. "
                            + "If not, ignore this line.",
                    inventory.toString());
        }

        int count = sdk.wooting_usb_device_count() & 0xFF;
        inventory.append(count).append(" device(s)\n");
        for (int i = 0; i < count; i++) {
            try {
                readDevice(i, deviceEnabled);
            } catch (Throwable t) {
                inventory.append("  [").append(i).append("] could not be read: ").append(t).append('\n');
                BackendHealth.problem(displayName(), "Device " + i + " of " + count
                        + " could not be read and was skipped.", t);
            }
        }

        if (devices.isEmpty()) {
            if (!skippedByClass.isEmpty()) {
                throw new BackendHealth.Unavailable(BackendHealth.State.DISABLED,
                        "Every Wooting device found is a type turned off in the config: " + skippedByClass + ".",
                        "Turn on the matching setting in the [devices] section of "
                                + "everythingrgbprofiles-common.toml, for example keyboardEnabled.",
                        inventory.toString());
            }
            // Not a bug: the plain UwU has no RGB, and the SDK lists it anyway.
            throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                    "Found " + count + " Wooting device(s), but none with lighting this mod can drive.",
                    "If you have an RGB Wooting board, " + BackendHealth.SEND_REPORT,
                    inventory.toString());
        }

        KeyGrid.Builder builder = new KeyGrid.Builder();
        boolean namedKeysGiven = false;
        for (Device d : devices) {
            List<double[]> fallback = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int r = 0; r < d.rows; r++) {
                for (int c = 0; c < d.cols; c++) {
                    // luid = the cell's index in the SDK's 6x21 matrix, whatever
                    // the board's width, so applyFrame can find the bytes at luid * 3.
                    fallback.add(new double[]{r * WootingRgbSdk.COLS + c, c, r});
                    names.add("R" + r + "C" + c);
                }
            }
            List<double[]> laidOut = KeyLayout.positionsFor(d.model, names, fallback);
            builder.beginDevice(d.id, d.model, d.deviceClass);
            for (double[] p : laidOut) builder.addLed((int) p[0], p[1], p[2]);
            builder.endDevice();

            // One board's letters only. Two Wootings both claiming "W" would
            // leave which one lights up to map iteration order.
            if (!namedKeysGiven && d.deviceClass == KeyGrid.DeviceClass.KEYBOARD && d.cols >= NAMED_KEYS_MIN_COLS) {
                for (Map.Entry<Character, int[]> e : namedCells().entrySet()) {
                    int[] rc = e.getValue();
                    builder.addNamedKey(e.getKey(), new KeyGrid.LedRef(d.id, rc[0] * WootingRgbSdk.COLS + rc[1]));
                }
                namedKeysGiven = true;
            }
        }
        grid = builder.build();
    }

    /**
     * Reads one device's {@code WOOTING_USB_META} by offset.
     *
     * <p>By offset rather than as a JNA Structure because half the fields are
     * C {@code bool}s, which a Structure would want to treat as four bytes,
     * and getting that wrong shifts every field after it. The x64 MSVC layout,
     * worked out from the header:
     * <pre>
     *   0  bool         connected
     *   8  const char*  model
     *  16  uint8_t      max_rows
     *  17  uint8_t      max_columns
     *  18  uint8_t      led_index_max
     *  20  int (enum)   device_type
     *  24  bool         v2_interface
     *  28  int (enum)   layout
     * </pre>
     * Nothing past {@code layout} is read. The rest is USB packet trivia the
     * SDK handles on its own.
     */
    private void readDevice(int index, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        Pointer meta = sdk.wooting_usb_get_device_meta((byte) index);
        if (meta == null) {
            inventory.append("  [").append(index).append("] no info\n");
            return;
        }
        boolean live = meta.getByte(0) != 0;
        Pointer modelPtr = meta.getPointer(8);
        String model = modelPtr != null ? modelPtr.getString(0, "US-ASCII") : null;
        if (model == null || model.isBlank()) model = "Wooting device " + index;
        int rows = Math.min(meta.getByte(16) & 0xFF, WootingRgbSdk.ROWS);
        int cols = Math.min(meta.getByte(17) & 0xFF, WootingRgbSdk.COLS);
        int ledIndexMax = meta.getByte(18) & 0xFF;
        int type = meta.getInt(20);
        int layout = meta.getInt(28);
        KeyGrid.DeviceClass cls = type == WootingRgbSdk.DEVICE_KEYPAD_3KEY
                ? KeyGrid.DeviceClass.OTHER : KeyGrid.DeviceClass.KEYBOARD;

        inventory.append("  [").append(index).append("] \"").append(model).append("\" type ").append(type)
                .append(" (").append(cls).append("), ").append(cols).append('x').append(rows)
                .append(", layout ").append(layout >= 0 && layout < LAYOUTS.length ? LAYOUTS[layout] : "unknown")
                .append(live ? "" : ", not connected")
                .append(ledIndexMax == 0 ? ", no RGB" : "")
                .append('\n');

        if (!live || rows == 0 || cols == 0 || ledIndexMax == 0) return;
        if (!deviceEnabled.test(cls)) {
            skippedByClass.add(model + " (" + cls.configKey() + ")");
            return;
        }

        Device d = new Device();
        d.index = (byte) index;
        d.id = "wooting:" + index;
        d.model = model;
        d.deviceClass = cls;
        d.rows = rows;
        d.cols = cols;
        devices.add(d);
        devicesById.put(d.id, d);
    }

    /**
     * Letter and digit cells as {row, col}, off Wooting's own matrix diagram
     * for the full-size boards. Only handed out for boards at least
     * {@link #NAMED_KEYS_MIN_COLS} wide, because the diagram is a Wooting Two
     * and nobody here has seen where a 60HE puts its letters.
     */
    private static Map<Character, int[]> namedCells() {
        Map<Character, int[]> m = new HashMap<>();
        row(m, "1234567890", 1, 1);
        row(m, "QWERTYUIOP", 2, 1);
        row(m, "ASDFGHJKL", 3, 1);
        // Column 1 is the ISO key between left shift and Z, so Z starts at 2.
        row(m, "ZXCVBNM", 4, 2);
        return m;
    }

    private static void row(Map<Character, int[]> m, String keys, int row, int firstCol) {
        for (int i = 0; i < keys.length(); i++) {
            m.put(keys.charAt(i), new int[]{row, firstCol + i});
        }
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public KeyGrid keyGrid() {
        return grid;
    }

    @Override
    public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (!connected) return;
        for (Map.Entry<KeyGrid.LedRef, RGBColor> e : frame.entrySet()) {
            Device d = devicesById.get(e.getKey().deviceId());
            if (d == null) continue;
            int luid = e.getKey().luid();
            if (luid < 0 || luid >= WootingRgbSdk.ROWS * WootingRgbSdk.COLS) continue;
            RGBColor c = e.getValue();
            int o = luid * 3;
            byte r = (byte) c.r(), g = (byte) c.g(), b = (byte) c.b();
            if (d.buffer[o] != r || d.buffer[o + 1] != g || d.buffer[o + 2] != b) {
                d.buffer[o] = r;
                d.buffer[o + 1] = g;
                d.buffer[o + 2] = b;
                d.dirty = true;
                pending = true;
            }
        }

        long now = System.nanoTime();
        if (!pending || now - lastPushNanos < MIN_PUSH_NANOS) return;
        lastPushNanos = now;
        try {
            for (Device d : devices) {
                if (!d.dirty) continue;
                if (sdk.wooting_usb_select_device(d.index) == 0
                        || sdk.wooting_rgb_array_set_full(d.buffer) == 0
                        || sdk.wooting_rgb_array_update_keyboard() == 0) {
                    lost(d);
                    return;
                }
                d.dirty = false;
            }
            pending = false;
        } catch (Throwable t) {
            connected = false;
            BackendHealth.recordFailure(id(), displayName(), "while sending a frame", t,
                    "Wooting's SDK stopped answering.", BackendHealth.SEND_REPORT);
        }
    }

    /**
     * One failed write and that is it for the session. No retry count here,
     * unlike the backends talking to a vendor app that might just be busy.
     *
     * <p>When a write fails, the SDK closes every device it had open. And the
     * next call into it would quietly re-enumerate the whole USB bus to find
     * them again, on every frame, possibly handing back a different device
     * order than the grid was built from. Stopping is the only safe move.
     */
    private void lost(Device d) {
        connected = false;
        lostMidGame = true;
        BackendHealth.record(id(), displayName(), BackendHealth.State.LOST,
                d.model + " stopped accepting lighting mid-game, most likely because it was unplugged.",
                "Plug it back in, then restart Minecraft to reconnect.",
                "Write to device " + (d.index & 0xFF) + " failed.\n" + inventory);
    }

    @Override
    public void shutdown() {
        connected = false;
        if (sdk != null) {
            try {
                // After a lost write the SDK has no handles left to reset with,
                // so a board that is in fact still plugged in would sit on our
                // last frame until somebody replugged it. Reconnecting first
                // gives close() something to put back on the board's own
                // profile colours.
                if (lostMidGame) sdk.wooting_rgb_kbd_connected();
                sdk.wooting_rgb_close();
            } catch (Throwable ignored) {
                // Runs from the render loop's finally. Nothing here is
                // actionable, and throwing would bury whatever got us here.
            }
        }
        lostMidGame = false;
        devices.clear();
        devicesById.clear();
        grid = KeyGrid.empty();
    }
}
