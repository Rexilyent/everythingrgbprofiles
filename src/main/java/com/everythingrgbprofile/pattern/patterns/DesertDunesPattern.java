package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * Desert: dune ridges under a moving sheet of wind-blown sand.
 *
 * <p>Replaces a bare {@code sand-drift}, which put six particles on a hundred
 * and nine keys and left the rest of the board dark. Six moving dots is not a
 * desert, it is six moving dots — a desert is a surface, and a surface has to
 * cover the board before anything moving across it means anything.
 *
 * <h2>Where this differs from the obvious reference</h2>
 * Terraria's underground desert is a dense amber field, and dense is the part
 * worth taking: whatever else it does, the whole strip is alive. What it does
 * not have is structure, so this adds some. Three things are happening here
 * that a noise field alone would not give you:
 *
 * <ol>
 *   <li><b>Dunes.</b> The large noise octave is stretched wide and flat,
 *       because dunes are ridges rather than blobs. It drifts slowly downwind,
 *       so crests migrate across the board the way real ones do.</li>
 *   <li><b>Grain.</b> A second, much finer octave moving faster. This is what
 *       stops the dunes reading as soft lighting and starts them reading as a
 *       material made of particles.</li>
 *   <li><b>Gusts.</b> Streaks of lifted sand that skim across, low and fast,
 *       well brighter than anything underneath them. These are the events; the
 *       dunes are the place they happen in.</li>
 * </ol>
 *
 * <h2>Heat shimmer</h2>
 * The whole field is sampled through a slow vertical ripple whose phase varies
 * along the board, so it wobbles rather than sliding. It is deliberately almost
 * too subtle to point at — the desert is the only biome in the mod where the
 * air itself is visibly doing something, and overdoing it turns sand into
 * water immediately.
 *
 * <h2>Why the sand is not sand-coloured</h2>
 * Minecraft's sand samples at {@code #DBCFA3}, which is 74% white light. On a
 * monitor that is sand; on an LED it is a white key with an opinion. So the
 * profile drives a saturated amber instead — the same hue family sand actually
 * occupies, with the white taken out. It is the same correction the jungle
 * needed, and the one that tuning colours by eye on the real keyboard kept
 * arriving at independently (see {@code ColorPalette} for why).
 *
 * <h2>A rule this one deliberately does not follow</h2>
 * Elsewhere in this mod the goal is to keep the middle of the LED's range
 * empty, so a moving element reads against a dark background. That is the right
 * rule when something is <i>on</i> a background. Here the sand <b>is</b> the
 * subject, and a desert with most of its keys dark would be the barrenness this
 * class exists to fix. So the field deliberately occupies the low-to-middle
 * range across most of the board, and only the gusts go near the top.
 */
public final class DesertDunesPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** Dune octave: how many cells across the board, and how flat they are. */
    private final double duneScale;
    private final double duneFlatten;
    /** Grain octave, relative to the dune octave. */
    private final double grainScale;
    private final double grainWeight;
    /** Brightness of the darkest trough. Never zero: bare sand is not a hole. */
    private final double troughFloor;
    /** How hard the crests are picked out of the field. */
    private final double crestSharpness;
    private final int gustCount;

    private DesertDunesPattern(double duneScale, double duneFlatten, double grainScale,
                               double grainWeight, double troughFloor,
                               double crestSharpness, int gustCount) {
        this.duneScale = duneScale;
        this.duneFlatten = duneFlatten;
        this.grainScale = grainScale;
        this.grainWeight = grainWeight;
        this.troughFloor = troughFloor;
        this.crestSharpness = crestSharpness;
        this.gustCount = gustCount;
    }

    /** Downwind drift, in noise cells per second. */
    private static final double DUNE_DRIFT = 0.055;
    private static final double GRAIN_DRIFT = 0.30;

    /** Heat shimmer: how far the sample ripples, and how fast. */
    private static final double SHIMMER_AMOUNT = 0.055;
    private static final double SHIMMER_HZ = 0.28;

    /** Gust geometry, in key widths, and its timing. */
    private static final double GUST_RADIUS_KEYS = 1.1;
    private static final int GUST_TRAIL = 5;
    private static final double GUST_TRAIL_STEP = 0.028;
    private static final double GUST_PERIOD_SECONDS = 5.2;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        RGBColor trough = ctx.baseColor();
        RGBColor crest = ctx.resolvedAccentColor();

        LightBudget budget = new LightBudget();

        // --- the sand itself --------------------------------------------
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            // Shimmer displaces where we sample, not what comes back, so the
            // dunes appear to ripple rather than to brighten and dim in place.
            double shimmer = SHIMMER_AMOUNT
                    * Math.sin(TAU * SHIMMER_HZ * t + key.x() * 7.3);

            double dx = key.x() * duneScale + t * DUNE_DRIFT;
            double dy = (key.y() + shimmer) * duneScale / duneFlatten;
            double dune = valueNoise(dx, dy);

            double gx = key.x() * duneScale * grainScale + t * GRAIN_DRIFT;
            double gy = (key.y() + shimmer * 0.5) * duneScale * grainScale;
            double grain = valueNoise(gx, gy);

            double field = (1.0 - grainWeight) * dune + grainWeight * grain;
            // Gentle sharpening only. The jungle wants distinct pools with
            // nothing between them; sand wants a continuous surface, and
            // pushing this any harder turns dunes into leopard print.
            double lit = Math.pow(clamp01(field), crestSharpness);

            RGBColor color = trough.lerp(crest, lit);
            budget.add(key.ref(), color, troughFloor + (1.0 - troughFloor) * lit);
        }

        // --- gusts of lifted sand ---------------------------------------
        double gustRadius = GUST_RADIUS_KEYS * keyWidth;
        for (int i = 0; i < gustCount; i++) {
            double phase = hash(i * 19 + 5);
            double period = GUST_PERIOD_SECONDS / (0.8 + 0.5 * hash(i * 23 + 1));
            double progress = frac(t / period + phase);
            // Lanes are stratified rather than hashed. With four gusts a plain
            // roll leaves whole bands of the board that never see one, which is
            // the same clumping the enchanted tangle's motes ran into.
            double lane = (i + 0.2 + 0.6 * hash(i * 29 + 7)) / gustCount;
            // Sand lifts as it travels, so a gust rises slightly across its run.
            double y = lane - 0.10 * progress;
            double x = -0.15 + progress * 1.30;

            double envelope = progress < 0.10
                    ? progress / 0.10
                    : Math.min(1.0, (1.0 - progress) / 0.30);
            if (envelope <= 0.02) continue;

            for (int tail = 0; tail < GUST_TRAIL; tail++) {
                // The trail lags upwind, which is behind in x.
                double tx = x - tail * GUST_TRAIL_STEP;
                double strength = envelope * (1.0 - tail / (double) GUST_TRAIL);

                // Nearest key lit outright, then a halo. A gust is thinner than
                // the gap between LEDs, and leaving it to distance falloff
                // alone makes its brightness depend on where it happens to sit
                // between keys — see the enchanted tangle for how dim that gets.
                KeyGrid.LedPosition core = nearestKey(grid, tx, y);
                if (core != null) budget.add(core.ref(), crest, strength);

                for (KeyGrid.LedPosition key : grid.allKeys()) {
                    double d = Math.hypot(key.x() - tx, (key.y() - y) * aspect);
                    if (d >= gustRadius) continue;
                    double falloff = 1.0 - d / gustRadius;
                    budget.add(key.ref(), crest, falloff * falloff * strength * 0.55);
                }
            }
        }

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    /** Same lattice hash the canopy uses; see CanopyDapplePattern for the notes. */
    private static double latticeHash(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0x7fffffff) / (double) 0x7fffffff;
    }

    private static double valueNoise(double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx);
        double sy = fy * fy * (3 - 2 * fy);
        double top = lerp(latticeHash(x0, y0), latticeHash(x0 + 1, y0), sx);
        double bottom = lerp(latticeHash(x0, y0 + 1), latticeHash(x0 + 1, y0 + 1), sx);
        return lerp(top, bottom, sy);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double nx, double ny) {
        KeyGrid.LedPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - nx, key.y() - ny);
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        return best;
    }

    /**
     * Open desert.
     *
     * <p>Dunes stretched almost three to one, because a ridge that reads as
     * round reads as a hill. Four gusts is the tuned count: fewer and the board
     * is a still photograph of a desert, more and the sand stops looking like
     * ground and starts looking like weather.
     */
    public static DesertDunesPattern desert() {
        return new DesertDunesPattern(3.6, 2.8, 3.1, 0.34, 0.16, 1.35, 4);
    }
}
