import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.AuraBackend;
import com.everythingrgbprofile.sdk.BackendHealth;
import com.everythingrgbprofile.sdk.CorsairBackend;
import com.everythingrgbprofile.sdk.ICueSdk;
import com.everythingrgbprofile.sdk.LightingBackend;
import com.everythingrgbprofile.sdk.LogitechBackend;
import com.everythingrgbprofile.sdk.MysticLightBackend;
import com.everythingrgbprofile.sdk.OpenRgbBackend;
import com.everythingrgbprofile.sdk.RazerChromaBackend;
import com.everythingrgbprofile.sdk.SteelSeriesBackend;
import com.everythingrgbprofile.sdk.SurfaceMapper;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Lets {@link PatternStudio} and {@link ColorTuner} drive your actual
 * lighting, so you can tune a pattern on real hardware without launching
 * Minecraft. Genuinely the single biggest quality-of-life win in this project.
 * The alternative is a full game launch per parameter tweak, and nobody has
 * that kind of time.
 *
 * <h2>Two ways in, picked for you</h2>
 * <b>Every backend</b>, when the tool is launched on the mod's full runtime
 * classpath ({@code gradlew colorTuner} / {@code gradlew patternStudio}). Then
 * this drives the mod's own backend classes, the exact ones the game uses:
 * Corsair, OpenRGB, Aura, Razer, Logitech, SteelSeries and MSI, all at once,
 * whatever answers. Only Corsair has been tested on real hardware. The rest
 * are experimental here exactly as much as they are in game, which is the
 * point: if a backend's colours look wrong in the tuner, they look wrong in
 * game too.
 *
 * <p><b>Corsair only</b>, on a plain {@code java -cp} launch. The mod's
 * backends log through {@code RGBProfileMod}, which drags NeoForge in during
 * class verification, which a hand-rolled classpath can't satisfy. So that
 * launch falls back to {@link DirectCorsair}, a small second implementation
 * that only needs JNA and iCUE's DLL. Kept so the old command line still
 * works; the status line says which one you got.
 *
 * <p>Also deliberately a separate class from PatternStudio, so the JVM never
 * loads it (and therefore never needs JNA at all) until you tick the box.
 * The studio runs fine with no JNA and no hardware.
 */
public final class HardwareBridge {

    /** Whichever way in actually connected. */
    private interface Link {
        String connect();
        boolean ok();
        KeyGrid grid();
        String prepare(KeyGrid studioGrid);
        void push(Map<KeyGrid.LedRef, RGBColor> frame);
        String mappingReport(KeyGrid studioGrid);
        void close();
    }

    private Link link;

    /**
     * Connects to whatever lighting is there.
     *
     * <p>Returns a STRING rather than throwing or returning a boolean: every
     * failure path here hands back something displayable, and the tools put
     * it straight in their status bar. When you are debugging why a keyboard
     * won't light up, a reason on screen beats a stack trace in a terminal you
     * had already scrolled past.
     */
    public String connect() {
        if (modBackendsAvailable()) {
            link = new ModBackends();
            return link.connect();
        }
        link = new DirectCorsair();
        return "Corsair only (plain java -cp; gradlew colorTuner drives every backend): " + link.connect();
    }

    /**
     * True when the mod's real backends can load here.
     *
     * <p>The test is just "can RGBProfileMod initialise", because that's
     * the exact thing that breaks. Every backend logs through it, and on a
     * plain classpath its verification trips over missing NeoForge classes.
     * Asking directly beats guessing from what's on the classpath.
     */
    private static boolean modBackendsAvailable() {
        try {
            Class.forName("com.everythingrgbprofile.RGBProfileMod");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public boolean ok() {
        return link != null && link.ok();
    }

    /** Real hardware geometry, exposed for inspection: the board that would lead in game. */
    public KeyGrid grid() {
        return link == null ? KeyGrid.empty() : link.grid();
    }

    /**
     * Maps the tool's drawn LEDs onto the real ones, so {@link #push} can
     * translate frames.
     *
     * <p>The translation lives here rather than in the tools deliberately:
     * they then have exactly one grid and one LED identity space, and
     * connecting hardware cannot affect what they draw. Returns a status
     * fragment for the UI.
     */
    public String prepare(KeyGrid studioGrid) {
        return link == null ? "not connected" : link.prepare(studioGrid);
    }

    public void push(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (link != null) link.push(frame);
    }

    /** Which real LEDs got a source, so a dark row is a printout and not a squint. */
    public String mappingReport(KeyGrid studioGrid) {
        return link == null ? "not connected" : link.mappingReport(studioGrid);
    }

    public void close() {
        if (link != null) link.close();
    }

    // ---------------------------------------------------------------
    // Every backend, through the mod's own classes
    // ---------------------------------------------------------------

    /**
     * Drives every backend that answers, each one straight from the drawn
     * board.
     *
     * <p>Similar to the mod's auto mode, with one difference on purpose. In
     * game, one backend leads and the rest copy the leader's LEDs. Here the
     * drawn board is already the source of truth, so every device maps from
     * it directly, one surface at a time, using the mod's {@link
     * SurfaceMapper}. One hop instead of two, and a mouse gets mapped as a
     * mouse instead of being lumped in with whatever keyboard shares its
     * backend.
     *
     * <h2>One thread, always</h2>
     * Every backend call happens on one worker thread owned by this class.
     * The backends are written for exactly one caller thread (the mod's
     * {@code SdkWorkerThread}), and native SDKs don't throw when you break
     * that, they corrupt memory and let you find out later. Aura and MSI are
     * COM on top of that. So the Swing thread never touches a backend. It
     * drops the newest frame in a box and walks off.
     */
    private static final class ModBackends implements Link {

        /**
         * Same backends, same order as the mod's {@code BackendRegistry}.
         * That list is private to the mod, and OpenRGB needs building
         * differently here anyway (see {@link #openRgb}), so it's copied.
         * Add a backend there, add it here, or the tuner quietly won't know
         * it exists.
         */
        private static final List<Supplier<LightingBackend>> BACKENDS = List.of(
                CorsairBackend::new,
                ModBackends::openRgb,
                AuraBackend::new,
                RazerChromaBackend::new,
                LogitechBackend::new,
                SteelSeriesBackend::new,
                MysticLightBackend::new);

        /** Where backends may unpack DLLs and keep crash markers. Under build/, so git never sees it. */
        private static final Path WORK_DIR = Path.of("build", "tuner-hardware");

        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tuner-hardware");
            // Daemon, so closing the window closes the tool even if a vendor
            // SDK is mid-sulk.
            t.setDaemon(true);
            return t;
        });

        // Everything below is only touched on the worker, except the volatiles.
        private final List<LightingBackend> live = new ArrayList<>();
        /** Per live backend: real LED -> the drawn key whose colour it shows. */
        private final List<Map<KeyGrid.LedRef, KeyGrid.LedRef>> maps = new ArrayList<>();
        /** Per live backend: a frame map reused every frame. */
        private final List<Map<KeyGrid.LedRef, RGBColor>> scratch = new ArrayList<>();

        private final AtomicReference<Map<KeyGrid.LedRef, RGBColor>> pending = new AtomicReference<>();
        private final AtomicBoolean drainQueued = new AtomicBoolean();
        private volatile boolean anyConnected;
        private volatile KeyGrid leaderGrid = KeyGrid.empty();

        /**
         * OpenRGB, pointed at loopback unless told otherwise.
         *
         * <p>Its no-arg constructor reads the address from the mod's config,
         * and outside a loaded game that read throws. So the address comes
         * from {@code -Dtuner.openrgb.host} / {@code -Dtuner.openrgb.port}
         * instead, defaulting to the same 127.0.0.1:6742 the config does.
         */
        private static LightingBackend openRgb() {
            String host = System.getProperty("tuner.openrgb.host", "127.0.0.1");
            int port = Integer.getInteger("tuner.openrgb.port", 6742);
            return OpenRgbBackend.pointedAt(host, port);
        }

        @Override
        public String connect() {
            return onWorker(() -> {
                Files.createDirectories(WORK_DIR);
                List<String> names = new ArrayList<>();
                for (Supplier<LightingBackend> factory : BACKENDS) {
                    LightingBackend b;
                    try {
                        b = factory.get();
                    } catch (Throwable t) {
                        continue;
                    }
                    boolean up = false;
                    try {
                        // Every device class, like the probes. The tuner is
                        // for looking at colour, and a mouse is a perfectly
                        // good place to look at it.
                        up = b.connect(WORK_DIR, cls -> true);
                    } catch (Throwable ignored) {
                        // Backends record their own failures; one that throws
                        // anyway just doesn't make the list.
                    }
                    if (up) {
                        live.add(b);
                        // Counted per surface: allKeys() is the main device
                        // only, which undersells a backend with a mouse too.
                        int leds = 0;
                        for (KeyGrid.Surface s : b.keyGrid().surfaces()) leds += s.leds().size();
                        names.add(b.displayName() + " (" + leds + " LEDs)");
                    } else {
                        try {
                            b.shutdown();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (live.isEmpty()) return "no lighting software answered: " + whyNot();

                // The board that would lead in game: first one whose main
                // device is a keyboard, else whatever has LEDs.
                LightingBackend lead = live.get(0);
                for (LightingBackend b : live) {
                    KeyGrid.Surface primary = b.keyGrid().primarySurface();
                    if (primary != null && primary.deviceClass() == KeyGrid.DeviceClass.KEYBOARD) {
                        lead = b;
                        break;
                    }
                }
                leaderGrid = lead.keyGrid();
                anyConnected = true;
                return "live: " + String.join(", ", names);
            }, 60);
        }

        /**
         * One line on why nothing connected, straight from what each backend
         * told {@code BackendHealth}. "Not installed" is skipped, since most
         * people own most brands exactly zero times, and a status bar listing
         * every one of them is just noise.
         */
        private static String whyNot() {
            List<String> parts = new ArrayList<>();
            for (BackendHealth.Diagnosis d : BackendHealth.diagnoses()) {
                if (d.state() == BackendHealth.State.NOT_INSTALLED) continue;
                parts.add(d.displayName() + ": " + d.summary());
            }
            return parts.isEmpty() ? "none of them are installed" : String.join(" | ", parts);
        }

        @Override
        public boolean ok() {
            return anyConnected;
        }

        @Override
        public KeyGrid grid() {
            return leaderGrid;
        }

        @Override
        public String prepare(KeyGrid studioGrid) {
            return onWorker(() -> {
                maps.clear();
                scratch.clear();
                int real = 0;
                int sourced = 0;
                int devices = 0;
                List<KeyGrid.LedPosition> drawn = studioGrid.allKeys();
                for (LightingBackend b : live) {
                    Map<KeyGrid.LedRef, KeyGrid.LedRef> map = new HashMap<>();
                    // Per surface, never the whole backend at once. Positions
                    // are normalised per device, so a mouse and a keyboard
                    // dumped in one list would get clustered into rows
                    // together, which is nonsense.
                    for (KeyGrid.Surface s : b.keyGrid().surfaces()) {
                        map.putAll(SurfaceMapper.map(s.leds(), drawn));
                        real += s.leds().size();
                        devices++;
                    }
                    sourced += map.size();
                    maps.add(map);
                    scratch.add(new HashMap<>());
                }
                return String.format("%d/%d real LEDs sourced across %d device(s)", sourced, real, devices);
            }, 10);
        }

        /**
         * Hands the frame to the worker and returns immediately.
         *
         * <p>Newest frame wins. If a backend is slow (the HTTP ones can be),
         * frames that pile up behind it get dropped rather than queued, so
         * the board on the keyboard never lags further and further behind the
         * board on screen while you drag a slider.
         *
         * <p>The frame is copied because the caller owns it and may reuse it
         * the instant this returns.
         */
        @Override
        public void push(Map<KeyGrid.LedRef, RGBColor> frame) {
            if (!anyConnected) return;
            pending.set(new HashMap<>(frame));
            if (drainQueued.compareAndSet(false, true)) {
                try {
                    worker.execute(this::drain);
                } catch (RuntimeException closed) {
                    drainQueued.set(false);
                }
            }
        }

        private void drain() {
            drainQueued.set(false);
            Map<KeyGrid.LedRef, RGBColor> frame = pending.getAndSet(null);
            if (frame == null) return;
            boolean any = false;
            for (int i = 0; i < live.size() && i < maps.size(); i++) {
                LightingBackend b = live.get(i);
                try {
                    if (!b.connected()) continue;
                    Map<KeyGrid.LedRef, RGBColor> out = scratch.get(i);
                    out.clear();
                    for (Map.Entry<KeyGrid.LedRef, KeyGrid.LedRef> m : maps.get(i).entrySet()) {
                        RGBColor c = frame.get(m.getValue());
                        out.put(m.getKey(), c == null ? RGBColor.BLACK : c);
                    }
                    b.applyFrame(out);
                    any |= b.connected();
                } catch (Throwable ignored) {
                    // Unplug something mid-session and the tool carries on
                    // drawing to its own canvas instead of dying. Which is
                    // exactly what you want from a tool you leave open for
                    // hours.
                }
            }
            anyConnected = any;
        }

        @Override
        public String mappingReport(KeyGrid studioGrid) {
            return onWorker(() -> {
                StringBuilder out = new StringBuilder();
                out.append(String.format("drawn keys %d%n", studioGrid.allKeys().size()));
                for (int i = 0; i < live.size(); i++) {
                    LightingBackend b = live.get(i);
                    Map<KeyGrid.LedRef, KeyGrid.LedRef> map = i < maps.size() ? maps.get(i) : Map.of();
                    String experimental = b instanceof CorsairBackend ? "" : " (experimental)";
                    for (KeyGrid.Surface s : b.keyGrid().surfaces()) {
                        int lit = 0;
                        for (KeyGrid.LedPosition p : s.leds()) if (map.containsKey(p.ref())) lit++;
                        int dark = s.leds().size() - lit;
                        out.append(String.format("%s%s / %s (%s): %d LEDs, %d sourced%s%n",
                                b.displayName(), experimental, s.model(), s.deviceClass(),
                                s.leds().size(), lit, dark > 0 ? "  <-- " + dark + " never written" : ""));
                    }
                }
                return out.toString();
            }, 10);
        }

        @Override
        public void close() {
            anyConnected = false;
            try {
                onWorker(() -> {
                    for (LightingBackend b : live) {
                        try {
                            b.shutdown();
                        } catch (Throwable ignored) {
                        }
                    }
                    live.clear();
                    maps.clear();
                    scratch.clear();
                    return null;
                }, 10);
            } finally {
                worker.shutdown();
            }
        }

        /**
         * Runs a job on the worker and waits for it. Failures come back as a
         * status string, same contract as everything else here.
         */
        private String onWorker(Callable<String> job, int timeoutSeconds) {
            try {
                return worker.submit(job).get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                return "lighting software took over " + timeoutSeconds + "s to answer, gave up waiting";
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable t = e.getCause();
                return t.getClass().getSimpleName() + ": " + t.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return "already closed";
            }
        }
    }

    // ---------------------------------------------------------------
    // Corsair only, for a plain java -cp launch
    // ---------------------------------------------------------------

    /**
     * Talks to iCUE directly through the mod's {@link ICueSdk} bindings.
     *
     * <h2>Why this duplicates CueSdkBridge instead of reusing it</h2>
     * Reusing the mod's own bridge was the obvious design, was tried, and does
     * not work on a plain classpath. Here is the exact chain, because it is
     * not obvious and it wasted an afternoon:
     *
     * <p>{@code CueSdkBridge} logs through {@code RGBProfileMod.LOGGER}.
     * Loading it therefore loads {@code RGBProfileMod}, which imports eight
     * NeoForge packages that are simply not on a plain {@code java -cp}
     * classpath. The result is a {@code NoClassDefFoundError} that presents as
     * missing JNA and is nothing of the sort. One logger reference, entire
     * dependency tree, dead on arrival.
     *
     * <p>{@link ICueSdk} imports nothing but JNA, so this needs only
     * jna-5.14.0.jar and the DLL. The trade-off is honest: the connect and
     * push logic here is a second implementation and can drift from the
     * mod's. But the SDK <b>bindings</b>, the struct layouts, the calling
     * convention, the bit that is genuinely hard and genuinely dangerous to
     * get wrong, are still shared. The duplicated part is the easy part.
     * {@link ModBackends} has none of this problem, which is why it's the one
     * you get whenever it can load.
     */
    private static final class DirectCorsair implements Link {

        private ICueSdk sdk;
        private boolean connected;
        private String deviceId;
        private ICueSdk.CorsairLedColor[] buffer;
        private final Map<KeyGrid.LedRef, Integer> slotByRef = new HashMap<>();
        /**
         * Real LED slot -> the drawn key whose colour it shows.
         *
         * <p>Deliberately keyed by the REAL slot, which is the inverse of the
         * obvious direction. Mapping drawn-to-real leaves any real LED that is
         * nobody's nearest neighbour unwritten and therefore dark, silently,
         * while the on-screen board shows it lit the whole time. Keyed this
         * way every physical LED has a source by construction, so that failure
         * cannot happen.
         */
        private final Map<Integer, KeyGrid.LedRef> slotSource = new HashMap<>();
        private KeyGrid grid = KeyGrid.empty();

        /** Held in a field: if the SDK's function pointer outlives this, it calls into freed memory. */
        @SuppressWarnings("unused")
        private ICueSdk.SessionStateChangedHandler sessionHandler;

        /** Connects and maps the first keyboard iCUE reports. */
        @Override
        public String connect() {
            try {
                System.setProperty("jna.nosys", "true");

                String dll = findDll();
                if (dll == null) {
                    return "iCUESDK.x64_2019.dll not found - put it in the project root";
                }
                sdk = ICueSdk.load(dll);

                CountDownLatch settled = new CountDownLatch(1);
                AtomicInteger state = new AtomicInteger(ICueSdk.CSS_Invalid);
                sessionHandler = (ctx, data) -> {
                    data.read();
                    state.set(data.state);
                    if (data.state == ICueSdk.CSS_Connected
                            || data.state == ICueSdk.CSS_ConnectionRefused
                            || data.state == ICueSdk.CSS_Timeout
                            || data.state == ICueSdk.CSS_Closed) {
                        settled.countDown();
                    }
                };

                int rc = sdk.CorsairConnect(sessionHandler, Pointer.NULL);
                if (rc != ICueSdk.CE_Success) return "CorsairConnect: " + ICueSdk.errorName(rc);
                if (!settled.await(5, TimeUnit.SECONDS) || state.get() != ICueSdk.CSS_Connected) {
                    return "session " + ICueSdk.sessionStateName(state.get())
                            + " - is iCUE running with SDK access enabled?";
                }

                sdk.CorsairSetLayerPriority(ICueSdk.DEFAULT_LAYER_PRIORITY);

                ICueSdk.CorsairDeviceFilter.ByReference filter =
                        new ICueSdk.CorsairDeviceFilter.ByReference(ICueSdk.CDT_All);
                ICueSdk.CorsairDeviceInfo[] devices =
                        ICueSdk.array(new ICueSdk.CorsairDeviceInfo(), ICueSdk.CORSAIR_DEVICE_COUNT_MAX);
                IntByReference count = new IntByReference();
                rc = sdk.CorsairGetDevices(filter, ICueSdk.CORSAIR_DEVICE_COUNT_MAX, devices[0], count);
                if (rc != ICueSdk.CE_Success) return "CorsairGetDevices: " + ICueSdk.errorName(rc);

                // First keyboard with LEDs wins. The studio draws one board,
                // so there is nothing sensible to do with a second one.
                String model = "?";
                for (int i = 0; i < count.getValue(); i++) {
                    devices[i].read();
                    if ((devices[i].type & ICueSdk.CDT_Keyboard) != 0 && devices[i].ledCount > 0) {
                        deviceId = devices[i].deviceId();
                        model = devices[i].model();
                        break;
                    }
                }
                if (deviceId == null) return "no keyboard reported by iCUE";

                ICueSdk.CorsairLedPosition[] positions =
                        ICueSdk.array(new ICueSdk.CorsairLedPosition(), ICueSdk.CORSAIR_DEVICE_LEDCOUNT_MAX);
                IntByReference ledCount = new IntByReference();
                rc = sdk.CorsairGetLedPositions(deviceId, ICueSdk.CORSAIR_DEVICE_LEDCOUNT_MAX, positions[0], ledCount);
                if (rc != ICueSdk.CE_Success) return "CorsairGetLedPositions: " + ICueSdk.errorName(rc);

                int leds = ledCount.getValue();
                KeyGrid.Builder b = new KeyGrid.Builder();
                b.beginDevice(deviceId, model, KeyGrid.DeviceClass.KEYBOARD);
                buffer = ICueSdk.array(new ICueSdk.CorsairLedColor(), Math.max(1, leds));
                for (int i = 0; i < leds; i++) {
                    positions[i].read();
                    b.addLed(positions[i].id, positions[i].cx, positions[i].cy);
                    slotByRef.put(new KeyGrid.LedRef(deviceId, positions[i].id), i);
                    buffer[i].id = positions[i].id;
                    buffer[i].a = (byte) 255;
                }
                // A-Z only, unlike the mod (which also does 0-9). The studio
                // only needs 'T' for the pillar centre crosshair; the rest are
                // free.
                for (char c = 'A'; c <= 'Z'; c++) {
                    IntByReference luid = new IntByReference();
                    if (sdk.CorsairGetLedLuidForKeyName(deviceId, (byte) c, luid) == ICueSdk.CE_Success
                            && luid.getValue() != 0) {
                        b.addNamedKey(c, new KeyGrid.LedRef(deviceId, luid.getValue()));
                    }
                }
                b.endDevice();
                grid = b.build();

                connected = true;
                return String.format("live: %s, %d LEDs", model, leds);
            } catch (Throwable t) {
                return t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }

        /**
         * Hunts for the DLL in five places, cheapest and most likely first:
         * the project root (drop a copy there and it just works), the two
         * build output locations, then Corsair's own install directories.
         */
        private static String findDll() {
            for (String p : new String[]{
                    "iCUESDK.x64_2019.dll",
                    "src/main/resources/everythingrgbprofiles/native/iCUESDK.x64_2019.dll",
                    "build/resources/main/everythingrgbprofiles/native/iCUESDK.x64_2019.dll",
                    "C:\\Program Files\\Corsair\\CORSAIR iCUE5 Software\\iCUESDK.x64_2019.dll",
                    "C:\\Program Files\\Corsair\\CORSAIR iCUE4 Software\\iCUESDK.x64_2019.dll"}) {
                if (Files.isRegularFile(Path.of(p))) return Path.of(p).toAbsolutePath().toString();
            }
            return null;
        }

        @Override
        public boolean ok() {
            return connected;
        }

        @Override
        public KeyGrid grid() {
            return grid;
        }

        @Override
        public String prepare(KeyGrid studioGrid) {
            slotSource.clear();
            List<List<KeyGrid.LedPosition>> realRows = rows(grid.allKeys());
            List<List<KeyGrid.LedPosition>> drawnRows = rows(studioGrid.allKeys());

            // Only rows with a real handful of LEDs count as key rows. A K70
            // reports its logo and a couple of indicators as their own one-LED
            // "rows", and letting one of those consume a row slot shifts the
            // pairing by one and misaligns the entire board.
            List<List<KeyGrid.LedPosition>> realKeyRows = new ArrayList<>();
            for (List<KeyGrid.LedPosition> r : realRows) if (r.size() >= 5) realKeyRows.add(r);

            int paired = Math.min(realKeyRows.size(), drawnRows.size());
            for (int i = 0; i < paired; i++) {
                List<KeyGrid.LedPosition> real = realKeyRows.get(i);
                List<KeyGrid.LedPosition> drawn = drawnRows.get(i);
                for (KeyGrid.LedPosition r : real) {
                    // Within a paired row, x alone decides. The y values come
                    // from two different boards, and comparing them across
                    // boards is precisely what went wrong before.
                    KeyGrid.LedPosition best = null;
                    double bestD = Double.MAX_VALUE;
                    for (KeyGrid.LedPosition d : drawn) {
                        double dx = Math.abs(d.x() - r.x());
                        if (dx < bestD) { bestD = dx; best = d; }
                    }
                    Integer slot = slotByRef.get(r.ref());
                    if (slot != null && best != null) slotSource.put(slot, best.ref());
                }
            }

            // Anything left over - the logo, the indicators, a row the drawn
            // layout has no counterpart for - falls back to plain nearest in
            // 2D so it still lights rather than sitting dark beside lit keys.
            int fallback = 0;
            for (KeyGrid.LedPosition r : grid.allKeys()) {
                Integer slot = slotByRef.get(r.ref());
                if (slot == null || slotSource.containsKey(slot)) continue;
                KeyGrid.LedPosition best = null;
                double bestD = Double.MAX_VALUE;
                for (KeyGrid.LedPosition d : studioGrid.allKeys()) {
                    double dist = Math.hypot(d.x() - r.x(), d.y() - r.y());
                    if (dist < bestD) { bestD = dist; best = d; }
                }
                if (best != null) { slotSource.put(slot, best.ref()); fallback++; }
            }

            return String.format("%d/%d real LEDs sourced (%d rows paired, %d by fallback)",
                    slotSource.size(), grid.allKeys().size(), paired, fallback);
        }

        /**
         * Clusters LEDs into rows by y, top to bottom.
         *
         * <p>The gap test is a fraction of the board's own normalised height
         * rather than a fixed distance, so it holds whatever the device
         * reports. Rows on a keyboard are far enough apart that this needs no
         * cleverness: the only thing it must avoid is merging two adjacent
         * rows, and 4% of board height sits comfortably below any real row
         * spacing while staying above the jitter within one row.
         */
        private static List<List<KeyGrid.LedPosition>> rows(List<KeyGrid.LedPosition> leds) {
            List<KeyGrid.LedPosition> sorted = new ArrayList<>(leds);
            sorted.sort(java.util.Comparator.comparingDouble(KeyGrid.LedPosition::y));
            List<List<KeyGrid.LedPosition>> out = new ArrayList<>();
            List<KeyGrid.LedPosition> cur = new ArrayList<>();
            double last = Double.NaN;
            for (KeyGrid.LedPosition q : sorted) {
                if (!cur.isEmpty() && q.y() - last > 0.04) {
                    out.add(cur);
                    cur = new ArrayList<>();
                }
                cur.add(q);
                last = q.y();
            }
            if (!cur.isEmpty()) out.add(cur);
            return out;
        }

        /**
         * Pushes one frame to the hardware.
         *
         * <p>Note this pushes EVERY key every frame with no dirty tracking,
         * unlike the mod's bridge. Deliberate: this is a dev tool where every
         * parameter is changing constantly under a slider, so nearly
         * everything is dirty anyway, and the tracking would be pure
         * complexity for no gain.
         *
         * <p>{@code catch (Throwable ignored)} then setting {@code connected =
         * false} is the whole error strategy: unplug the keyboard mid-session
         * and the studio quietly carries on drawing to its own canvas instead
         * of dying.
         */
        @Override
        public void push(Map<KeyGrid.LedRef, RGBColor> frame) {
            if (!connected) return;
            try {
                // Driven from the real LEDs rather than from the frame. Every
                // physical LED is written every frame, so none can be stranded
                // by a gap in the mapping - which is the failure this replaced.
                for (Map.Entry<Integer, KeyGrid.LedRef> e : slotSource.entrySet()) {
                    RGBColor c = frame.get(e.getValue());
                    if (c == null) c = RGBColor.BLACK;
                    int slot = e.getKey();
                    buffer[slot].r = (byte) c.r();
                    buffer[slot].g = (byte) c.g();
                    buffer[slot].b = (byte) c.b();
                    buffer[slot].write();
                }
                sdk.CorsairSetLedColors(deviceId, buffer.length, buffer[0]);
            } catch (Throwable ignored) {
                connected = false;
            }
        }

        /**
         * Reports the real LEDs nothing targets, grouped into rows, so an
         * orphaned row is obvious rather than something you notice by
         * squinting at the keyboard. That's invisible on screen, because the
         * on-screen board is drawn from the studio layout and does not know
         * the hardware exists.
         */
        @Override
        public String mappingReport(KeyGrid studioGrid) {
            if (!connected) return "not connected";
            StringBuilder out = new StringBuilder();
            java.util.Set<Integer> targeted = slotSource.keySet();

            out.append(String.format("real LEDs %d, drawn keys %d, real LEDs with a source %d%n",
                    grid.allKeys().size(), studioGrid.allKeys().size(), targeted.size()));

            // Cluster real LEDs into rows by y. A physical keyboard's rows are
            // far enough apart that a coarse bucket is enough to separate them.
            java.util.TreeMap<Long, java.util.List<KeyGrid.LedPosition>> rows = new java.util.TreeMap<>();
            for (KeyGrid.LedPosition p : grid.allKeys()) {
                rows.computeIfAbsent(Math.round(p.y() * 20), k -> new java.util.ArrayList<>()).add(p);
            }
            out.append(String.format("%-8s %-6s %-6s %-6s %s%n", "row y", "leds", "lit", "dark", ""));
            for (var e : rows.entrySet()) {
                int lit = 0;
                for (KeyGrid.LedPosition p : e.getValue()) {
                    Integer slot = slotByRef.get(p.ref());
                    if (slot != null && targeted.contains(slot)) lit++;
                }
                int dark = e.getValue().size() - lit;
                out.append(String.format("%-8.3f %-6d %-6d %-6d %s%n",
                        e.getKey() / 20.0, e.getValue().size(), lit, dark,
                        dark > 0 ? "<-- " + dark + " never written" : ""));
            }

            // And the drawn rows, so the two coordinate spaces can be compared.
            java.util.TreeMap<Long, Integer> drawnRows = new java.util.TreeMap<>();
            for (KeyGrid.LedPosition p : studioGrid.allKeys()) {
                drawnRows.merge(Math.round(p.y() * 20), 1, Integer::sum);
            }
            out.append("drawn rows at y: ");
            for (var e : drawnRows.entrySet()) out.append(String.format("%.3f(%d) ", e.getKey() / 20.0, e.getValue()));
            out.append(System.lineSeparator());
            return out.toString();
        }

        @Override
        public void close() {
            try {
                if (sdk != null) sdk.CorsairDisconnect();
            } catch (Throwable ignored) {
            }
            connected = false;
        }
    }
}
