package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
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
 * mod is arithmetic on colours. This is the one file capable of hard-crashing
 * the JVM, which is why it is written as defensively as it is and why it has
 * more comment than code in places.
 *
 * <p>It owns the iCUE SDK v4 connection, and it was rewritten against the real
 * headers because an earlier version targeted the older CUE SDK surface that
 * v4 <b>removed</b>. Meaning it could not have linked against the v4 DLL it
 * was loading, on any machine, under any circumstances. Code that read
 * perfectly well and was structurally incapable of working.
 *
 * <h2>Five things that changed, and why every one of them matters</h2>
 *
 * <p><b>1. Connection is asynchronous.</b> {@code CorsairConnect} returns
 * immediately, and the session is only usable once the state callback reports
 * {@code CSS_Connected}.
 *
 * <p>Treating that return code as a handshake result — which is the obvious
 * reading, and the wrong one — hands you a "successful" connection you then
 * cannot use for anything. So this waits on a latch with a bounded timeout
 * instead, and the latch is what actually decides whether we are connected.
 *
 * <p><b>2. No exclusive control.</b> This calls
 * {@code CorsairSetLayerPriority} rather than {@code CorsairRequestControl}.
 * iCUE draws at 127 and SDK clients default to 128, so sitting at 130 puts us
 * on top without seizing the device out from under anybody.
 *
 * <p>That distinction genuinely matters to real people rather than being
 * tidiness for its own sake. With exclusive control, an unclean Minecraft exit
 * — a crash, a task-kill, a power cut — leaves the keyboard stuck displaying
 * OUR lighting with the owner's iCUE profile locked out of their own hardware.
 * Shutdown hooks do not run when a process is killed.
 *
 * <p>Layer priority has no such failure mode. We stop drawing, iCUE carries on
 * as though nothing happened.
 *
 * <p><b>3. Alpha goes out as 255, always.</b> {@code CorsairLedColor} carries
 * an alpha channel that iCUE would composite against its own layers, and it
 * is deliberately not used for that. Every LED is sent fully opaque; all the
 * translucency (Tier 2 over Tier 1, fades, dissolves) is worked out in the
 * {@code Compositor} before the frame gets here, so iCUE only ever receives
 * finished colours.
 *
 * <p><b>4. Unchanged devices get skipped.</b> An earlier loop wrote the full
 * board every frame at 30Hz whether or not a single pixel had moved. Now a
 * device whose LEDs all match the last frame gets no native call at all
 * (one with any change still sends its whole array; see applyFrame). And
 * {@code debounceMillis} sat in the config, fully defined, read by absolutely
 * nothing at all.
 *
 * <p><b>5. Struct arrays are contiguous.</b> Everything goes through
 * {@code Structure.toArray}, without exception.
 *
 * <p>An earlier version built Java arrays of individually allocated
 * Structures, which get scattered anywhere in memory the allocator fancied,
 * and then handed the first one's pointer to native code that walks forward
 * expecting the rest to be right behind it. So it reads whatever happened to
 * be sitting next in the heap and treats it as colour data.
 *
 * <p>That is not a bug you debug. That is a bug you survive.
 */
public final class CueSdkBridge {

    /** CONNECTED means talking to real hardware. NO_OP means everything still runs and nothing is sent anywhere. */
    public enum Mode { CONNECTED, NO_OP }

    private static final String DLL_RESOURCE = "/everythingrgbprofiles/native/iCUESDK.x64_2019.dll";
    private static final String DLL_FILE_NAME = "iCUESDK.x64_2019.dll";

    /** The config key labels we ask the SDK to resolve into real luids at startup. */
    private static final char[] NAMED_KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private static final String ID = "corsair";
    private static final String NAME = "Corsair iCUE";

    /**
     * Where iCUE installs its own copy of the SDK. Hardcoded paths, which is
     * inelegant and also simply correct: these are Corsair's fixed install
     * locations, and there is no registry key or environment variable that
     * reliably beats going and looking. iCUE5 first because it is newer, then
     * iCUE4, then the x86 program-files variant.
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
            // jna.nosys=true tells JNA to use the jnidispatch native packed
            // inside its own jar rather than one found on the system library
            // path, where a mismatched system-wide copy (a 7.0.0 has been seen
            // in the wild) would clash with it.
            //
            // Belt and braces rather than a fix: JNA 5's default for this is
            // already "true" (Native.java in 5.14.0, the version Minecraft
            // ships and this mod uses), so the line only changes anything if
            // something else set it to false before JNA initialised. The mod
            // embeds no JNA of its own; it runs on Minecraft's.
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
     * Finds a DLL: the bundled copy first, then whatever iCUE installed.
     *
     * <p>That fallback settles the redistribution question rather neatly. It
     * means shipping Corsair's DLL can be skipped entirely by anyone who would
     * rather not, <i>and</i> it guarantees the SDK build matches the iCUE the
     * player actually has installed rather than whatever version happened to
     * be current on the day this mod was released. Two problems, one fallback.
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
     * Unpacks the bundled DLL to disk, and makes sure what is on disk IS the
     * bundled DLL before anything loads it.
     *
     * <p>An earlier version extracted once and then trusted whatever file sat
     * at that path forever, which went wrong three ways. A crash or a full
     * disk part way through the write left a truncated DLL that failed to load
     * on every launch after it. A mod update shipping a newer DLL never
     * replaced the old one. And whatever a third party dropped at that path got
     * loaded into the game unchecked.
     *
     * <h2>Why a file that doesn't match gets overwritten, not trusted</h2>
     * Loading a DLL is not reading a file. It is running whatever code is in
     * it, inside Minecraft, with every permission the player's Windows account
     * has. There is no sandbox and no "only touch the keyboard" mode. A broken
     * one takes the game down with a native crash that no Java try/catch can
     * see coming. A malicious one can do anything the player could: read their
     * files, install things, lift saved logins. All without a single prompt,
     * because as far as Windows is concerned the player just launched it.
     *
     * <p>And this path sits in a folder that any program running as the player
     * can write to, so "it's in our folder" proves nothing about who put it
     * there. The only DLL this code has any business vouching for is the
     * exact one shipped inside the jar, so that is the only one it loads. Any
     * other file at this path, whatever it is and however it got there, is
     * treated as damage and replaced. Trusting it because it is sitting where
     * ours should be is precisely how DLL planting works.
     *
     * <p>The fallback in {@link #resolveDll} loads iCUE's own installed copy
     * without this check, and that is not the same gamble: it lives under
     * Program Files, which an ordinary program cannot write to without an
     * administrator prompt.
     *
     * <p>So the copy on disk is compared byte for byte against the one in the
     * jar on every connect. Half a megabyte, once per launch, which is nothing.
     * A mismatch is rewritten through a temporary file and a rename, so the
     * path only ever holds either the old complete file or the new complete
     * file, never half of one.
     *
     * <p>One case cannot be rewritten: on Windows a DLL another running
     * Minecraft has loaded is locked. Then the existing copy is used as it is,
     * which is no worse than what the old version always did, and the log says
     * so.
     */
    private Path extractBundledDll(Path dir) throws IOException {
        byte[] bundled;
        try (InputStream in = CueSdkBridge.class.getResourceAsStream(DLL_RESOURCE)) {
            if (in == null) return null; // not bundled in this build, so the caller falls back to iCUE's copy
            bundled = in.readAllBytes();
        }
        Path target = dir.resolve(DLL_FILE_NAME);
        if (Files.isRegularFile(target) && Files.size(target) == bundled.length
                && Arrays.equals(Files.readAllBytes(target), bundled)) {
            return target;
        }

        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, DLL_FILE_NAME, ".tmp");
        try {
            Files.write(temp, bundled);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (Files.isRegularFile(target)) {
                RGBProfileMod.LOGGER.warn("RGB Profile: could not refresh {} ({}), probably because another "
                        + "running game has it loaded. Using the existing copy.", target.getFileName(), e.toString());
                return target;
            }
            throw e;
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
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
