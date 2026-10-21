package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.COM.COMException;
import com.sun.jna.platform.win32.COM.util.ObjectFactory;
import com.sun.jna.platform.win32.COM.util.ProxyObject;
import com.sun.jna.platform.win32.Guid.CLSID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinReg;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * ASUS Aura Sync, through the Aura SDK that Armoury Crate installs.
 *
 * <h2>COM, finally</h2>
 * Every other native backend here calls plain exported functions. Aura is a
 * COM object instead: {@code aura.sdk.1}, served by
 * {@code AuraSdk_x64.dll}, which Armoury Crate registers and which loads
 * straight into this process. No separate program, no network, but every
 * call is an {@code IDispatch} round trip through JNA's COM layer, mapped in
 * {@link AuraSdk}.
 *
 * <p>Nothing is bundled. If Armoury Crate is installed, the SDK is too. That
 * includes machines with no ASUS lighting at all: Armoury Crate installs it
 * regardless, which is how this got checked on a machine with no ASUS hardware
 * in it. (You must select a device otherwise the install fails and no SDK is installed.)
 *
 * <h2>COM rules this class lives by</h2>
 * <ul>
 *   <li>COM is initialised on the SDK worker thread, multithreaded, by
 *       {@link #connect}, and uninitialised by {@link #shutdown}. ASUS
 *       register the object as "Both", so it is happy there.</li>
 *   <li>Every proxy JNA hands out holds a COM reference. Collections are
 *       released the moment they have been read. Devices and lights are kept
 *       for the session, and the factory releases the lot at shutdown.</li>
 *   <li>Left to themselves, JNA's proxies release in {@code finalize()}, on
 *       the garbage collector's thread, whenever it gets round to it. Which
 *       is exactly why nothing here is left to itself.</li>
 * </ul>
 *
 * <h2>Geometry</h2>
 * Keyboards are the good news. A device that answers to
 * {@code IAuraSyncKeyboard} lists its keys with real X and Y positions, so an
 * ASUS keyboard gets the full pattern library with no layout file.
 *
 * <p>Everything else gets laid out from its reported width and height,
 * filling rows left to right, top to bottom. That is how the SDK's own grid
 * reads, but no strip, fan or motherboard has ever been checked against it,
 * so it is an assumption, and a {@link KeyLayout} matched on the light names
 * can override it.
 *
 * <h2>What has been checked</h2>
 * The only keyboard this project has been tested on is a Corsair K70 RGB
 * RAPIDFIRE, and no ASUS RGB hardware has been near this code. Checked
 * against a real Armoury Crate install with no ASUS devices: the object is
 * registered, it creates, {@code SwitchMode} and {@code ReleaseControl} are
 * accepted, and {@code Enumerate(0)} returns an empty collection.
 *
 * <p>Everything past that (device types, key positions, which LEDs the
 * colors land on) is the type library read faithfully and never seen
 * working. Experimental, very much so.
 */
public final class AuraBackend implements LightingBackend {

    private static final String PROG_ID = "aura.sdk.1";

    /**
     * Frame-rate cap. Same cautious guess as Mystic Light: every changed LED
     * costs three dispatch calls and every changed device an {@code Apply},
     * and nobody here has timed either against real hardware.
     */
    private static final long MIN_PUSH_NANOS = 1_000_000_000L / 30;

    /** Consecutive failed pushes before the session counts as gone. About two seconds at the cap. */
    private static final int LOST_AFTER_FAILED_PUSHES = 60;

    private static final String NOT_RUNNING_SUMMARY =
            "Armoury Crate's Aura SDK did not answer, so its lighting service is probably not running.";
    private static final String NOT_RUNNING_FIX =
            "Start Armoury Crate, or check the \"ASUS AURA SYNC lighting service\" is running, then restart Minecraft.";

    /** Sets one LED. Lights and keyboard keys are different COM interfaces with the same three setters. */
    private interface Lamp {
        void set(int r, int g, int b);
    }

    private static final class Device {
        String id;
        String name;
        KeyGrid.DeviceClass deviceClass;
        AuraSdk.Device com;
        Lamp[] lamps;
        /** Packed 0xRRGGBB of what we last asked for per LED, -1 before the first frame. */
        int[] colors;
        boolean[] dirty;
        boolean anyDirty;
        /** Per LED: luid, x, y. Only needed until the grid is built. */
        List<double[]> where;
        List<String> lampNames;
    }

    private ObjectFactory factory;
    private AuraSdk.Sdk sdk;
    private boolean comInitialised;
    private boolean inControl;
    private boolean connected;
    private KeyGrid grid = KeyGrid.empty();

    private final List<Device> devices = new ArrayList<>();
    private final Map<String, Device> devicesById = new HashMap<>();
    private final List<String> skippedByClass = new ArrayList<>();
    /** Everything Aura told us, one line per device, for the report. */
    private final StringBuilder inventory = new StringBuilder();

    private boolean pending;
    private long lastPushNanos;
    private int failedPushesInARow;

    @Override
    public String id() {
        return "aura";
    }

    @Override
    public String displayName() {
        return "ASUS Aura Sync";
    }

    @Override
    public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        try {
            // Registry first, no native code: a machine without Armoury Crate
            // gets "not installed" before anything is loaded.
            Path dll = findServer();
            List<Path> libraries = List.of(dll);
            NativeCrashGuard.checkBefore(workDir, id(), displayName(), libraries);
            NativeCrashGuard.enter(workDir, id(), libraries);
            try {
                open(dll, deviceEnabled);
            } finally {
                NativeCrashGuard.exit(workDir, id());
            }
        } catch (Throwable t) {
            BackendHealth.recordFailure(id(), displayName(), "while connecting", t,
                    NOT_RUNNING_SUMMARY, NOT_RUNNING_FIX);
            shutdown();
            return false;
        }

        connected = true;
        StringBuilder summary = new StringBuilder()
                .append(devices.size()).append(" device(s), ").append(grid.allKeys().size()).append(" LEDs");
        if (!skippedByClass.isEmpty()) summary.append("; skipped as turned off in [devices]: ").append(skippedByClass);
        BackendHealth.record(id(), displayName(), BackendHealth.State.CONNECTED, summary.append('.').toString(),
                null, inventory.toString());
        return true;
    }

    /**
     * Where the registered COM server lives, which doubles as the "is it
     * installed" check and as the file the crash guard fingerprints.
     */
    private static Path findServer() throws BackendHealth.Unavailable {
        CLSID.ByReference clsid = new CLSID.ByReference();
        HRESULT hr = Ole32.INSTANCE.CLSIDFromProgID(PROG_ID, clsid);
        if (hr.intValue() != WinError.S_OK.intValue()) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_INSTALLED,
                    "Armoury Crate's Aura SDK is not installed, so ASUS lighting was not tried.",
                    "If you have ASUS lighting, install Armoury Crate. If not, ignore this line.",
                    PROG_ID + " is not registered (HRESULT 0x" + Integer.toHexString(hr.intValue()) + ")");
        }
        String key = "CLSID\\" + clsid.toGuidString() + "\\InprocServer32";
        String path = null;
        try {
            path = Advapi32Util.registryGetStringValue(WinReg.HKEY_CLASSES_ROOT, key, "");
        } catch (RuntimeException ignored) {
            // Falls through to the "registered but missing" diagnosis below.
        }
        if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path.trim()))) {
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "Armoury Crate's Aura SDK is registered, but its file is missing.",
                    "Reinstall Armoury Crate (ASUS's Armoury Crate Uninstall Tool first, if a normal reinstall fails).",
                    "HKCR\\" + key + " = " + path);
        }
        return Path.of(path.trim());
    }

    private void open(Path dll, Predicate<KeyGrid.DeviceClass> deviceEnabled) throws BackendHealth.Unavailable {
        HRESULT init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
        // S_OK and S_FALSE both need a matching CoUninitialize. RPC_E_CHANGED_MODE
        // means this thread is already single-threaded, which the object is
        // also fine with, but then the uninitialise is not ours to do.
        comInitialised = init.intValue() == WinError.S_OK.intValue() || init.intValue() == WinError.S_FALSE.intValue();
        inventory.append("SDK: ").append(dll).append('\n');

        factory = new ObjectFactory();
        long clock = System.nanoTime();
        try {
            sdk = factory.createObject(AuraSdk.Sdk.class);
        } catch (COMException e) {
            throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                    "Armoury Crate's Aura SDK is installed but would not start.",
                    "Try restarting Armoury Crate. If that does not help, " + BackendHealth.SEND_REPORT,
                    inventory + "CoCreateInstance: " + describe(e));
        }
        clock = timed("create", clock);
        try {
            sdk.SwitchMode();
            inControl = true;
        } catch (COMException e) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_RUNNING,
                    "Armoury Crate's Aura SDK would not hand over lighting control.",
                    NOT_RUNNING_FIX, inventory + "SwitchMode: " + describe(e));
        }

        clock = timed("SwitchMode", clock);
        // The slow one. About 3.6 seconds on a machine with no ASUS devices,
        // presumably Aura scanning for hardware, every launch. It is on the
        // worker thread, so the game never notices, but in auto mode the
        // backends after this one wait for it.
        AuraSdk.DeviceCollection all = sdk.Enumerate(0);
        timed("Enumerate", clock);
        int count;
        try {
            count = all.getCount();
            inventory.append(count).append(" device(s)\n");
            for (int i = 0; i < count; i++) {
                try {
                    readDevice(i, all.Item(i), deviceEnabled);
                } catch (Throwable t) {
                    inventory.append("  [").append(i).append("] could not be read: ").append(t).append('\n');
                    BackendHealth.problem(displayName(), "Device " + i + " of " + count
                            + " could not be read and was skipped.", t);
                }
            }
        } finally {
            release(all);
        }

        if (devices.isEmpty()) {
            if (count == 0) {
                // The common case, and not a problem: Armoury Crate installs
                // the SDK on plenty of machines with no ASUS lighting attached.
                throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                        "Armoury Crate is installed, but Aura reports no lighting devices.",
                        "If you have ASUS lighting, check it shows up in Armoury Crate's Aura Sync page. "
                                + "If not, ignore this line.",
                        inventory.toString());
            }
            if (!skippedByClass.isEmpty()) {
                throw new BackendHealth.Unavailable(BackendHealth.State.DISABLED,
                        "Aura's devices are all types turned off in the config: " + skippedByClass + ".",
                        "Turn on the matching setting in the [devices] section of "
                                + "everythingrgbprofiles-common.toml, for example keyboardEnabled.",
                        inventory.toString());
            }
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "Aura listed " + count + " device(s), but this mod could not use any of them.",
                    BackendHealth.SEND_REPORT + " Mention which version of Armoury Crate you have.",
                    inventory.toString());
        }

        KeyGrid.Builder builder = new KeyGrid.Builder();
        for (Device d : devices) {
            List<double[]> laidOut = KeyLayout.positionsFor(d.name, d.lampNames, d.where);
            builder.beginDevice(d.id, d.name, d.deviceClass);
            for (double[] p : laidOut) builder.addLed((int) p[0], p[1], p[2]);
            builder.endDevice();
            d.where = null;
            d.lampNames = null;
        }
        grid = builder.build();
    }

    // ---------------------------------------------------------------
    // Enumeration
    // ---------------------------------------------------------------

    private void readDevice(int index, AuraSdk.Device com, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        boolean kept = false;
        try {
            String name = com.getName();
            if (name == null || name.isBlank()) name = "Aura device " + index;
            int type = com.getType();
            int width = com.getWidth();
            int height = com.getHeight();

            AuraSdk.Keyboard keyboard = asKeyboard(com);
            KeyGrid.DeviceClass cls = keyboard != null ? KeyGrid.DeviceClass.KEYBOARD : deviceClassOf(name);

            List<Lamp> lamps = new ArrayList<>();
            List<String> lampNames = new ArrayList<>();
            List<double[]> where = new ArrayList<>();
            String source;
            if (keyboard != null && readKeys(keyboard, lamps, lampNames, where)) {
                source = "keys with positions";
            } else {
                readLights(com, width, lamps, lampNames, where);
                source = width > 0 ? "lights, laid out " + width + " wide" : "lights, in one row";
            }
            if (keyboard != null) release(keyboard);

            inventory.append("  [").append(index).append("] \"").append(name).append("\" type 0x")
                    .append(Integer.toHexString(type)).append(" (").append(cls).append("), ")
                    .append(width).append('x').append(height).append(", ")
                    .append(lamps.size()).append(' ').append(source)
                    .append(lampNames.isEmpty() ? "" : ", first: " + lampNames.subList(0, Math.min(8, lampNames.size())))
                    .append('\n');
            if (debugEnabled()) {
                RGBProfileMod.LOGGER.info("RGB Profile: Aura [{}] '{}' type 0x{} ({}), {}x{}, {} LEDs:",
                        index, name, Integer.toHexString(type), cls, width, height, lamps.size());
                for (int i = 0; i < lampNames.size(); i++) {
                    double[] p = where.get(i);
                    RGBProfileMod.LOGGER.info("    {} = \"{}\" at ({}, {})", i, lampNames.get(i), p[1], p[2]);
                }
            }

            if (!deviceEnabled.test(cls)) {
                skippedByClass.add(name + " (" + cls.configKey() + ")");
                return;
            }
            if (lamps.isEmpty()) return;

            Device d = new Device();
            d.id = "aura:" + index;
            d.name = name;
            d.deviceClass = cls;
            d.com = com;
            d.lamps = lamps.toArray(new Lamp[0]);
            d.colors = new int[d.lamps.length];
            Arrays.fill(d.colors, -1);
            d.dirty = new boolean[d.lamps.length];
            d.where = where;
            d.lampNames = lampNames;
            devices.add(d);
            devicesById.put(d.id, d);
            kept = true;
        } finally {
            // A device we are not keeping gives its COM reference back now
            // instead of whenever the garbage collector feels like it.
            if (!kept) release(com);
        }
    }

    /** The keyboard view of a device, or null if it is not one. QueryInterface failing is the answer, not an error. */
    private static AuraSdk.Keyboard asKeyboard(AuraSdk.Device com) {
        try {
            return com.queryInterface(AuraSdk.Keyboard.class);
        } catch (COMException notAKeyboard) {
            return null;
        }
    }

    /** Reads keys and their positions. False if the keyboard listed none, so the caller falls back to lights. */
    private boolean readKeys(AuraSdk.Keyboard keyboard, List<Lamp> lamps, List<String> lampNames,
                             List<double[]> where) {
        AuraSdk.KeyCollection keys = keyboard.getKeys();
        try {
            int n = keys.getCount();
            for (int i = 0; i < n; i++) {
                AuraSdk.Key key = keys.Item(i);
                String keyName = key.getName();
                lampNames.add(keyName == null || keyName.isBlank() ? "Key 0x" + Integer.toHexString(key.getCode()) : keyName);
                // Y grows downward here like everywhere else in this mod. Which
                // way ASUS's Y runs has never been seen on real hardware; if an
                // ASUS board ever renders upside down, this is the line.
                where.add(new double[]{i, key.getX(), key.getY()});
                lamps.add((r, g, b) -> {
                    key.setRed(r);
                    key.setGreen(g);
                    key.setBlue(b);
                });
            }
            return n > 0;
        } finally {
            release(keys);
        }
    }

    private void readLights(AuraSdk.Device com, int width, List<Lamp> lamps, List<String> lampNames,
                            List<double[]> where) {
        AuraSdk.LightCollection lights = com.getLights();
        try {
            int n = lights.getCount();
            for (int i = 0; i < n; i++) {
                AuraSdk.Light light = lights.Item(i);
                String lightName = light.getName();
                lampNames.add(lightName == null || lightName.isBlank() ? "Light " + i : lightName);
                // Row-major across the reported width. An assumption, see the class doc.
                where.add(width > 0 ? new double[]{i, i % width, i / width} : new double[]{i, i, 0});
                lamps.add((r, g, b) -> {
                    light.setRed(r);
                    light.setGreen(g);
                    light.setBlue(b);
                });
            }
        } finally {
            release(lights);
        }
    }

    // ---------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------

    @Override
    public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (!connected) return;
        for (Map.Entry<KeyGrid.LedRef, RGBColor> e : frame.entrySet()) {
            Device d = devicesById.get(e.getKey().deviceId());
            if (d == null) continue;
            int i = e.getKey().luid();
            if (i < 0 || i >= d.lamps.length) continue;
            RGBColor c = e.getValue();
            int packed = (c.r() & 0xFF) << 16 | (c.g() & 0xFF) << 8 | (c.b() & 0xFF);
            if (d.colors[i] != packed) {
                d.colors[i] = packed;
                d.dirty[i] = true;
                d.anyDirty = true;
                pending = true;
            }
        }

        long now = System.nanoTime();
        if (!pending || now - lastPushNanos < MIN_PUSH_NANOS) return;
        lastPushNanos = now;
        try {
            for (Device d : devices) {
                if (!d.anyDirty) continue;
                for (int i = 0; i < d.lamps.length; i++) {
                    if (!d.dirty[i]) continue;
                    int c = d.colors[i];
                    d.lamps[i].set((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                    d.dirty[i] = false;
                }
                d.com.Apply();
                d.anyDirty = false;
            }
            pending = false;
            failedPushesInARow = 0;
        } catch (COMException e) {
            // Whatever did not go out is still marked dirty and goes next push.
            if (++failedPushesInARow >= LOST_AFTER_FAILED_PUSHES) {
                connected = false;
                BackendHealth.record(id(), displayName(), BackendHealth.State.LOST,
                        "Armoury Crate stopped accepting lighting mid-game: closed, restarting or updating.",
                        "Once it is running again, restart Minecraft to reconnect.",
                        "Refused " + failedPushesInARow + " frames in a row. Last: " + describe(e));
            }
        } catch (Throwable t) {
            connected = false;
            BackendHealth.recordFailure(id(), displayName(), "while sending a frame", t,
                    NOT_RUNNING_SUMMARY, NOT_RUNNING_FIX);
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

    /**
     * Hands control back, which is also how the player's own lighting comes
     * back: Armoury Crate re-applies its profile when released. Then every
     * COM reference goes, then COM itself, in that order. Uninitialising
     * with references still out is how you get a crash at exit that nobody
     * can reproduce.
     */
    @Override
    public void shutdown() {
        connected = false;
        if (sdk != null && inControl) {
            try {
                sdk.ReleaseControl(0);
            } catch (Throwable ignored) {
            }
        }
        inControl = false;
        devices.clear();
        devicesById.clear();
        sdk = null;
        if (factory != null) {
            try {
                factory.disposeAll();
            } catch (Throwable ignored) {
            }
            factory = null;
        }
        if (comInitialised) {
            try {
                Ole32.INSTANCE.CoUninitialize();
            } catch (Throwable ignored) {
            }
            comInitialised = false;
        }
    }

    // ---------------------------------------------------------------
    // Small things
    // ---------------------------------------------------------------

    /** Notes how long a step took, for the report, and restarts the clock. */
    private long timed(String step, long since) {
        long now = System.nanoTime();
        inventory.append(step).append(": ").append((now - since) / 1_000_000).append(" ms\n");
        return now;
    }

    /** Drops one proxy's COM reference now. Safe on anything, including null. */
    private static void release(Object proxy) {
        if (proxy != null && Proxy.isProxyClass(proxy.getClass())
                && Proxy.getInvocationHandler(proxy) instanceof ProxyObject po) {
            po.dispose();
        }
    }

    private static String describe(COMException e) {
        HRESULT hr = e.getHresult();
        return (hr == null ? "" : "HRESULT 0x" + Integer.toHexString(hr.intValue()) + " ") + e.getMessage();
    }

    /**
     * Name -> class, for everything that is not a keyboard.
     *
     * <p>Aura does report a numeric type per device, but the type library
     * does not say what the numbers mean, and a table typed in from memory is
     * not a thing worth shipping. So the device's own name decides, the raw
     * type goes into the report, and an honest table can replace this once
     * reports show what real devices send.
     */
    static KeyGrid.DeviceClass deviceClassOf(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        if (n.contains("KEYBOARD")) return KeyGrid.DeviceClass.KEYBOARD;
        if (n.contains("MOUSEPAD") || n.contains("MOUSE PAD") || n.contains("MOUSEMAT")) {
            return KeyGrid.DeviceClass.MOUSEMAT;
        }
        if (n.contains("MOUSE")) return KeyGrid.DeviceClass.MOUSE;
        if (n.contains("HEADSET") || n.contains("HEADPHONE")) return KeyGrid.DeviceClass.HEADSET;
        if (n.contains("DRAM") || n.contains("MEMORY") || n.contains("DIMM")) return KeyGrid.DeviceClass.MEMORY;
        if (n.contains("FAN") || n.contains("COOLER") || n.contains("AIO")) return KeyGrid.DeviceClass.COOLING;
        if (n.contains("MAINBOARD") || n.contains("MOTHERBOARD") || n.matches(".*\\bMB\\b.*")) {
            return KeyGrid.DeviceClass.MOTHERBOARD;
        }
        return KeyGrid.DeviceClass.OTHER;
    }

    /** Wrapped because {@code tuning/AuraProbe} runs this class outside a loaded game. */
    private static boolean debugEnabled() {
        try {
            return RGBProfileConfig.DEBUG_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }
}
