package com.everythingrgbprofile.keymap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where every LED physically is. The map of your desk, essentially — every
 * pattern in the mod plots against this, so if the geometry here is wrong,
 * everything downstream is wrong too, and wrong in a way that still looks
 * deliberate, which makes it hard to spot.
 *
 * <h2>Identity: one type, no strings</h2>
 * There is exactly ONE key identity in this codebase: {@link LedRef}, a real
 * device id plus the LED id that device's backend uses.
 *
 * <p>This design came out of a bad bug. An earlier version had THREE
 * colliding string namespaces — config labels ({@code "H"}), synthetic grid
 * coordinates ({@code "R0C12"}), and stringified SDK ids — all resolved
 * through {@code Integer.parseInt} with a fallback table that returned
 * <b>0</b> for anything it didn't recognise.
 *
 * <p>Zero is a valid LED. So every unrecognised key silently collapsed onto
 * the same physical LED, and an entire board's worth of effects piled onto one
 * light. No exception, no warning, just one very busy key and a lot of
 * confusion.
 *
 * <p>Now: named config keys are resolved ONCE, when the backend builds the
 * grid (on Corsair via {@code CorsairGetLedLuidForKeyName}), and stored as
 * LedRefs like everything else. Patterns never see a string. There is nothing
 * left to parse, and therefore nothing left to parse wrongly.
 *
 * <h2>Geometry: keyboard-primary, per-device normalisation</h2>
 * Each device gets its own 0..1 {@link Surface}, normalised independently.
 *
 * <p>The tempting alternative — one shared bounding box across all devices —
 * breaks immediately. Devices report positions in their own origin, and
 * Corsair's SDK header notes that DIY/cooler/headset devices use <i>logical units</i>
 * rather than millimetres. Share a coordinate space and a 4-LED mouse sitting
 * off to the right gets to distort a 423mm keyboard's entire geometry, so your
 * spiral ends up centred somewhere over the mousepad.
 *
 * <p>The keyboard is the <b>primary surface</b>. Every positional pattern
 * (sweep, spiral, ring, drift, twinkle) plots against it, and it's what
 * {@link #centerX()}, {@link #maxRadius()} and {@link #topRow()} describe.
 * Auxiliary devices are enumerated and addressable but geometrically
 * independent — so a future mouse/headset/motherboard pass can drive them from
 * their own surface without touching a line of this.
 */
public final class KeyGrid {

    /** Device categories, matching the CorsairDeviceType bits we care about. */
    public enum DeviceClass {
        KEYBOARD,
        MOUSE,
        MOUSEMAT,
        HEADSET,
        MEMORY,
        COOLING,
        MOTHERBOARD,
        OTHER;

        /**
         * Collapses a CorsairDeviceType BITMASK into one class. Order matters:
         * a device can legitimately report several bits (a keyboard with a
         * built-in LED strip, a headset stand that's also a controller), and
         * first match wins — keyboard first, because if there's a keyboard in
         * there, it's a keyboard.
         */
        public static DeviceClass fromCorsairType(int type) {
            if ((type & 0x0001) != 0) return KEYBOARD;
            if ((type & 0x0002) != 0) return MOUSE;
            if ((type & 0x0004) != 0) return MOUSEMAT;
            if ((type & (0x0008 | 0x0010)) != 0) return HEADSET;
            if ((type & 0x0080) != 0) return MEMORY;
            if ((type & (0x0020 | 0x0040 | 0x0100)) != 0) return COOLING;
            if ((type & (0x0200 | 0x0400)) != 0) return MOTHERBOARD;
            return OTHER;
        }

        /** The config key this class is gated behind, e.g. {@code [devices] keyboardEnabled}. */
        public String configKey() {
            return switch (this) {
                case KEYBOARD -> "keyboardEnabled";
                case MOUSE -> "mouseEnabled";
                case MOUSEMAT -> "mousematEnabled";
                case HEADSET -> "headsetEnabled";
                case MEMORY -> "memoryEnabled";
                case COOLING -> "coolingEnabled";
                case MOTHERBOARD -> "motherboardEnabled";
                case OTHER -> "otherDevicesEnabled";
            };
        }
    }

    /**
     * The one and only key identity in this codebase. A record, so equals and
     * hashCode are correct by construction — which matters enormously, because
     * these are map keys absolutely everywhere.
     *
     * <p>deviceId is part of the identity, not decoration: luids are unique
     * only WITHIN a device, so two devices can each have an LED 47.
     */
    public record LedRef(String deviceId, int luid) {
    }

    /**
     * An LED's identity plus where it is: normalised 0..1 within its own
     * device's bounding box, AND the raw values.
     *
     * <p>Both are kept because both are genuinely needed. Normalised is what
     * patterns want (layout-independent). Raw is what tells you the board is
     * 409mm x 122mm rather than square — see {@link KeyGrid#aspectRatio()},
     * without which every "circle" this mod draws is a wide flat ellipse.
     */
    public record LedPosition(LedRef ref, double x, double y, double rawX, double rawY) {
    }

    /** One device's LEDs and derived geometry. */
    public static final class Surface {
        private final String deviceId;
        private final String model;
        private final DeviceClass deviceClass;
        private final List<LedPosition> leds;
        private final List<LedRef> topRow;
        private final double centerX;
        private final double centerY;
        private final double maxRadius;
        private final double widthRaw;
        private final double heightRaw;

        Surface(String deviceId, String model, DeviceClass deviceClass, List<LedPosition> leds, double widthRaw, double heightRaw) {
            this.deviceId = deviceId;
            this.model = model;
            this.deviceClass = deviceClass;
            this.leds = List.copyOf(leds);
            this.widthRaw = widthRaw;
            this.heightRaw = heightRaw;

            double sx = 0, sy = 0, minY = Double.MAX_VALUE;
            for (LedPosition p : leds) {
                sx += p.x();
                sy += p.y();
                minY = Math.min(minY, p.y());
            }
            int n = Math.max(1, leds.size());
            this.centerX = sx / n;
            this.centerY = sy / n;

            double maxD = 0;
            for (LedPosition p : leds) {
                maxD = Math.max(maxD, Math.hypot(p.x() - centerX, p.y() - centerY));
            }
            this.maxRadius = Math.max(1e-6, maxD);

            // Top row = the highest 8% BAND, ordered left to right. A band,
            // not an exact row, and the tolerance is in normalised space so it
            // behaves identically on a 60% and a full-size K95.
            //
            // An earlier version tested raw-millimetre equality to within 0.001mm.
            // Function rows are physically staggered, so on most real boards
            // that matched exactly one key: the night indicator was a progress
            // bar one pixel wide.
            final double band = leds.isEmpty() ? 0 : minY + 0.08;
            List<LedPosition> top = new ArrayList<>();
            for (LedPosition p : leds) {
                if (p.y() <= band) top.add(p);
            }
            top.sort(Comparator.comparingDouble(LedPosition::x));
            List<LedRef> refs = new ArrayList<>(top.size());
            for (LedPosition p : top) refs.add(p.ref());
            this.topRow = List.copyOf(refs);
        }

        public String deviceId() {
            return deviceId;
        }

        public String model() {
            return model;
        }

        public DeviceClass deviceClass() {
            return deviceClass;
        }

        public List<LedPosition> leds() {
            return leds;
        }

        public List<LedRef> topRow() {
            return topRow;
        }

        public double centerX() {
            return centerX;
        }

        public double centerY() {
            return centerY;
        }

        public double maxRadius() {
            return maxRadius;
        }

        /** Physical width in the device's own units (mm for keyboards). */
        public double widthRaw() {
            return widthRaw;
        }

        /** Physical height in the same units. */
        public double heightRaw() {
            return heightRaw;
        }
    }

    private final List<Surface> surfaces;
    private final Surface primary;
    private final Map<LedRef, LedPosition> byRef;
    private final Map<Character, LedRef> namedKeys;
    private final List<LedPosition> primaryLeds;
    private final double keyWidth;
    private final double extentX;
    private final double extentY;

    public KeyGrid(List<Surface> surfaces, Map<Character, LedRef> namedKeys) {
        this.surfaces = List.copyOf(surfaces);
        this.namedKeys = Map.copyOf(namedKeys);

        Surface best = null;
        for (Surface s : surfaces) {
            if (s.deviceClass() == DeviceClass.KEYBOARD
                    && (best == null || s.leds().size() > best.leds().size())) {
                best = s;
            }
        }
        // No keyboard? Fall back to whichever enabled device has the most
        // LEDs. A mousemat-only user still gets every positional pattern, just
        // plotted across their mousepad — a perfectly good outcome for four
        // lines of code.
        if (best == null) {
            for (Surface s : surfaces) {
                if (best == null || s.leds().size() > best.leds().size()) best = s;
            }
        }
        this.primary = best;
        this.primaryLeds = best != null ? best.leds() : List.of();
        this.keyWidth = computeKeyWidth(primaryLeds);

        // Half-extents from the centre — and the reason they exist is worth
        // internalising before writing any positional pattern.
        //
        // maxRadius() is the CORNER distance (~0.707 in normalised space).
        // Draw a circle at maxRadius and most of its arc is OFF THE BOARD, and
        // every off-board sample snaps to the nearest edge key — which turns a
        // sweeping curve into confetti stuck along the top and bottom rows.
        // SpiralInPattern's class doc has the long version.
        //
        // Want to sweep the whole board without leaving it? Trace an ellipse
        // of (extentX, extentY). Not a circle at maxRadius.
        double ex = 0, ey = 0;
        double cx0 = primary != null ? primary.centerX() : 0.5;
        double cy0 = primary != null ? primary.centerY() : 0.5;
        for (LedPosition p2 : primaryLeds) {
            ex = Math.max(ex, Math.abs(p2.x() - cx0));
            ey = Math.max(ey, Math.abs(p2.y() - cy0));
        }
        this.extentX = Math.max(1e-6, ex);
        this.extentY = Math.max(1e-6, ey);

        Map<LedRef, LedPosition> index = new HashMap<>();
        for (Surface s : surfaces) {
            for (LedPosition p : s.leds()) index.put(p.ref(), p);
        }
        this.byRef = Map.copyOf(index);
    }

    // --- primary-surface geometry (what every pattern plots against) ------

    /** Every LED on the primary surface. This is what "the whole board" means to a pattern. */
    public List<LedPosition> allKeys() {
        return primaryLeds;
    }

    public List<LedRef> topRow() {
        return primary != null ? primary.topRow() : List.of();
    }

    public double centerX() {
        return primary != null ? primary.centerX() : 0.5;
    }

    public double centerY() {
        return primary != null ? primary.centerY() : 0.5;
    }

    public double maxRadius() {
        return primary != null ? primary.maxRadius() : 1.0;
    }

    /** Half-width of the primary surface from its centre, in normalised units. */
    public double extentX() {
        return extentX;
    }

    /** Half-height of the primary surface from its centre, in normalised units. */
    public double extentY() {
        return extentY;
    }

    /**
     * Physical height divided by width of the primary surface — about 0.30 on
     * a full-size keyboard (122mm over 409mm).
     *
     * <p>Needed because normalised coordinates span 0..1 on both axes
     * regardless of the board's real shape, so a circle in normalised space is
     * a wide flat ellipse in reality. Anything that should look round to the
     * eye — a dark core, a radial glow — has to scale its y term by this.
     */
    public double aspectRatio() {
        if (primary == null || primary.widthRaw() <= 0) return 1.0;
        return primary.heightRaw() / primary.widthRaw();
    }

    /**
     * Normalised distance, NOT aspect-corrected. Fine for rings, which want to
     * fill the board anyway; wrong for anything that must look genuinely
     * circular — that needs the {@link #aspectRatio()} treatment, as
     * CoreEmitterPattern does.
     */
    public double distanceFromCenter(LedPosition p) {
        return Math.hypot(p.x() - centerX(), p.y() - centerY());
    }

    public double angleFromCenter(LedPosition p) {
        return Math.atan2(p.y() - centerY(), p.x() - centerX());
    }

    /**
     * One key-width in normalised units on the primary surface.
     *
     * <p>Ring thicknesses and particle sizes are authored in key-widths (see
     * {@code sensorRingThicknessKeys} in the config), because that's the unit
     * a human tuning a value can actually picture. An earlier version hardcoded
     * {@code maxRadius / 12} — a guess that drifts badly between a full-size
     * board and a tenkeyless. Computed once at construction.
     */
    public double keyWidthNormalised() {
        return keyWidth;
    }

    /**
     * Median nearest-neighbour distance = one key width. Two deliberate
     * choices in here:
     *
     * <p><b>Median, not mean.</b> Many boards report lighting-pipe LEDs
     * alongside the actual keys, packed far closer together. A mean gets
     * dragged down by those outliers; a median shrugs them off entirely.
     *
     * <p><b>Sampled, not exhaustive.</b> ~40 samples against all LEDs rather
     * than a full O(n^2) pass over 109+. Runs once at startup, and 40 samples
     * is plenty to find the median of a strongly clustered distribution.
     */
    private static double computeKeyWidth(List<LedPosition> leds) {
        if (leds.size() < 2) return 0.05; // one LED has no neighbours; 0.05 is a sane invented default
        List<Double> gaps = new ArrayList<>();
        int step = Math.max(1, leds.size() / 40);
        for (int i = 0; i < leds.size(); i += step) {
            LedPosition self = leds.get(i);
            double nearest = Double.MAX_VALUE;
            for (LedPosition other : leds) {
                if (other.ref().equals(self.ref())) continue;
                nearest = Math.min(nearest, Math.hypot(other.x() - self.x(), other.y() - self.y()));
            }
            if (nearest < Double.MAX_VALUE) gaps.add(nearest);
        }
        if (gaps.isEmpty()) return 0.05;
        gaps.sort(Double::compare);
        return Math.max(1e-4, gaps.get(gaps.size() / 2));
    }

    // --- whole-rig access -------------------------------------------------

    public List<Surface> surfaces() {
        return surfaces;
    }

    public Surface primarySurface() {
        return primary;
    }

    public LedPosition position(LedRef ref) {
        return byRef.get(ref);
    }

    /**
     * Resolves a config key label such as {@code "H"} to a real LED on the
     * keyboard. Returns null if that character isn't present on any connected
     * board; callers must handle that rather than silently lighting LED 0.
     */
    // Note the deliberate lack of a fallback: returning null forces the
    // caller to make a decision. EffectRegistry.resolveKey warns and falls
    // back to the whole board, which is loud and obvious. The earlier
    // behaviour — quietly returning LED 0 — was neither.
    public LedRef namedKey(String label) {
        if (label == null || label.isBlank()) return null;
        return namedKeys.get(Character.toUpperCase(label.trim().charAt(0)));
    }

    public boolean isEmpty() {
        return primaryLeds.isEmpty();
    }

    public static KeyGrid empty() {
        return new KeyGrid(List.of(), Map.of());
    }

    // ---------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------

    public static final class Builder {
        private final List<Surface> surfaces = new ArrayList<>();
        private final Map<Character, LedRef> named = new HashMap<>();
        private final Map<DeviceClass, Integer> counts = new EnumMap<>(DeviceClass.class);

        private String deviceId;
        private String model;
        private DeviceClass deviceClass;
        private List<double[]> pending; // luid, x, y

        public void beginDevice(String deviceId, String model, DeviceClass deviceClass) {
            this.deviceId = deviceId;
            this.model = model;
            this.deviceClass = deviceClass;
            this.pending = new ArrayList<>();
        }

        public void addLed(int luid, double x, double y) {
            if (pending != null) pending.add(new double[]{luid, x, y});
        }

        /**
         * Normalises the pending device into its own 0..1 space and closes it.
         *
         * <p>The min/max pass is what makes the whole thing layout-agnostic:
         * whatever origin and units the device reported, its LEDs come out
         * spanning 0..1. spanX/spanY are kept as the raw physical dimensions,
         * which is where {@link KeyGrid#aspectRatio()} gets its numbers.
         */
        public void endDevice() {
            if (pending == null || pending.isEmpty()) {
                pending = null;
                return;
            }
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] r : pending) {
                minX = Math.min(minX, r[1]);
                maxX = Math.max(maxX, r[1]);
                minY = Math.min(minY, r[2]);
                maxY = Math.max(maxY, r[2]);
            }
            double spanX = Math.max(1e-6, maxX - minX);
            double spanY = Math.max(1e-6, maxY - minY);

            List<LedPosition> leds = new ArrayList<>(pending.size());
            for (double[] r : pending) {
                LedRef ref = new LedRef(deviceId, (int) r[0]);
                leds.add(new LedPosition(ref, (r[1] - minX) / spanX, (r[2] - minY) / spanY, r[1], r[2]));
            }
            surfaces.add(new Surface(deviceId, model, deviceClass, leds, spanX, spanY));
            counts.merge(deviceClass, 1, Integer::sum);
            pending = null;
        }

        public void addNamedKey(char label, LedRef ref) {
            named.put(Character.toUpperCase(label), ref);
        }

        public Map<DeviceClass, Integer> deviceClassCounts() {
            return counts;
        }

        public KeyGrid build() {
            return new KeyGrid(surfaces, named);
        }
    }
}
