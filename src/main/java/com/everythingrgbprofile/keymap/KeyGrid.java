package com.everythingrgbprofile.keymap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where every LED physically is. The map of your desk, basically. Every
 * pattern in this mod plots against this class, so if the geometry in here is
 * wrong then everything downstream is wrong too, and wrong in a way that
 * still looks completely deliberate, which is the worst kind of wrong to try
 * and spot.
 *
 * <h2>Identity: one type, zero strings</h2>
 * There is exactly ONE key identity in this codebase: {@link LedRef}, which is
 * a real device id plus the LED id that device's backend actually uses.
 *
 * <p>This design exists because of a genuinely horrible bug. An earlier
 * version had THREE colliding string namespaces at once: config labels
 * ({@code "H"}), synthetic grid coordinates ({@code "R0C12"}), and stringified
 * SDK ids. All of them went through {@code Integer.parseInt} with a fallback
 * table that returned <b>0</b> for anything it didn't recognise.
 *
 * <p>Zero is a valid LED. So every unrecognised key quietly collapsed onto the
 * same physical light, and an entire board's worth of effects piled up on one
 * key. No exception, no warning, nothing in the log. Just one extremely busy
 * key and several hours of confusion.
 *
 * <p>Now: named config keys are resolved ONCE, when the backend builds the
 * grid (on Corsair via {@code CorsairGetLedLuidForKeyName}), and stored as
 * LedRefs like everything else. Patterns never see a string. There is nothing
 * left to parse, and therefore nothing left to parse wrongly.
 *
 * <h2>Geometry: keyboard-primary, per-device normalisation</h2>
 * Every device gets its own 0..1 {@link Surface}, normalised on its own.
 *
 * <p>The tempting alternative, one shared bounding box across every device,
 * falls apart instantly. Devices report positions in their own origin, and
 * Corsair's SDK header points out that DIY, cooler and headset devices report
 * in <i>logical units</i> rather than millimetres. Share one coordinate space
 * and a four-LED mouse parked off to the right gets an equal vote on a
 * keyboard four hundred millimetres wide, so your spiral ends up centred
 * somewhere over the mousepad.
 *
 * <p>The keyboard is the <b>primary surface</b>. Every positional pattern
 * (sweep, spiral, ring, drift, twinkle) plots against it, and it is what
 * {@link #centerX()}, {@link #maxRadius()} and {@link #topRow()} are all
 * describing. Auxiliary devices are enumerated and addressable but
 * geometrically independent, so a future mouse/headset/motherboard pass can
 * drive them off their own surface without touching a single line in here.
 */
public final class KeyGrid {

    /** Device categories, matching the CorsairDeviceType bits worth caring about. */
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
         * Collapses a CorsairDeviceType BITMASK down to one class. The order of
         * these checks matters and is not alphabetical for a reason: a device
         * can legitimately report several bits at once (a keyboard with a
         * built-in LED strip, a headset stand that also presents as a
         * controller), and first match wins. Keyboard goes first, because if
         * there is a keyboard anywhere in that bitmask, it's a keyboard.
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
     * hashCode are correct by construction, which matters enormously given
     * these things are used as map keys absolutely everywhere.
     *
     * <p>deviceId is part of the identity rather than decoration, because
     * luids are only unique WITHIN a device. Two devices can each happily
     * have an LED 47, and without the deviceId they would be the same key.
     */
    public record LedRef(String deviceId, int luid) {
    }

    /**
     * An LED's identity plus where it is: normalised 0..1 within its own
     * device's bounding box, AND the raw values.
     *
     * <p>Both get kept because both are genuinely needed. Normalised is what
     * patterns want, since it's layout-independent. Raw is the only thing that
     * knows the LED area is roughly three times wider than it is tall rather
     * than square. See {@link KeyGrid#aspectRatio()}, without which every
     * "circle" this mod draws comes out as a wide flat ellipse.
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

            // Top row = the highest 8% BAND, ordered left to right. A band and
            // not an exact row, with the tolerance in normalised space so it
            // behaves identically on a 60% and on a full-size K95.
            //
            // An earlier version tested raw-millimetre equality to within
            // 0.001mm. Function rows are physically staggered on basically
            // every real board, so that matched exactly one key, and the night
            // indicator spent its life as a progress bar one pixel wide.
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
        // No keyboard at all? Fall back to whichever enabled device has the
        // most LEDs. Somebody running a mousemat and nothing else still gets
        // every positional pattern, just plotted across their mousepad
        // instead. Perfectly good outcome for four lines of code.
        if (best == null) {
            for (Surface s : surfaces) {
                if (best == null || s.leds().size() > best.leds().size()) best = s;
            }
        }
        this.primary = best;
        this.primaryLeds = best != null ? best.leds() : List.of();
        this.keyWidth = computeKeyWidth(primaryLeds);

        // Half-extents measured from the centre. Read this before writing any
        // positional pattern, because it is the single easiest thing to get
        // wrong in this entire codebase.
        //
        // maxRadius() is the CORNER distance, around 0.707 in normalised
        // space. Draw a circle at maxRadius and most of its arc is physically
        // OFF THE BOARD, and every off-board sample snaps to the nearest edge
        // key. Your elegant sweeping curve arrives as confetti jammed along
        // the top and bottom rows. SpiralInPattern's class doc has the long
        // version and the diagram.
        //
        // Want to sweep the whole board without falling off it? Trace an
        // ellipse of (extentX, extentY). Not a circle at maxRadius. Ever.
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
     * Physical height divided by width of the primary surface. Around 0.30 on
     * a full-size keyboard, i.e. the lit area is about three times wider than
     * it is tall.
     *
     * <p>Note this measures the LED BOUNDING BOX rather than the plastic, since
     * spanX and spanY come from the reported LED positions. The number is
     * therefore a bit smaller than the board you can put a ruler against, and
     * that is the correct thing for it to be, because patterns are drawing on
     * the lights and not on the bezel.
     *
     * <p>It is needed because normalised coordinates span 0..1 on both axes no
     * matter what shape the board actually is, so a circle in normalised space
     * comes out as a wide flat ellipse in real life. Anything that is supposed
     * to look round to a human eye (a dark core, a radial glow) has to scale
     * its y term by this.
     */
    public double aspectRatio() {
        if (primary == null || primary.widthRaw() <= 0) return 1.0;
        return primary.heightRaw() / primary.widthRaw();
    }

    /**
     * Normalised distance, and NOT aspect-corrected. Perfectly fine for rings,
     * which want to fill the board anyway. Wrong for anything that has to look
     * genuinely circular, which needs the {@link #aspectRatio()} treatment the
     * way CoreEmitterPattern does it.
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
     * <p>Particle sizes and most of the scene patterns are authored in
     * key-widths because that is the one unit a human tuning a number can
     * actually picture, and this is where they get it. The two ring patterns
     * are the exception: they still estimate a key as {@code maxRadius / 12},
     * which is a guess, and a guess that drifts between a full-size board and
     * a tenkeyless. Computed once at construction.
     */
    public double keyWidthNormalised() {
        return keyWidth;
    }

    /**
     * Median nearest-neighbour distance, which is one key width. Two
     * deliberate decisions are baked in here:
     *
     * <p><b>Median, not mean.</b> Plenty of boards report lighting-pipe LEDs
     * alongside the actual keys, packed far closer together than any two keys
     * are. A mean gets dragged straight down by those outliers. A median
     * ignores them completely.
     *
     * <p><b>Sampled, not exhaustive.</b> Roughly 40 sample points compared
     * against every LED, rather than a full O(n^2) pass over 109 or more of
     * them. This runs once at startup, and 40 samples is plenty to find the
     * median of a distribution this strongly clustered.
     */
    private static double computeKeyWidth(List<LedPosition> leds) {
        if (leds.size() < 2) return 0.05; // one LED has no neighbours, so 0.05 is an invented but sane default
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
     * keyboard. Returns null when that character isn't on any connected board,
     * and callers have to handle that rather than silently lighting LED 0.
     *
     * <p><b>Only the first character is used.</b> The label gets trimmed,
     * uppercased, and then {@code charAt(0)} is the whole of the lookup. So
     * {@code "H"} works, {@code "h"} works, and {@code "F5"} quietly resolves
     * to the F key rather than to F5 or to null. Multi-character key names are
     * not supported, because the named-key table the backends hand over is
     * keyed by single characters (A-Z and 0-9) and nothing else.
     */
    // Note the deliberate absence of a fallback. Returning null forces the
    // caller to actually make a decision: EffectRegistry.resolveKey warns and
    // falls back to the whole board, which is loud and impossible to miss. The
    // old behaviour of quietly returning LED 0 was neither of those things.
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
         * Normalises the pending device into its own 0..1 space and closes it
         * out.
         *
         * <p>That min/max pass is the thing that makes all of this
         * layout-agnostic. Whatever origin and whatever units the device
         * decided to report in, its LEDs come out the other side spanning
         * 0..1. spanX and spanY are kept as the raw dimensions of the LED
         * bounding box, which is where {@link KeyGrid#aspectRatio()} gets its
         * numbers from.
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
