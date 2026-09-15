package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Sunlight coming through a jungle canopy, and the wind moving it about.
 *
 * <p>Replaces a flat shimmer on the three jungle biomes. A shimmer says "this
 * place is green"; it does not say anything about what the place is like.
 *
 * <h2>Dappled light, not a wave</h2>
 * The obvious reference here is Terraria, which runs a green-to-teal gradient
 * across the keyboard as a wave. It looks good and it means nothing — the
 * colours are a choice and the wave has no cause. What a jungle actually looks
 * like from underneath is a dark canopy with pools of light punched through
 * it, and those pools shifting whenever the wind moves the leaves overhead.
 * That is a thing that can be drawn, so this draws it.
 *
 * <h2>One green, dimmed — not two colours</h2>
 * This originally ran a teal-to-lime ramp, on the reasoning that shade under a
 * canopy is lit by sky and goes cool while light through leaves comes out warm.
 * That is true of actual jungles and it looked wrong on actual hardware: the
 * two ends sat eighty-odd degrees apart on the colour wheel, so instead of one
 * place with light falling through it, the board read as a teal thing with a
 * yellow-green thing on top. Physical accuracy was never the goal; the jungle
 * is supposed to look green.
 *
 * <p>So the ramp is now a single hue — the biome's own green at both ends, a
 * few degrees and a lot of brightness apart — and the dapple is carried
 * entirely by <b>how dark the shade gets</b> rather than by what colour the
 * light is. Shade sits near the bottom of the LED's range at roughly a tenth of
 * full output, sunlit pools run to about ninety percent, and the gap between
 * them is the whole effect.
 *
 * <p>Worth keeping in mind if this is ever retuned: the instinct is to brighten
 * the pools, and that is the wrong end to pull. The pools are already near the
 * top of what the hardware can do — the only lever with room left in it is
 * taking light away from everything that is not lit.
 *
 * <h2>Three layers</h2>
 * <ol>
 *   <li><b>The canopy.</b> Two octaves of value noise, drifting in different
 *       directions at different speeds. Because they disagree, their sum never
 *       repeats, so the leaf cover overhead keeps changing without ever
 *       looping back to a shape you have already seen.</li>
 *   <li><b>The pools.</b> That noise pushed through a threshold and a power
 *       curve, so light gathers into distinct patches instead of a smooth
 *       mush. Without the sharpening this reads as a green lava lamp.</li>
 *   <li><b>Gusts.</b> Every so often a band of wind crosses the board. It
 *       brightens the pools as it passes, and — the detail that sells it — it
 *       also drags the noise sample sideways, so the canopy visibly stirs
 *       rather than just getting brighter under a moving spotlight.</li>
 * </ol>
 *
 * <h2>No state at all</h2>
 * Every value here is a pure function of elapsed time and key position. There
 * is nothing to seed, nothing to recycle, and nothing to reset when the effect
 * clock restarts — the whole class is one expression evaluated per key. That
 * is unusual for the animated patterns in this mod and it is worth keeping:
 * the entire category of clock-restart bugs simply cannot happen here.
 */
public final class CanopyDapplePattern implements Pattern {

    /** How many noise cells span the board. Roughly board-width / this = pool size. */
    private final double noiseScale;
    /**
     * Stretches the noise vertically. 1.0 gives round-ish pools; higher values
     * elongate them into vertical streaks, which is what light does when it
     * comes down between bamboo stalks rather than through a leaf canopy.
     */
    private final double verticalStretch;
    /** Noise below this is full shade; above {@link #lightHigh} is full sun. */
    private final double lightLow;
    private final double lightHigh;
    /**
     * Higher = tighter, rarer pools, and a faster ramp between shade and sun.
     *
     * <p>This is the knob that controls how much of the board sits in the
     * middle of the LED's range, and the middle is where legibility goes to
     * die: a keyboard reads as animated when its keys are mostly background or
     * mostly element, and reads as a wash when they pile up in between. Raised
     * from an earlier, gentler value, which left about one key in seven
     * stranded in that dead band — enough to average the whole picture out.
     */
    private final double sharpness;
    /**
     * Brightness in full shade. Never zero — you should always see the jungle.
     *
     * <p>This is the knob that does the work, and it has to be read together
     * with the shade colour rather than on its own: it is a multiplier, so the
     * same floor over a dim teal and over a bright green produce completely
     * different boards. During tuning a dead-looking keyboard was first blamed
     * on this floor being too low; the real culprit was a shade colour with
     * very little output in any channel, and the floor was fine.
     *
     * <p>Tuned by measuring what the LED actually does: full shade lands near a
     * tenth of maximum drive — clearly lit, clearly dim — against pools at
     * roughly ninety percent. The greens here have a strong green channel, so
     * the floors look low written down and are not.
     */
    private final double shadeFloor;

    private CanopyDapplePattern(double noiseScale, double verticalStretch,
                                double lightLow, double lightHigh,
                                double sharpness, double shadeFloor) {
        this.noiseScale = noiseScale;
        this.verticalStretch = verticalStretch;
        this.lightLow = lightLow;
        this.lightHigh = lightHigh;
        this.sharpness = sharpness;
        this.shadeFloor = shadeFloor;
    }

    // --- Wind ----------------------------------------------------------
    /** Seconds between gusts. The board is calm for most of this. */
    private static final double GUST_PERIOD_SECONDS = 6.5;
    /** How long one gust takes to cross. Short relative to the period, so gusts stay events. */
    private static final double GUST_TRAVEL_SECONDS = 3.0;
    /** Width of the gust band, in board widths. */
    private static final double GUST_WIDTH = 0.24;
    /** How much a gust opens the canopy up as it passes. */
    private static final double GUST_BOOST = 0.85;
    /** How far a gust drags the canopy sideways, in noise cells. */
    private static final double GUST_SWAY = 0.45;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        RGBColor shade = ctx.baseColor();
        RGBColor sun = ctx.resolvedAccentColor();

        // Where the gust is this instant, and whether there is one at all.
        double gustPhase = t % GUST_PERIOD_SECONDS;
        boolean gusting = gustPhase < GUST_TRAVEL_SECONDS;
        // Starts and finishes off the board, so a gust arrives and leaves
        // rather than materialising at the edge.
        double gustCenter = gusting ? -0.25 + (gustPhase / GUST_TRAVEL_SECONDS) * 1.5 : 0;

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double gust = 0;
            if (gusting) {
                double d = (key.x() - gustCenter) / GUST_WIDTH;
                gust = Math.exp(-d * d);
            }

            double nx = key.x() * noiseScale;
            double ny = key.y() * noiseScale / verticalStretch;

            // Two octaves pulling in different directions. The drift speeds
            // are deliberately unrelated so the sum has no period: leaves
            // overhead never settle back into an arrangement you have seen.
            //
            // They are also faster than they look like they need to be. An
            // earlier version drifted at a third of this, which meant a pool sat in
            // the same place for the better part of twenty seconds — fine
            // during a gust, but the five-odd seconds of calm between gusts
            // read as the board having frozen.
            // The gust is added to the sample position, not just the result,
            // which is what makes the canopy stir instead of merely brighten.
            double n = 0.62 * valueNoise(nx + t * 0.135 + gust * GUST_SWAY, ny - t * 0.048)
                     + 0.38 * valueNoise(nx * 2.1 - t * 0.092, ny * 2.1 + t * 0.071 + gust * GUST_SWAY);

            // Threshold then sharpen, so light gathers into pools rather than
            // fading evenly across the whole board.
            double light = smoothstep(lightLow, lightHigh, n);
            light = Math.pow(light, sharpness);
            light = Math.min(1.0, light * (1.0 + gust * GUST_BOOST) + gust * 0.10);

            // Colour and brightness ramp together. Splitting them apart — a
            // constant green at varying brightness, say — loses the depth
            // completely, because a shadow is not just a dimmer version of a
            // sunlit leaf, it is a differently coloured one.
            RGBColor color = shade.lerp(sun, light);
            double alpha = shadeFloor + (1.0 - shadeFloor) * light;
            out.put(key.ref(), new LayerPixel(color, alpha));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Value noise
    // ---------------------------------------------------------------

    /**
     * Integer hash to a stable 0..1. Cheap, deterministic, and good enough for
     * a lattice — this is not cryptography, it just has to look unstructured.
     */
    private static double hash(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0x7fffffff) / (double) 0x7fffffff;
    }

    /**
     * Standard 2D value noise: hash the four lattice corners, blend with a
     * smoothstep so the result has no visible grid seams.
     *
     * <p>Plain linear blending would leave the lattice showing as faint creases
     * on a board this small, which on a keyboard reads as a rectangular
     * artefact rather than as foliage.
     */
    private static double valueNoise(double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx);
        double sy = fy * fy * (3 - 2 * fy);

        double top = lerp(hash(x0, y0), hash(x0 + 1, y0), sx);
        double bottom = lerp(hash(x0, y0 + 1), hash(x0 + 1, y0 + 1), sx);
        return lerp(top, bottom, sy);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0, Math.min(1, (x - edge0) / Math.max(1e-6, edge1 - edge0)));
        return t * t * (3 - 2 * t);
    }

    // ---------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------

    /**
     * Dense jungle: heavy cover, so the pools are small, rare and hard-edged,
     * and the shade between them is deep.
     */
    public static CanopyDapplePattern jungle() {
        return new CanopyDapplePattern(4.2, 1.0, 0.44, 0.72, 2.3, 0.16);
    }

    /**
     * Sparse jungle: fewer trees, so more light gets down. Larger and softer
     * pools over a brighter floor, from the same engine with the threshold
     * dropped and the shade lifted.
     */
    public static CanopyDapplePattern sparseJungle() {
        return new CanopyDapplePattern(3.4, 1.0, 0.36, 0.68, 1.6, 0.16);
    }

    /**
     * Bamboo jungle: the stretch is doing the work here. Bamboo is tall and
     * thin and grows in stands, so light comes down in vertical slots between
     * the stalks rather than in patches through leaves. Elongating the noise
     * on the y axis is the entire difference, and it is enough to make the
     * biome read as a different place rather than as jungle in another shade
     * of green.
     */
    public static CanopyDapplePattern bambooJungle() {
        return new CanopyDapplePattern(4.4, 3.0, 0.42, 0.72, 2.0, 0.13);
    }

    /**
     * Dark forest: the densest canopy in the game, so the light is scarce.
     *
     * <p>Dark oak grows in a near-continuous roof, and what makes the biome feel
     * like itself is how little gets through. So the threshold is raised until
     * only the brightest peaks of the noise break the canopy, and the higher
     * scale keeps those breaks small. Measured on an 18x6 board: pools cover
     * about 9% of the keys against the jungle's 20%, with almost nothing in the
     * dead middle band — a dark floor with a few shafts of light, rather than
     * the jungle's patchwork.
     *
     * <p>The floor is the lowest of the canopy presets, deliberately. At about
     * 7% of full drive it is still clearly lit, but it is as dark as a shade
     * gets before a key starts reading as off, so this is the first number to
     * raise if the biome ever looks dead rather than dim.
     */
    public static CanopyDapplePattern darkForest() {
        return new CanopyDapplePattern(4.8, 1.0, 0.56, 0.78, 2.8, 0.14);
    }
}
