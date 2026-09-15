package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * Sunflower plains: heads standing over grass, with the wind running through
 * them.
 *
 * <p>Replaces a flat shimmer, which gave the biome a yellow wash and no
 * flowers in it.
 *
 * <h2>What moves is the wave, not the flowers</h2>
 * This is the one thing that makes the pattern work, and it is the opposite of
 * how every other animated biome in the mod is built. The particle engines move
 * objects across the board; the noise engines move a field past the keys. A
 * crop does neither. The plants are rooted — nothing travels — and what crosses
 * the field is the <i>phase</i> of their movement, each row bending a moment
 * after the row upwind of it. So the heads here have fixed positions for the
 * life of the pattern, and the only thing with a velocity is a number.
 *
 * <p>That is also why this cannot be built out of {@code twinkle-particle} with
 * a yellow palette, which was the obvious cheap version. Twinkles fire
 * independently, and independent is exactly wrong: a field reads as a field
 * because everything in it is doing the same thing slightly out of step, and
 * the moment the heads stop agreeing with their neighbours the board goes back
 * to being scattered lights.
 *
 * <h2>They all face the same way, which is a gift</h2>
 * Every sunflower Minecraft places faces east. It is a detail nobody asks
 * about, and it means a whole field turns its face toward or away from a given
 * direction together — so a gust passing through produces one coherent sweep of
 * light rather than a hundred unrelated glints. The heads dim as they bend
 * away and come back up as they recover, all in the same order the wind
 * arrives in.
 *
 * <h2>Four layers</h2>
 * <ol>
 *   <li><b>Grass.</b> A dim green wash over the whole board, textured with
 *       noise so it is not a flat fill, and rippling on the same travelling
 *       wave as the heads.</li>
 *   <li><b>Heads.</b> Fixed bright discs, well clear of the grass in
 *       brightness. These are the subject and they get most of the range.</li>
 *   <li><b>Bend.</b> The wave. Each head leans downwind and dims as it turns
 *       its face away, on a curve weighted toward upright so a pass reads as
 *       something arriving rather than as a dimmer being wound up and down.</li>
 *   <li><b>Gusts.</b> A travelling band that deepens the bend where it is,
 *       so the field gets the occasional stronger ripple crossing it instead of
 *       breathing at one constant rate forever.</li>
 * </ol>
 *
 * <h2>Base is the grass, accent is the flower</h2>
 * The same way round as the desert, the lush caves and the enchanted tangle:
 * the base colour is the field and the accent is the bright thing standing in
 * it. Worth stating because the biome's own identity is obviously yellow and an
 * earlier profile spelled it that way — but the base is what covers the board
 * and what {@code BiomeColorEffect} crossfades through, and a yellow base puts
 * every unlit key in the field the colour of a petal.
 *
 * <h2>No state, and no nearest-key trick</h2>
 * Everything here is a pure function of elapsed time and key position, so there
 * is nothing to seed and nothing to reset when the effect clock restarts, and
 * the field is laid out identically every time you walk into the biome — which
 * is what you would expect of a field.
 *
 * <p>Unlike the desert gusts, the heads do not light their nearest key outright
 * before applying a halo. That trick exists for elements thinner than the gap
 * between LEDs, where distance falloff alone leaves brightness depending on
 * where the element happens to sit between keys. A head is wider than that gap
 * on purpose, so it always covers two or three keys, and letting the falloff do
 * all the work is what lets it slide smoothly as it leans instead of hopping
 * from key to key.
 */
public final class SunflowerFieldPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /**
     * Head layout. Stratified into a grid with a jitter inside each cell rather
     * than rolled freely: a free roll clumps, and a field with a bare corner and
     * three flowers stacked in the middle is the one arrangement a planted field
     * never has. Same reasoning as the desert gust lanes.
     *
     * <p>Two rows, not three. A head is round in physical space, so on a board
     * whose key rows and key columns are the same pitch it covers as many rows
     * as columns — and three rows of them on a six-row board stacked into
     * continuous vertical stripes with no grass left between. The board is a
     * wide strip, so the variety has to go across it rather than up it.
     */
    private static final int HEAD_COLUMNS = 6;
    private static final int HEAD_ROWS = 2;
    private static final double HEAD_JITTER = 0.70;

    /**
     * Head size, in key widths. Wide enough to always cover two or three keys,
     * and no wider: past about 1.3 the discs from neighbouring cells touch and
     * the field becomes one yellow sheet with the grass pushed off the board.
     */
    private static final double HEAD_RADIUS_KEYS = 1.15;
    /** Brightness of a head at full bend, as a fraction of its upright brightness. */
    private static final double HEAD_BEND_DIM = 0.45;
    private static final double HEAD_BRIGHTNESS = 0.95;

    /** Green under each head. Turns a yellow dot into something with a plant below it. */
    private static final double STEM_BRIGHTNESS = 0.30;

    /**
     * The travelling wave: how many full cycles fit across the board, how far
     * the crest tilts over its height, and how fast it moves downwind.
     *
     * <p>Under one cycle would bend the whole field in unison, which is a field
     * on a hinge rather than a field in wind. Much over two and the heads next
     * to each other disagree, and the wave stops being visible as a wave.
     */
    private static final double WAVE_CYCLES = 1.35;
    private static final double WAVE_SKEW = 0.22;
    private static final double WAVE_HZ = 0.33;
    /**
     * How far the heads lag the grass, in radians. Grass is light and goes over
     * first; a sunflower head is heavy and arrives late. Setting this to zero
     * makes the whole board move as one sheet, which reads as a lighting effect
     * rather than as two different things in the same wind.
     */
    private static final double HEAD_LAG = 0.9;
    /**
     * Shapes the bend curve. Above 1 keeps the plants nearer upright for most of
     * the cycle, so each pass reads as a gust arriving. A plain sine spends half
     * its time bent and looks like the board is breathing.
     */
    private static final double BEND_WEIGHT = 1.8;
    /** How far a head travels downwind at full bend, in key widths. */
    private static final double BEND_TRAVEL_KEYS = 0.85;

    /** Grass brightness floor and range. Deliberately well below the heads. */
    private static final double GRASS_FLOOR = 0.09;
    private static final double GRASS_SPAN = 0.13;
    private static final double GRASS_TEXTURE_SCALE = 5.5;

    /** Gust band: how wide, how much harder it bends, and how often one crosses. */
    private static final double GUST_WIDTH = 0.30;
    private static final double GUST_BOOST = 0.55;
    private static final double GUST_PERIOD_SECONDS = 9.0;

    private SunflowerFieldPattern() {
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        RGBColor grass = ctx.baseColor();
        RGBColor flower = ctx.resolvedAccentColor();

        // Where the stronger ripple is right now. One band, travelling downwind
        // on the same axis as the wave it is deepening.
        double gustCenter = frac(t / GUST_PERIOD_SECONDS) * (1.0 + 2 * GUST_WIDTH) - GUST_WIDTH;

        LightBudget budget = new LightBudget();

        // --- the grass ---------------------------------------------------
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double bend = bendAt(key.x(), key.y(), t, 0.0, gustCenter);
            double texture = 0.75 + 0.25 * valueNoise(
                    key.x() * GRASS_TEXTURE_SCALE, key.y() * GRASS_TEXTURE_SCALE);
            budget.add(key.ref(), grass, (GRASS_FLOOR + GRASS_SPAN * bend) * texture);
        }

        // --- the flowers -------------------------------------------------
        double radius = HEAD_RADIUS_KEYS * keyWidth;
        double stemOffset = keyWidth / aspect;
        int headCount = HEAD_COLUMNS * HEAD_ROWS;

        for (int i = 0; i < headCount; i++) {
            int col = i % HEAD_COLUMNS;
            int row = i / HEAD_COLUMNS;
            double baseX = (col + 0.5 + HEAD_JITTER * (hash(i * 31 + 7) - 0.5)) / HEAD_COLUMNS;
            double baseY = (row + 0.5 + HEAD_JITTER * (hash(i * 53 + 13) - 0.5)) / HEAD_ROWS;

            double bend = bendAt(baseX, baseY, t, HEAD_LAG, gustCenter);
            // Heads lean downwind and come back; they never lean into the wind,
            // so this displacement is one-directional rather than a sine.
            double headX = baseX + bend * BEND_TRAVEL_KEYS * keyWidth;
            double lit = HEAD_BRIGHTNESS * (1.0 - HEAD_BEND_DIM * bend);

            // The stem stays where it is planted while the head moves, which is
            // most of what makes the lean read as a plant bending rather than as
            // the whole flower sliding sideways.
            KeyGrid.LedPosition stem = nearestKey(grid, baseX, baseY + stemOffset, aspect);
            if (stem != null) {
                budget.add(stem.ref(), grass, STEM_BRIGHTNESS * (0.6 + 0.4 * bend));
            }

            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double d = Math.hypot(key.x() - headX, (key.y() - baseY) * aspect);
                if (d >= radius) continue;
                // Smoothstep rather than the squared falloff the particle
                // engines use. Squared peaks to a point, so a head's brightness
                // depended on how near a key it happened to be planted, and the
                // heads came out visibly uneven against each other. This is
                // flat across the middle of the disc, so every head carries the
                // same weight wherever it sits, and still soft at the rim.
                double falloff = 1.0 - d / radius;
                double disc = falloff * falloff * (3 - 2 * falloff);
                budget.add(key.ref(), flower, disc * lit);
            }
        }

        return budget.resolve();
    }

    /**
     * How far over the plant at a point is, 0 upright and 1 fully bent.
     *
     * @param lag how far this layer trails the wave, in radians
     */
    private static double bendAt(double x, double y, double t, double lag, double gustCenter) {
        double phase = TAU * (WAVE_CYCLES * x + WAVE_SKEW * y - WAVE_HZ * t) - lag;
        double bend = Math.pow(0.5 + 0.5 * Math.sin(phase), BEND_WEIGHT);

        double d = Math.abs(x - gustCenter);
        if (d < GUST_WIDTH) {
            double envelope = 1.0 - d / GUST_WIDTH;
            bend *= 1.0 + GUST_BOOST * envelope * envelope;
        }
        return Math.min(1.0, bend);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    /** Same lattice hash the canopy and the dunes use; see CanopyDapplePattern for the notes. */
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

    /** Aspect-corrected, so "nearest" means nearest on the board rather than in the coordinate space. */
    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double nx, double ny, double aspect) {
        KeyGrid.LedPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - nx, (key.y() - ny) * aspect);
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        return best;
    }

    /**
     * Sunflower plains.
     *
     * <p>Twelve heads is the tuned count on a full-size board: it puts a flower
     * within a key or two of anywhere you look while still leaving green
     * between them. Past about sixteen the discs merge into a single yellow
     * sheet, which takes the grass — and with it any sense of individual
     * plants — off the board entirely.
     */
    public static SunflowerFieldPattern sunflowerPlains() {
        return new SunflowerFieldPattern();
    }
}
