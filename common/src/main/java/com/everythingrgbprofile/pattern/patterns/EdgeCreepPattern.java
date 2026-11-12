package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Something creeping in from the edges of the board, further the worse it
 * gets, for Tough As Nails' freezing and overheating. The keyboard version of
 * the screen frosting over from the corners, or the edges of your vision going
 * red while you cook.
 *
 * <p>Same family as {@link WaterRisePattern} and {@link FlameRisePattern}, one
 * level in and the board fills accordingly, except this one fills from all
 * four sides at once instead of from the bottom. Level 0 is nothing, a small
 * level is just the outermost ring of keys, and level 1 closes all the way
 * over the middle. The biome shows through whatever's left uncovered, so how
 * much of it you can still see IS the meter.
 *
 * <h2>Depth is measured from the border keys, not from a box</h2>
 * Every key's depth is how far it physically sits from the nearest key on the
 * outline of the board, in board heights. A key is on the outline if looking
 * left, right, up or down from it finds no other key (stray media buttons
 * don't count as a row, see {@code FULL_ROW_FRACTION}). So the whole border sits
 * at depth zero and lights up together, and everything else follows it in,
 * ring by ring, at the same physical speed from every side. The last thing to
 * go is a strip through the middle rows, which is where a vignette on a wide
 * board ends up.
 *
 * <p>An earlier version measured from the edges of the LED bounding box
 * instead. Perfectly sound on a rectangle, and a keyboard is a rectangle only
 * from across the room. The box touches whatever sticks out furthest on each
 * side and nothing else: the bottom row and the numpad's right-hand column sat
 * at zero, while the staggered left column (Tab, Caps, Shift, each a bit
 * further in than the last) and any function row with media keys above it sat
 * up to a fifth of a board height deeper. So the "ring" turned up one side at
 * a time, bottom row first, and read as a spiral winding in. Nobody ordered a
 * spiral.
 *
 * <p>Raw device units throughout, the same ones on both axes, so there's no
 * aspect ratio to remember here. The heat ripple still needs one, see below.
 *
 * <h2>Two personalities, one engine</h2>
 * The geometry is identical and the temperament is opposite.
 *
 * <p><b>Frost</b> grows and then holds still. Its front is jagged, every key
 * getting its own fixed nudge so ice reaches out in uneven fingers, and none
 * of that moves with time, because frost on a window doesn't wiggle. Advancing
 * means more keys freezing over one at a time. It's translucent at the front
 * (pale blue over anything reads as frosted) and the occasional key glints.
 *
 * <p><b>Heat</b> never holds still. Its front ripples around the ring like air
 * over a road, and the whole thing throbs, slowly at first and faster as the
 * heatstroke builds. It's drawn opaque, for the same reason the flames are:
 * orange blended into a green biome comes out olive, which reads as nothing.
 * So the front softens by dimming instead of going see-through.
 *
 * <h2>Why the level is eased here</h2>
 * The poll only gets round to TAN about once a second, so the level arrives
 * in steps. Easing toward it in the render loop turns those steps into a
 * creep, which is the whole point. Retreating is slower than advancing, so
 * warming up or cooling down visibly thaws rather than snapping back.
 */
public final class EdgeCreepPattern implements Pattern {

    public enum Kind { FROST, HEAT }

    /** At level 1, how far in the front reaches, in board heights. Past 0.5 so the jagged front still closes over the middle. */
    private static final double FULL_REACH = 0.58;

    private static final double RISE_TAU_SECONDS = 0.9;
    private static final double FALL_TAU_SECONDS = 1.6;

    /** How far behind the front a key has to be before it counts as fully covered, in board heights. */
    private static final double FRONT_BAND = 0.14;

    /** How ragged the frost line is, in board heights, either side of the true front. */
    private static final double FROST_JAG = 0.07;
    /** Coverage right at the frost line. Inside it climbs to fully opaque. */
    private static final double FROST_FRONT_ALPHA = 0.35;

    /** How far the heat line ripples either side of the true front, in board heights. */
    private static final double HEAT_RIPPLE = 0.05;
    /** Brightness right at the heat line, so it fades out by dimming rather than going see-through. */
    private static final double HEAT_FRONT_BRIGHTNESS = 0.45;
    /** Throb rate at the first sign of overheating and at full heatstroke, in Hz. */
    private static final double THROB_SLOW_HZ = 0.45;
    private static final double THROB_FAST_HZ = 1.6;

    /**
     * How far off its own row another key can be and still count as being in
     * it, in key widths. Over half, because the double-height numpad keys
     * report their LED halfway between two rows. Doubles as the sideways slack
     * for "straight above or below".
     */
    private static final double ROW_BAND_KEYS = 0.6;
    /**
     * A row only counts as a roof or a floor for the keys under or over it if
     * it has at least this fraction of the fullest row's key count. A handful
     * of media buttons floating above the function row do not make the
     * function row an inside row, and without this they would.
     */
    private static final double FULL_ROW_FRACTION = 0.5;

    private final Kind kind;

    // Depth of every key for one particular grid. Rebuilt when the grid
    // object changes, which is when a device comes or goes. Render thread only.
    private KeyGrid depthGrid;
    private Map<KeyGrid.LedRef, Double> depths = Map.of();

    private volatile double targetLevel = 0;
    private double displayedLevel = 0;
    private double lastElapsedMillis = 0;
    private double throbPhase = 0;
    private boolean initialised = false;

    private EdgeCreepPattern(Kind kind) {
        this.kind = kind;
    }

    public static EdgeCreepPattern frost() {
        return new EdgeCreepPattern(Kind.FROST);
    }

    public static EdgeCreepPattern heat() {
        return new EdgeCreepPattern(Kind.HEAT);
    }

    /** 0 = nothing, 1 = closed over the whole board. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /** Gone at once, no thawing: for a world going away, when there's nothing to thaw into. */
    public void clear() {
        this.targetLevel = 0;
        this.initialised = false;
    }

    /** Whether there's still anything to draw, so the edge can retreat after the danger's over. */
    public boolean visible() {
        return targetLevel > 0.001 || (initialised && displayedLevel > 0.005);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            // Snapped to nothing rather than to the target, so it creeps in
            // from the edge instead of appearing already halfway across.
            displayedLevel = 0;
            lastElapsedMillis = elapsedMillis;
            throbPhase = 0;
            initialised = true;
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        if (dt > 0) {
            double tau = targetLevel > displayedLevel ? RISE_TAU_SECONDS : FALL_TAU_SECONDS;
            displayedLevel += (targetLevel - displayedLevel) * (1 - Math.exp(-dt / tau));
        }
        double level = displayedLevel;
        if (level <= 0.005) return Map.of();

        double seconds = elapsedMillis / 1000.0;
        double aspect = ctx.grid().aspectRatio();
        if (aspect <= 0) aspect = 1.0;
        double reach = level * FULL_REACH;

        // Accumulated rather than computed as rate * time. The rate changes
        // with the level, and sin(2pi * rate * t) with a moving rate leaps
        // around the cycle every time the rate moves, which reads as a stutter.
        if (kind == Kind.HEAT) {
            double hz = THROB_SLOW_HZ + (THROB_FAST_HZ - THROB_SLOW_HZ) * level;
            throbPhase = (throbPhase + hz * dt) % 1.0;
        }

        if (depthGrid != ctx.grid()) {
            depthGrid = ctx.grid();
            depths = borderDepths(depthGrid);
        }

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double x = key.x();
            double y = key.y();
            double edge = depths.getOrDefault(key.ref(), 0.0);

            LayerPixel px = kind == Kind.FROST
                    ? frost(ctx, key, x, y, edge, reach, seconds)
                    : heat(ctx, x, y, edge, reach, level, seconds, aspect);
            if (px != null) out.put(key.ref(), px);
        }
        return out;
    }

    /**
     * Ice blue at the front, nearly white at the rim where it's been frozen
     * longest. Base colour is the front, accent is the rim.
     */
    private LayerPixel frost(PatternContext ctx, KeyGrid.LedPosition key, double x, double y,
                             double edge, double reach, double seconds) {
        // Mostly per key so neighbours disagree, partly a slow spatial wave so
        // the disagreement clumps into fingers instead of salt and pepper.
        // Neither term has time in it. Frost doesn't move once it's there.
        double jag = 0.6 * (hash(key.ref(), 0) * 2 - 1)
                + 0.4 * Math.sin(2 * Math.PI * (x * 5.3 + y * 2.1));
        double depth = reach + FROST_JAG * jag - edge;
        if (depth < 0) return null;

        double cover = smooth(depth / FRONT_BAND);
        // 1 at the very edge of the board, 0 at the front: how long it's been frozen.
        double age = reach <= 0 ? 0 : Math.max(0, Math.min(1, 1 - edge / reach));
        RGBColor color = ctx.baseColor().lerp(ctx.resolvedAccentColor(), age);

        // Glints. Each key gets its own slow clock and catches the light for a
        // sliver of each turn, the pow() being what turns a sine into a sliver.
        // Only on keys properly frozen over, never on the thin front.
        double rate = 0.12 + 0.2 * hash(key.ref(), 1);
        double glint = Math.pow(Math.max(0, Math.sin(2 * Math.PI * (seconds * rate + hash(key.ref(), 2)))), 40);
        if (glint > 0.01 && cover > 0.8) color = color.lerp(RGBColor.WHITE, glint * 0.8);

        double alpha = FROST_FRONT_ALPHA + (1 - FROST_FRONT_ALPHA) * cover;
        return new LayerPixel(color, alpha);
    }

    /**
     * Orange at the front, deep red at the rim. Base colour is the front,
     * accent is the rim. Opaque throughout; see the class notes.
     */
    private LayerPixel heat(PatternContext ctx, double x, double y, double edge,
                            double reach, double level, double seconds, double aspect) {
        // Position around the ring, 0..1, so the ripple travels round it
        // rather than sloshing left to right. Angle from the centre with the
        // same aspect fix as the distance, or the corners would bunch up.
        double around = (Math.atan2(y - 0.5, (x - 0.5) / aspect) / (2 * Math.PI)) + 0.5;
        double ripple = 0.6 * Math.sin(2 * Math.PI * (around * 5 + seconds * 0.5))
                + 0.4 * Math.sin(2 * Math.PI * (around * 11 - seconds * 1.1) + 1.0);
        double depth = reach + HEAT_RIPPLE * ripple - edge;
        if (depth < 0) return null;

        double cover = smooth(depth / FRONT_BAND);
        double age = reach <= 0 ? 0 : Math.max(0, Math.min(1, 1 - edge / reach));
        RGBColor color = ctx.baseColor().lerp(ctx.resolvedAccentColor(), age);

        // The throb gets deeper as well as faster, so a mild overheat is a
        // slow warm pulse and full heatstroke is a pounding one.
        double throbDepth = 0.15 + 0.25 * level;
        double throb = 1 - throbDepth * (0.5 + 0.5 * Math.sin(2 * Math.PI * throbPhase));
        double brightness = (HEAT_FRONT_BRIGHTNESS + (1 - HEAT_FRONT_BRIGHTNESS) * cover) * throb;
        return LayerPixel.opaque(color.scaled(brightness));
    }

    /**
     * Every key's distance from the nearest border key, in board heights. See
     * the class notes for why it's done this way round.
     *
     * <p>All of it is n squared over a hundred-odd keys, once per grid, so
     * nobody needs to be clever about it.
     */
    private static Map<KeyGrid.LedRef, Double> borderDepths(KeyGrid grid) {
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        int n = keys.size();
        if (n == 0) return Map.of();

        // One key width in raw units: the median gap to the nearest neighbour.
        // Median for the same reason KeyGrid uses one, light-pipe LEDs packed
        // tighter than any two keys would drag a mean down.
        double[] nearest = new double[n];
        for (int i = 0; i < n; i++) {
            double best = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                best = Math.min(best, Math.hypot(keys.get(j).rawX() - keys.get(i).rawX(),
                        keys.get(j).rawY() - keys.get(i).rawY()));
            }
            nearest[i] = best;
        }
        double[] sorted = nearest.clone();
        Arrays.sort(sorted);
        double pitch = sorted[n / 2];
        // One LED, or every LED stacked on the same spot. There is no inside
        // to creep toward, so the whole thing is border.
        if (n < 2 || pitch <= 0 || pitch == Double.MAX_VALUE) {
            Map<KeyGrid.LedRef, Double> flat = new HashMap<>();
            for (KeyGrid.LedPosition key : keys) flat.put(key.ref(), 0.0);
            return flat;
        }

        // How many keys share each key's row, and what a full row looks like.
        double band = ROW_BAND_KEYS * pitch;
        int[] rowSize = new int[n];
        int fullest = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (Math.abs(keys.get(j).rawY() - keys.get(i).rawY()) <= band) rowSize[i]++;
            }
            fullest = Math.max(fullest, rowSize[i]);
        }
        double fullRow = fullest * FULL_ROW_FRACTION;

        boolean[] border = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean left = false, right = false;
            // Above and below each want covering from both sides, by one key
            // sitting straight over it or by two flanking it. Flanking is what
            // handles rows that don't line up: the 1 key sits a full key from
            // both Esc and F1, and C has nothing under it but the gap between
            // Alt and the single LED in the middle of the space bar.
            boolean upL = false, upR = false, downL = false, downR = false;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double dx = keys.get(j).rawX() - keys.get(i).rawX();
                double dy = keys.get(j).rawY() - keys.get(i).rawY();
                if (Math.abs(dy) <= band) {
                    if (dx < -0.5 * pitch) left = true;
                    if (dx > 0.5 * pitch) right = true;
                }
                if (rowSize[j] < fullRow) continue;
                if (dy < -0.5 * pitch) {
                    if (dx <= band) upL = true;
                    if (dx >= -band) upR = true;
                }
                if (dy > 0.5 * pitch) {
                    if (dx <= band) downL = true;
                    if (dx >= -band) downR = true;
                }
            }
            border[i] = !(left && right && upL && upR && downL && downR);
        }

        // A single-row device has a height of basically nothing, and dividing
        // by basically nothing is how you get depths in the millions. Every
        // key on one of those is border anyway, so the floor only has to stop
        // the division being silly.
        KeyGrid.Surface surface = grid.primarySurface();
        double boardHeight = Math.max(surface != null ? surface.heightRaw() : 0, pitch);

        Map<KeyGrid.LedRef, Double> out = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double best = Double.MAX_VALUE;
            if (border[i]) {
                best = 0;
            } else {
                for (int j = 0; j < n; j++) {
                    if (!border[j]) continue;
                    best = Math.min(best, Math.hypot(keys.get(j).rawX() - keys.get(i).rawX(),
                            keys.get(j).rawY() - keys.get(i).rawY()));
                }
            }
            out.put(keys.get(i).ref(), best == Double.MAX_VALUE ? 0.0 : best / boardHeight);
        }
        return out;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Stable 0..1 per key, with a salt so one key can have several unrelated values. */
    private static double hash(KeyGrid.LedRef ref, int salt) {
        long h = (ref.luid() * 2654435761L) ^ (ref.deviceId() == null ? 0 : ref.deviceId().hashCode()) ^ (salt * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 15);
        h *= 0x2C1B3C6DL;
        h ^= (h >>> 12);
        return (h & 0xFFFF) / 65536.0;
    }
}
