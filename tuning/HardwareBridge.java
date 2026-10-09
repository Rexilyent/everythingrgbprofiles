import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.ICueSdk;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets {@link PatternStudio} drive your actual keyboard, so you can tune a
 * pattern on real hardware without launching Minecraft. Genuinely the single
 * biggest quality-of-life win in this project — the alternative is a full game
 * launch per parameter tweak.
 *
 * <h2>Why this duplicates CueSdkBridge instead of reusing it</h2>
 * Reusing the mod's own bridge was the obvious design, was tried, and does not
 * work outside the game. Here is the exact chain, because it is not obvious
 * and it wasted an afternoon:
 *
 * <p>{@code CueSdkBridge} logs through {@code RGBProfileMod.LOGGER}. Loading
 * it therefore loads {@code RGBProfileMod}, which imports eight NeoForge
 * packages that are simply not on a plain {@code java -cp} classpath. The
 * result is a {@code NoClassDefFoundError} that presents as missing JNA and is
 * nothing of the sort. One logger reference, entire dependency tree, dead on
 * arrival.
 *
 * <p>{@link ICueSdk} imports nothing but JNA, so this class needs only
 * jna-5.14.0.jar and the DLL. The trade-off is honest: the connect and push
 * logic here is a second implementation and can drift from the mod's. But the
 * SDK <b>bindings</b> — the struct layouts, the calling convention, the bit
 * that is genuinely hard and genuinely dangerous to get wrong — are still
 * shared. The duplicated part is the easy part.
 *
 * <p>Also deliberately a separate class from PatternStudio, so the JVM never
 * loads it — and therefore never needs JNA at all — until you tick the box.
 * The studio runs fine with no JNA and no keyboard.
 */
public final class HardwareBridge {

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
     * nobody's nearest neighbour unwritten and therefore dark, silently, while
     * the on-screen board shows it lit the whole time. Keyed this way every
     * physical LED has a source by construction, so that failure cannot happen.
     */
    private final Map<Integer, KeyGrid.LedRef> slotSource = new HashMap<>();
    private KeyGrid grid = KeyGrid.empty();

    /** Held in a field: if the SDK's function pointer outlives this, it calls into freed memory. */
    @SuppressWarnings("unused")
    private ICueSdk.SessionStateChangedHandler sessionHandler;

    /**
     * Connects and maps the first keyboard iCUE reports.
     *
     * <p>Returns a STRING rather than throwing or returning a boolean: every
     * failure path here hands back something displayable, and the studio puts
     * it straight in its status bar. When you are debugging why a keyboard
     * won't light up, "session ConnectionRefused - is iCUE running with SDK
     * access enabled?" on screen beats a stack trace in a terminal you had
     * already scrolled past.
     */
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

            // First keyboard with LEDs wins. The studio draws one board, so
            // there is nothing sensible to do with a second one.
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
            // A-Z only, unlike the mod (which also does 0-9). The studio only
            // needs 'T' for the pillar centre crosshair; the rest are free.
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
     * Hunts for the DLL in five places, cheapest and most likely first: the
     * project root (drop a copy there and it just works), the two build
     * output locations, then Corsair's own install directories.
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

    public boolean ok() {
        return connected;
    }

    /** Real hardware geometry, exposed for inspection. */
    public KeyGrid grid() {
        return grid;
    }

    /**
     * Maps the studio's drawn LEDs onto this board's real ones by nearest
     * normalised position, so {@link #push} can translate frames.
     *
     * <p>The translation lives here rather than in the studio deliberately:
     * the studio then has exactly one grid and one LED identity space, and
     * connecting hardware cannot affect what it draws. Returns a status
     * fragment for the UI.
     */
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
                // Within a paired row, x alone decides. The y values come from
                // two different boards, and comparing them across boards is
                // precisely what went wrong before.
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

        // Anything left over - the logo, the indicators, a row the drawn layout
        // has no counterpart for - falls back to plain nearest in 2D so it
        // still lights rather than sitting dark beside lit keys.
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
     * <p>The gap test is a fraction of the board's own normalised height rather
     * than a fixed distance, so it holds whatever the device reports. Rows on a
     * keyboard are far enough apart that this needs no cleverness: the only
     * thing it must avoid is merging two adjacent rows, and 4% of board height
     * sits comfortably below any real row spacing while staying above the
     * jitter within one row.
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
     * <p>Note this pushes EVERY key every frame with no dirty tracking, unlike
     * the mod's bridge. Deliberate: this is a dev tool where every parameter
     * is changing constantly under a slider, so nearly everything is dirty
     * anyway, and the tracking would be pure complexity for no gain.
     *
     * <p>{@code catch (Throwable ignored)} then setting {@code connected =
     * false} is the whole error strategy: unplug the keyboard mid-session and
     * the studio quietly carries on drawing to its own canvas instead of
     * dying. Which is exactly what you want from a tool you leave open for
     * hours.
     */
    public void push(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (!connected) return;
        try {
            // Driven from the real LEDs rather than from the frame. Every
            // physical LED is written every frame, so none can be stranded by a
            // gap in the mapping - which is the failure this replaced.
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
     * Diagnostic for the studio-to-real mapping.
     *
     * <p>{@link #prepare} maps every drawn key to its nearest real LED, which
     * is many-to-one: two drawn keys can land on the same real LED, and a real
     * LED that is nobody's nearest neighbour is <b>never written and stays
     * dark</b> no matter what the pattern does. That is invisible on screen,
     * because the on-screen board is drawn from the studio layout and does not
     * know the hardware exists.
     *
     * <p>Reports the real LEDs nothing targets, grouped into rows, so an
     * orphaned row is obvious rather than something you notice by squinting at
     * the keyboard.
     */
    public String mappingReport(KeyGrid studioGrid) {
        if (!connected) return "not connected";
        StringBuilder out = new StringBuilder();
        java.util.Set<Integer> targeted = slotSource.keySet();

        out.append(String.format("real LEDs %d, drawn keys %d, real LEDs with a source %d%n",
                grid.allKeys().size(), studioGrid.allKeys().size(), targeted.size()));

        // Cluster real LEDs into rows by y. A physical keyboard's rows are far
        // enough apart that a coarse bucket is enough to separate them.
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

    public void close() {
        try {
            if (sdk != null) sdk.CorsairDisconnect();
        } catch (Throwable ignored) {
        }
        connected = false;
    }
}
