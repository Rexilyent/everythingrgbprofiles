package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The part that actually talks to a Corsair keyboard. Everything else in this
 * mod is arithmetic on colours; this is the one file that can hard-crash the
 * JVM, which is why it is written as defensively as it is.
 *
 * <p>Owns the iCUE SDK v4 connection. Rewritten against the real headers,
 * because an earlier version targeted the <b>removed</b> CUE SDK v2 surface
 * — meaning it could not have linked against any shipping DLL, ever, on any
 * machine. It was code that looked correct and was structurally incapable of
 * working.
 *
 * <h2>Five things that changed, and why each one matters</h2>
 *
 * <p><b>1. Connection is asynchronous.</b> {@code CorsairConnect} returns
 * immediately; the session is only usable once the state callback reports
 * {@code CSS_Connected}. Treating the return code as a handshake result — the
 * obvious reading — gives you a "successful" connection you then can't use. We
 * wait on a latch with a bounded timeout instead.
 *
 * <p><b>2. No exclusive control.</b> We call {@code CorsairSetLayerPriority}
 * rather than {@code CorsairRequestControl}. iCUE draws at 127 and SDK clients
 * default to 128, so sitting at 130 puts us on top without seizing the device.
 *
 * <p>This one is genuinely important for users, not just tidiness: with
 * exclusive control, an unclean Minecraft exit — a crash, a task-kill, a power
 * cut — leaves the keyboard stuck displaying our lighting with the user's own
 * iCUE profile locked out. Shutdown hooks do not run when the process is
 * killed. Layer priority has no such failure mode; iCUE simply resumes drawing
 * when we stop.
 *
 * <p><b>3. Alpha is used.</b> {@code CorsairLedColor} carries an alpha channel
 * that iCUE composites in its own layer stack, so Tier 2 overlays can be
 * genuinely translucent rather than the opaque per-key overwrite an earlier
 * Compositor did.
 *
 * <p><b>4. Only changed LEDs are pushed.</b> An earlier loop wrote a full board
 * every frame at 30Hz whether or not anything had moved — and
 * {@code debounceMillis} was defined in config and read by absolutely nothing.
 *
 * <p><b>5. Struct arrays are contiguous.</b> Everything goes through
 * {@code Structure.toArray}. An earlier version built Java arrays of individually
 * allocated Structures, which are scattered anywhere in memory — and then
 * handed the first one's pointer to native code that would walk forward
 * expecting the rest to follow. It reads whatever happened to be next in the
 * heap. That's not a bug you debug, it's a bug you survive.
 */
public final class CueSdkBridge {

    /** CONNECTED = talking to hardware. NO_OP = everything still runs, nothing is sent. */
    public enum Mode { CONNECTED, NO_OP }

    private static final String DLL_RESOURCE = "/everythingrgbprofiles/native/iCUESDK.x64_2019.dll";
    private static final String DLL_FILE_NAME = "iCUESDK.x64_2019.dll";

    /** Config key labels we ask the SDK to resolve to real luids at startup. */
    private static final char[] NAMED_KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static final String ID = "corsair";
    private static final String NAME = "Corsair iCUE";

    /**
     * Where iCUE installs its own copy of the SDK. Hardcoded paths, which is
     * inelegant and also correct: these are Corsair's fixed install locations
     * and there is no registry key or env var that reliably beats just
     * looking. iCUE5 first (newer), then iCUE4, then the x86 program-files
     * variant.
     */
    private static final String[] ICUE_INSTALL_DLLS = {
            "C:\\Program Files\\Corsair\\CORSAIR iCUE5 Software\\iCUESDK.x64_2019.dll",
            "C:\\Program Files\\Corsair\\CORSAIR iCUE4 Software\\iCUESDK.x64_2019.dll",
            "C:\\Program Files (x86)\\Corsair\\CORSAIR iCUE5 Software\\iCUESDK.x64_2019.dll",
    };

    private volatile Mode mode = Mode.NO_OP; // guilty until proven connected
    /** Devices left dark because their type is turned off in [devices], for the diagnosis. */
    private final List<String> skippedByClass = new ArrayList<>();
    private ICueSdk sdk;
    private KeyGrid keyGrid = KeyGrid.empty();
    private boolean loggedOnce = false;

    /**
     * Strong reference to the session callback. <b>Load-bearing field. Do not
     * "clean up".</b>
     *
     * <p>If this were a local or an inline lambda, the JVM would be free to
     * collect it the moment {@code connect} returns — while the native SDK is
     * still holding its function pointer. The next session state change then
     * calls into freed memory. That's a JVM crash, at an arbitrary later time,
     * with a stack trace pointing at nothing useful.
     *
     * <p>{@code @SuppressWarnings("unused")} because it genuinely is never
     * read from Java. Being unread is the entire job.
     */
    @SuppressWarnings("unused")
    private ICueSdk.SessionStateChangedHandler sessionHandler;

    /** Per-device native write buffers, allocated once and reused every frame. */
    private final Map<String, DeviceBuffer> deviceBuffers = new LinkedHashMap<>();

    /** Last frame actually sent, so unchanged LEDs can be skipped. */
    private final Map<KeyGrid.LedRef, Integer> lastSent = new HashMap<>();

    /**
     * One device's reusable native colour array plus the ref→slot index.
     *
     * <p>Allocated once at connect and mutated in place forever after.
     * Allocating native memory every frame at 30Hz would be an unusually
     * effective way to generate garbage.
     */
    private static final class DeviceBuffer {
        final String deviceId;
        final ICueSdk.CorsairLedColor[] colors;
        final Map<KeyGrid.LedRef, Integer> slotByRef = new HashMap<>();
        int dirtyCount;

        DeviceBuffer(String deviceId, int ledCount) {
            this.deviceId = deviceId;
            // ICueSdk.array() → Structure.toArray: CONTIGUOUS native memory.
            // See point 5 in the class doc for what happens otherwise.
            this.colors = ICueSdk.array(new ICueSdk.CorsairLedColor(), Math.max(1, ledCount));
        }
    }

    public Mode mode() {
        return mode;
    }

    public KeyGrid keyGrid() {
        return keyGrid;
    }

    // ---------------------------------------------------------------------
    // Connect
    // ---------------------------------------------------------------------

    /**
     * Attempts the whole connection sequence. Every failure path ends in
     * no-op mode with one log line — never an exception out of this method,
     * because a keyboard-lighting mod has no business crashing anyone's game.
     */
    public void connect(Path dllExtractDir, java.util.function.Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        try {
            // jna.nosys=true — small line, real war story.
            //
            // Another mod in a 600-mod pack may ship its own JNA, and the
            // machine may ALSO have a system-wide jnidispatch (a 7.0.0 has
            // been seen in the wild). Left to its own devices JNA will happily
            // discover the wrong one and the version clash takes the whole
            // lighting layer down. This forces it to use the copy we embed.
            System.setProperty("jna.nosys", "true");

            Path dllPath = resolveDll(dllExtractDir);
            if (dllPath == null) {
                logOnce("No iCUE SDK DLL available (neither bundled nor found in an iCUE install) — no-op mode.");
                report(BackendHealth.State.NOT_INSTALLED,
                        "Corsair iCUE does not look installed: its SDK was not found, and this build does not include one.",
                        "If you have a Corsair keyboard, install Corsair iCUE. If not, ignore this line.",
                        "Looked in: " + String.join(", ", ICUE_INSTALL_DLLS));
                return;
            }
            sdk = ICueSdk.load(dllPath.toAbsolutePath().toString());

            // The async handshake (class doc point 1). CorsairConnect returns
            // instantly; the latch is what actually tells us we're connected.
            CountDownLatch settled = new CountDownLatch(1);
            AtomicInteger state = new AtomicInteger(ICueSdk.CSS_Invalid);
            sessionHandler = (ctx, data) -> {
                // Explicit read(): this struct was filled in by native code,
                // and JNA does not know its fields changed until told.
                // Forgetting this gives you stale zeroes and a mystery.
                data.read();
                state.set(data.state);
                // Count down on any TERMINAL state, not just success —
                // otherwise a refused or timed-out connection sits here
                // burning the full 5-second timeout for no reason.
                if (data.state == ICueSdk.CSS_Connected
                        || data.state == ICueSdk.CSS_ConnectionRefused
                        || data.state == ICueSdk.CSS_Timeout
                        || data.state == ICueSdk.CSS_Closed) {
                    settled.countDown();
                }
            };

            int rc = sdk.CorsairConnect(sessionHandler, Pointer.NULL);
            if (rc != ICueSdk.CE_Success) {
                logOnce("CorsairConnect failed: " + ICueSdk.errorName(rc) + " — no-op mode.");
                if (rc == ICueSdk.CE_IncompatibleProtocol) {
                    report(BackendHealth.State.REFUSED, "Your version of iCUE is too old for this mod.",
                            "Update Corsair iCUE, then restart Minecraft.", "CorsairConnect: " + ICueSdk.errorName(rc));
                } else {
                    report(BackendHealth.State.REFUSED, "iCUE's SDK would not start a session.",
                            "Restart iCUE, then restart Minecraft.", "CorsairConnect: " + ICueSdk.errorName(rc));
                }
                return;
            }

            if (!settled.await(5, TimeUnit.SECONDS) || state.get() != ICueSdk.CSS_Connected) {
                // The message names the actual state AND what to do about it.
                // "third-party control is disabled in iCUE settings" is by far
                // the most common cause and is not remotely discoverable, so
                // the log line says it outright.
                logOnce("iCUE session did not reach Connected (last state: "
                        + ICueSdk.sessionStateName(state.get())
                        + "). Check iCUE is running and third-party/SDK control is enabled in its settings. No-op mode.");
                safeDisconnect();
                String detail = "Last session state: " + ICueSdk.sessionStateName(state.get()) + ", SDK: " + dllPath;
                if (state.get() == ICueSdk.CSS_ConnectionRefused) {
                    report(BackendHealth.State.REFUSED, "iCUE is running but refused the connection.",
                            "In iCUE, open Settings and turn on SDK control (\"Enable SDK\"), then restart Minecraft.",
                            detail);
                } else if (!iCueInstalled()) {
                    report(BackendHealth.State.NOT_INSTALLED, "Corsair iCUE does not look installed.",
                            "If you have a Corsair keyboard, install Corsair iCUE. If not, ignore this line.", detail);
                } else {
                    report(BackendHealth.State.NOT_RUNNING,
                            "iCUE is installed but did not answer within 5 seconds, so it is probably not running.",
                            "Start iCUE, check SDK control (\"Enable SDK\") is on in its settings, then restart Minecraft.",
                            detail);
                }
                return;
            }

            // Layer priority, not exclusive control — see class doc point 2.
            sdk.CorsairSetLayerPriority(ICueSdk.DEFAULT_LAYER_PRIORITY);

            List<ICueSdk.CorsairDeviceInfo> devices = enumerateDevices();
            if (devices.isEmpty()) {
                logOnce("Connected to iCUE but no lighting-capable Corsair devices were reported — no-op mode.");
                safeDisconnect();
                report(BackendHealth.State.NO_DEVICES, "iCUE is running but reports no Corsair lighting devices.",
                        "Check your Corsair devices show up in iCUE. If iCUE lights them but this mod cannot, "
                                + BackendHealth.SEND_REPORT, null);
                return;
            }

            this.keyGrid = buildGrid(devices, deviceEnabled);
            if (keyGrid.isEmpty()) {
                logOnce("Devices present but no LED positions could be read — no-op mode.");
                safeDisconnect();
                if (!skippedByClass.isEmpty()) {
                    report(BackendHealth.State.DISABLED,
                            "Every Corsair device found is a type turned off in the config: " + skippedByClass + ".",
                            "Turn on the matching setting in the [devices] section of everythingrgbprofiles-common.toml, "
                                    + "for example keyboardEnabled.", null);
                } else {
                    report(BackendHealth.State.BROKEN,
                            "iCUE listed " + devices.size() + " device(s), but none of their LED positions could be read.",
                            BackendHealth.SEND_REPORT, null);
                }
                return;
            }

            this.mode = Mode.CONNECTED;
            RGBProfileMod.LOGGER.info("RGB Profile: iCUE connected — {} device(s), {} LEDs mapped.",
                    devices.size(), keyGrid.allKeys().size());
            report(BackendHealth.State.CONNECTED,
                    keyGrid.surfaces().size() + " device(s), " + keyGrid.allKeys().size() + " LEDs"
                            + (skippedByClass.isEmpty() ? "" : "; skipped as turned off in [devices]: " + skippedByClass)
                            + ".",
                    null, "SDK: " + dllPath);
        } catch (Throwable t) {
            // Throwable, and it is not negotiable. Native library loading
            // throws ERRORS, not exceptions: UnsatisfiedLinkError,
            // NoClassDefFoundError, ExceptionInInitializerError. Catching only
            // Exception here means a missing symbol propagates up through
            // client setup and takes Minecraft's startup with it.
            //
            // The absolute worst acceptable outcome of this class is "no
            // lighting today, here's a log line".
            logOnce("iCUE SDK initialisation failed (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + ") — no-op mode.");
            mode = Mode.NO_OP;
            BackendHealth.recordFailure(ID, NAME, "while connecting", t,
                    "iCUE did not answer, so it is probably not running.",
                    "Start iCUE, check SDK control (\"Enable SDK\") is on in its settings, then restart Minecraft.");
        }
    }

    /** Asks the SDK what's plugged in, keeping only devices that have LEDs. */
    private List<ICueSdk.CorsairDeviceInfo> enumerateDevices() {
        ICueSdk.CorsairDeviceFilter.ByReference filter =
                new ICueSdk.CorsairDeviceFilter.ByReference(ICueSdk.CDT_All);
        ICueSdk.CorsairDeviceInfo[] buf =
                ICueSdk.array(new ICueSdk.CorsairDeviceInfo(), ICueSdk.CORSAIR_DEVICE_COUNT_MAX);
        IntByReference count = new IntByReference();

        // buf[0] passed, not buf: JNA sends the address of the first element,
        // and the contiguous allocation means native code can walk the rest.
        int rc = sdk.CorsairGetDevices(filter, ICueSdk.CORSAIR_DEVICE_COUNT_MAX, buf[0], count);
        if (rc != ICueSdk.CE_Success) {
            BackendHealth.problem(NAME, "Listing devices failed: " + ICueSdk.errorName(rc), null);
            return List.of();
        }
        List<ICueSdk.CorsairDeviceInfo> out = new ArrayList<>();
        for (int i = 0; i < count.getValue(); i++) {
            buf[i].read(); // again: native wrote it, JNA needs telling
            if (buf[i].ledCount > 0) { // skip anything with nothing to light
                out.add(buf[i]);
                RGBProfileMod.LOGGER.debug("RGB Profile: device {} ({} LEDs, type 0x{})",
                        buf[i].model(), buf[i].ledCount, Integer.toHexString(buf[i].type));
            }
        }
        return out;
    }

    /**
     * Builds the {@link KeyGrid} — the physical layout everything else in the
     * mod reasons about — and allocates each device's write buffer.
     */
    private KeyGrid buildGrid(List<ICueSdk.CorsairDeviceInfo> devices,
                              java.util.function.Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        KeyGrid.Builder builder = new KeyGrid.Builder();

        for (ICueSdk.CorsairDeviceInfo device : devices) {
            KeyGrid.DeviceClass deviceClass = KeyGrid.DeviceClass.fromCorsairType(device.type);
            if (!deviceEnabled.test(deviceClass)) {
                // INFO with the config key that controls it, so a user who
                // wonders why their mousepad is dark gets the answer and the
                // fix in the same line.
                RGBProfileMod.LOGGER.info("RGB Profile: skipping {} ({}) — disabled via [devices] {}",
                        device.model(), deviceClass, deviceClass.configKey());
                skippedByClass.add(device.model() + " (" + deviceClass.configKey() + ")");
                continue;
            }

            String deviceId = device.deviceId();
            // Clamped both ways: at least 1 (a zero-length native array is a
            // bad time), at most the SDK's stated maximum (a device reporting
            // a nonsense count shouldn't get us to allocate accordingly).
            int cap = Math.min(Math.max(device.ledCount, 1), ICueSdk.CORSAIR_DEVICE_LEDCOUNT_MAX);
            ICueSdk.CorsairLedPosition[] positions =
                    ICueSdk.array(new ICueSdk.CorsairLedPosition(), cap);
            IntByReference count = new IntByReference();

            int rc = sdk.CorsairGetLedPositions(deviceId, cap, positions[0], count);
            if (rc != ICueSdk.CE_Success) {
                // continue, not return: one uncooperative device shouldn't
                // cost the user the rest of their hardware.
                BackendHealth.problem(NAME, "Could not read the LED positions of " + device.model()
                        + ", so it stays dark: " + ICueSdk.errorName(rc), null);
                continue;
            }

            int leds = count.getValue();
            if (leds <= 0) continue;

            builder.beginDevice(deviceId, device.model(), deviceClass);
            DeviceBuffer buffer = new DeviceBuffer(deviceId, leds);
            for (int i = 0; i < leds; i++) {
                positions[i].read();
                builder.addLed(positions[i].id, positions[i].cx, positions[i].cy);
                buffer.slotByRef.put(new KeyGrid.LedRef(deviceId, positions[i].id), i);
                // Pre-stamp the LED id and full alpha once, here. Every frame
                // afterwards only has to write r/g/b — three byte stores
                // instead of five, times a hundred LEDs, times 30Hz.
                buffer.colors[i].id = positions[i].id;
                buffer.colors[i].a = (byte) 255;
            }
            builder.endDevice();
            deviceBuffers.put(deviceId, buffer);

            // Named keys: ask the SDK to resolve "H", "F", "T"... to real
            // luids, per device, because it respects the user's LOGICAL
            // LAYOUT. An AZERTY keyboard's "A" is not a QWERTY "A", and a
            // hardcoded table gets that wrong for everyone outside the US.
            //
            // An earlier version resolved key names through a hand-transcribed
            // table of Corsair LED ids, which could only ever be right for a US
            // layout. Asking the SDK is right everywhere. Checked on the K70 RGB
            // RAPIDFIRE this mod was developed against: H->47, F->45, T->33,
            // K->49, matching CLK_H/CLK_F/CLK_T/CLK_K.
            //
            // Keyboards only — asking a mousepad for its "H" key is not a
            // productive conversation.
            if (deviceClass == KeyGrid.DeviceClass.KEYBOARD) {
                for (char c : NAMED_KEYS) {
                    IntByReference luid = new IntByReference();
                    // Both checks needed: some paths return success with a
                    // zero luid, which means "no such key on this layout".
                    if (sdk.CorsairGetLedLuidForKeyName(deviceId, (byte) c, luid) == ICueSdk.CE_Success
                            && luid.getValue() != 0) {
                        builder.addNamedKey(c, new KeyGrid.LedRef(deviceId, luid.getValue()));
                    }
                }
            }
        }
        return builder.build();
    }

    // ---------------------------------------------------------------------
    // Frame application
    // ---------------------------------------------------------------------

    /**
     * Pushes one composited frame to the hardware.
     *
     * <p>Only LEDs whose packed colour actually changed get written, and a
     * device with nothing dirty is skipped entirely. So a resting biome
     * shimmer costs close to nothing and an idle menu costs literally nothing
     * — no native calls at all on a frame where the board didn't move.
     */
    public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (mode != Mode.CONNECTED || sdk == null) return;
        try {
            for (DeviceBuffer buffer : deviceBuffers.values()) {
                buffer.dirtyCount = 0;
            }

            for (Map.Entry<KeyGrid.LedRef, RGBColor> entry : frame.entrySet()) {
                KeyGrid.LedRef ref = entry.getKey();
                RGBColor c = entry.getValue();
                // Pack to a single int for comparison. One int compare per LED
                // instead of three, and one boxed Integer in the map instead
                // of an RGBColor object.
                int packed = (c.r() << 16) | (c.g() << 8) | c.b();

                Integer previous = lastSent.get(ref);
                // Unboxing compare is safe: previous is null-checked, packed
                // is a primitive, so this is int==int.
                if (previous != null && previous == packed) continue;

                DeviceBuffer buffer = deviceBuffers.get(ref.deviceId());
                if (buffer == null) continue; // effect targeting a device we skipped
                Integer slot = buffer.slotByRef.get(ref);
                if (slot == null) continue;

                ICueSdk.CorsairLedColor target = buffer.colors[slot];
                target.r = (byte) c.r();
                target.g = (byte) c.g();
                target.b = (byte) c.b();
                target.a = (byte) 255;
                // write(): the mirror of read(). We changed Java fields and
                // native memory doesn't know yet. Skip this and the SDK
                // faithfully displays whatever was in that buffer last frame.
                target.write();
                buffer.dirtyCount++;
                lastSent.put(ref, packed);
            }

            for (DeviceBuffer buffer : deviceBuffers.values()) {
                if (buffer.dirtyCount == 0) continue;
                // Writes the FULL device span even though only some LEDs
                // changed — and that's the cheaper option. The buffer is
                // already one contiguous native array, so this is a single
                // call; compacting the dirty LEDs into a sub-array would mean
                // allocating and copying every frame to save the native side
                // some memcpy it's going to do anyway.
                int rc = sdk.CorsairSetLedColors(buffer.deviceId, buffer.colors.length, buffer.colors[0]);
                if (rc == ICueSdk.CE_NotConnected || rc == ICueSdk.CE_NoControl) {
                    // iCUE closed, restarted, or another client grabbed
                    // control. Drop to no-op rather than spamming failed calls
                    // 30 times a second forever.
                    logOnce("Lost the iCUE session (" + ICueSdk.errorName(rc) + ") — dropping to no-op mode.");
                    mode = Mode.NO_OP;
                    if (rc == ICueSdk.CE_NoControl) {
                        report(BackendHealth.State.LOST, "Another program took exclusive control of the Corsair lighting.",
                                "Close the other RGB program, or turn off its exclusive control, then restart Minecraft.",
                                "CorsairSetLedColors: " + ICueSdk.errorName(rc));
                    } else {
                        report(BackendHealth.State.LOST,
                                "iCUE stopped answering mid-game: closed, restarting or updating.",
                                "Once iCUE is running again, restart Minecraft to reconnect.",
                                "CorsairSetLedColors: " + ICueSdk.errorName(rc));
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            mode = Mode.NO_OP;
            BackendHealth.recordFailure(ID, NAME, "while sending a frame", t,
                    "iCUE stopped answering mid-game.", "Once iCUE is running again, restart Minecraft to reconnect.");
        }
    }

    // ---------------------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------------------

    public void shutdown() {
        safeDisconnect();
        mode = Mode.NO_OP;
        deviceBuffers.clear();
        lastSent.clear();
    }

    /**
     * Disconnect that cannot throw. Called from a finally block and from a
     * shutdown hook — two contexts where an exception is either swallowed
     * confusingly or prints alarming noise during exit. DEBUG level because
     * "the SDK complained while we were leaving anyway" is not news.
     */
    private void safeDisconnect() {
        if (sdk == null) return;
        try {
            sdk.CorsairDisconnect();
        } catch (Throwable t) {
            RGBProfileMod.LOGGER.debug("RGB Profile: CorsairDisconnect threw on shutdown (harmless).", t);
        }
    }

    // ---------------------------------------------------------------------
    // DLL resolution
    // ---------------------------------------------------------------------

    /**
     * Finds a DLL: bundled copy first, then whatever iCUE installed.
     *
     * <p>The fallback settles the redistribution question rather neatly — it
     * sidesteps shipping Corsair's DLL entirely if you'd rather not, <i>and</i>
     * it guarantees the SDK build matches the user's actual installed iCUE
     * instead of whatever version happened to be current when the mod was
     * released. Two problems, one fallback.
     */
    private Path resolveDll(Path extractDir) throws IOException {
        Path bundled = extractBundledDll(extractDir);
        if (bundled != null) return bundled;

        for (String candidate : ICUE_INSTALL_DLLS) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                RGBProfileMod.LOGGER.info("RGB Profile: using iCUE's installed SDK at {}", p);
                return p;
            }
        }
        return null;
    }

    /**
     * Unpacks the bundled DLL to disk, once. Returns the existing file if it's
     * already there — no re-extraction, no version check.
     *
     * <p>(Worth knowing: that means updating the mod with a new DLL won't
     * replace an already-extracted one. Deleting {@code config/<modid>/native/}
     * forces a fresh extract.)
     */
    private Path extractBundledDll(Path dir) throws IOException {
        Path target = dir.resolve(DLL_FILE_NAME);
        if (Files.isRegularFile(target)) return target;
        try (InputStream in = CueSdkBridge.class.getResourceAsStream(DLL_RESOURCE)) {
            if (in == null) return null; // not bundled in this build; caller falls back
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            return target;
        }
    }

    /** iCUE's installed copies of the SDK, for {@link NativeCrashGuard} to notice an iCUE update by. */
    static List<Path> installedLibraries() {
        List<Path> out = new ArrayList<>();
        for (String dll : ICUE_INSTALL_DLLS) out.add(Path.of(dll));
        return out;
    }

    private static void report(BackendHealth.State state, String summary, String fix, String detail) {
        BackendHealth.record(ID, NAME, state, summary, fix, detail);
    }

    /**
     * Whether iCUE is installed, as distinct from running. The bundled SDK
     * loads fine without it, so a failed session alone cannot tell "not
     * installed" from "not started", and those need different advice.
     */
    private static boolean iCueInstalled() {
        for (String dll : ICUE_INSTALL_DLLS) {
            if (Files.isDirectory(Path.of(dll).getParent())) return true;
        }
        return false;
    }

    /**
     * One log line per session, whatever goes wrong.
     *
     * <p>Every failure path in this class routes through here, and the latch
     * is deliberately shared across all of them: a connection problem tends to
     * be a persistent condition, and something in a 30Hz loop can hit the same
     * failure thirty times a second. One line is a diagnostic; thirty thousand
     * is a denial-of-service on the log file.
     */
    private void logOnce(String message) {
        if (!loggedOnce) {
            RGBProfileMod.LOGGER.info("RGB Profile: {}", message);
            loggedOnce = true;
        }
    }
}
