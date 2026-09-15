package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The windswept biomes: eroded ground in hard terraces, with bare rock showing
 * through and wind crossing the shelves.
 *
 * <p>Replaces a flat shimmer on all four. The shimmer was not wrong so much as
 * silent — it said "this place is a slightly grey green", which is also what it
 * said about the swamp, the taiga and the beach.
 *
 * <h2>The name is misleading, and drawing the name would be a mistake</h2>
 * Nothing in a windswept biome is actually moving. "Windswept" in vanilla
 * describes what the wind is supposed to have already done: the terrain is
 * chopped into one-block shelves, with overhangs, floating ledges and long
 * scars of exposed stone and gravel where the soil has gone. Drawing gusts of
 * something blowing past would be drawing the word rather than the place, and
 * would land somewhere between the desert's lifted sand and a plain sweep.
 *
 * <p>So the subject here is the ground, and the wind is only what moves over
 * it. The four layers, in order of how much of the effect they carry:
 *
 * <ol>
 *   <li><b>Terraces.</b> A height field <i>quantised</i> into a handful of flat
 *       bands. This is the whole idea and the one thing separating this from
 *       {@link DesertDunesPattern}: a dune field is a continuous surface and is
 *       drawn as smooth noise, while eroded ground is a staircase and has to
 *       have hard edges in it or it is just a dune field in green.</li>
 *   <li><b>Ledge lips.</b> The keys sitting on a band boundary are lit well
 *       above the shelf they belong to. This is the detail that makes the layer
 *       above work: at roughly six key rows of vertical resolution, quantising
 *       on its own reads as a posterised blob, and it is the lit edge — grass
 *       and stone catching light where the ground drops away — that makes the
 *       eye see a step.</li>
 *   <li><b>Scars.</b> A second, unrelated noise field decides where the ground
 *       shows its other material, so patches of the board sit in the accent
 *       colour rather than the base. Static, because a hillside is.</li>
 *   <li><b>Wind.</b> Broad, slanted passes that brighten the lips as they
 *       cross, plus a small constant sway between them.</li>
 * </ol>
 *
 * <h2>Base is the ground, accent is what erosion exposed</h2>
 * Which material is in the majority is a per-preset decision, not a fixed one:
 * the grassy biomes are green with rock showing through, and the gravelly hills
 * invert that — bare rock with what is left of the grass clinging to it. The
 * base colour is always the dominant material of the two, because it is also
 * what {@code BiomeColorEffect} crossfades through when you walk in, and fading
 * a whole board to a colour that only covers a fifth of it looks like a mistake
 * on the way in and on the way out again.
 *
 * <h2>The terrain does not move, and that was the risk</h2>
 * The height field is a pure function of key position with no time term at all,
 * which is the honest way to draw a hillside and also a good way to end up with
 * a board that looks like the animation has stopped. Two things cover that: the
 * wind passes are timed to overlap, so there is essentially always one crossing
 * somewhere, and the lips carry a slow sway underneath them so the ledges are
 * never completely still between passes. If this ever does read as frozen, the
 * sway amplitude is the first number to raise — not the gust count, which buys
 * motion at the cost of the ground reading as weather.
 *
 * <h2>Why the gust front is slanted</h2>
 * An earlier version swept a vertical band across the board, which read as a
 * wipe — the same gesture as {@code SweepPattern}, and unmistakably a lighting
 * effect rather than anything happening in the world. Leaning the front over
 * costs one term and is enough to break the association.
 */
public final class WindsweptRidgePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /**
     * Height field frequency, in noise cells across the board. The two axes are
     * separate numbers rather than one scale and a stretch factor because the
     * ratio between them <i>is</i> the terrain: y runs faster than x, so
     * features come out as broad shelves stacked up the board rather than as
     * blobs, or as the vertical ridges a dune field wants.
     */
    private final double ridgeScaleX;
    private final double ridgeScaleY;
    /** How many flat bands the height field is chopped into. */
    private final int terraceCount;
    /** Brightness of the lowest shelf. Never zero: ground in shadow is still ground. */
    private final double shelfFloor;
    /** Brightness added between the lowest shelf and the highest. */
    private final double shelfSpan;
    /** Above 1 pushes the shelves apart at the top of the range, below 1 evens them out. */
    private final double shelfContrast;
    /** Extra brightness on a ledge lip, on top of the shelf it belongs to. */
    private final double lipBoost;
    /** Scar field frequency, and how much of the board it covers, 0..1. */
    private final double scarScale;
    private final double scarCoverage;
    private final int gustCount;
    private final double gustStrength;
    /**
     * Where in the noise field this preset takes its terrain from.
     *
     * <p>An earlier version left this out, and all four presets sampled the
     * same origin. Scale alone does not hide that: the shelves landed in the
     * same places and the scars in the same corner every time, so the four
     * biomes read as one hillside at four zoom levels — which matters because
     * these biomes generate next to each other, and walking between two of them
     * showed the board recolouring without the ground changing.
     */
    private final double seedOffset;

    private WindsweptRidgePattern(double ridgeScaleX, double ridgeScaleY, int terraceCount,
                                  double shelfFloor, double shelfSpan, double shelfContrast,
                                  double lipBoost, double scarScale, double scarCoverage,
                                  int gustCount, double gustStrength, int seed) {
        this.ridgeScaleX = ridgeScaleX;
        this.ridgeScaleY = ridgeScaleY;
        this.terraceCount = Math.max(2, terraceCount);
        this.shelfFloor = shelfFloor;
        this.shelfSpan = shelfSpan;
        this.shelfContrast = shelfContrast;
        this.lipBoost = lipBoost;
        this.scarScale = scarScale;
        this.scarCoverage = scarCoverage;
        this.gustCount = gustCount;
        this.gustStrength = gustStrength;
        this.seedOffset = seed * 37.13;
    }

    /** How soft the edge of a scar is, in noise units either side of the threshold. */
    private static final double SCAR_EDGE = 0.10;
    /**
     * How far {@link #scarCoverage} moves the threshold it is tested against.
     *
     * <p>A single octave of value noise does not spread evenly over 0..1 — it
     * clusters hard around the middle, because every sample is an interpolation
     * between four lattice values rather than a lattice value itself. An earlier
     * version used the coverage figure as the threshold directly, which meant
     * asking for 30% of the board and getting 9% of it, differently wrong for
     * every preset depending on where in the field it sampled. Scaling the
     * offset from the middle instead keeps the number roughly honest: coverage
     * 0.5 is half the board, and the presets land within a few points of what
     * they ask for.
     */
    private static final double SCAR_SPREAD = 0.55;

    /** Sway on the lips between gusts: fraction of the lip brightness, and its rate. */
    private static final double SWAY_AMOUNT = 0.22;
    private static final double SWAY_HZ = 0.21;

    /** Half-width of a gust front, in normalised board widths. */
    private static final double GUST_WIDTH = 0.26;
    /** How far the front leans over across the height of the board. */
    private static final double GUST_LEAN = 0.16;
    private static final double GUST_PERIOD_SECONDS = 7.0;
    /**
     * Share of a gust that lands on flat shelf rather than on a lip. A gust is
     * air, so it cannot be invisible everywhere except on the edges, but the
     * lips are what it is for.
     */
    private static final double GUST_ON_SHELF = 0.30;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        RGBColor ground = ctx.baseColor();
        RGBColor exposed = ctx.resolvedAccentColor();

        // One key-width of real distance, expressed in normalised y. Normalised
        // coordinates run 0..1 on both axes whatever shape the board is, so a
        // step of the same physical size on both axes has to go through the
        // aspect ratio — see KeyGrid.aspectRatio. On a full-size board this
        // lands within a few percent of one key row, which is what the lip test
        // below wants.
        double rowStep = grid.keyWidthNormalised() / aspect;

        // Gusts are resolved once per frame rather than per key: there are a
        // hundred-odd keys against a handful of gusts, and the per-key loop is
        // already doing four noise samples.
        double[] gustCenter = new double[gustCount];
        double[] gustLevel = new double[gustCount];
        for (int i = 0; i < gustCount; i++) {
            double period = GUST_PERIOD_SECONDS / (0.85 + 0.40 * hash(i * 17 + 3));
            // Phases are stratified rather than hashed, so gusts arrive spread
            // through the cycle. A plain roll clumps them, and a board that sits
            // still for four seconds and then takes three gusts at once reads as
            // a glitch rather than as weather — the same problem the desert
            // gusts have with their lanes.
            double progress = frac(t / period + (i + hash(i * 11 + 1)) / gustCount);
            gustCenter[i] = -GUST_WIDTH + progress * (1.0 + 2 * GUST_WIDTH);
            // Sine envelope: a gust fades in and out rather than switching on at
            // the edge of the board, which would read as a key column.
            gustLevel[i] = Math.sin(Math.PI * progress);
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            int band = bandAt(key.x(), key.y());
            double level = band / (terraceCount - 1.0);
            double lit = shelfFloor + shelfSpan * Math.pow(level, shelfContrast);

            // A lip is a key with lower ground one key-width downhill of it. The
            // test is deliberately hard-edged: softening it by how close the
            // height is to its band boundary gives a gradient, and a gradient is
            // exactly what these biomes do not have.
            double lip = bandAt(key.x(), key.y() + rowStep) < band ? 1.0 : 0.0;

            // Sway varies along the board, so the ledges do not all breathe
            // together and the effect reads as air rather than as a dimmer.
            double sway = 1.0 + SWAY_AMOUNT * Math.sin(TAU * SWAY_HZ * t + key.x() * 6.1 + band);

            double scarThreshold = 0.5 + (0.5 - scarCoverage) * SCAR_SPREAD;
            double scar = smoothstep(scarThreshold - SCAR_EDGE, scarThreshold + SCAR_EDGE,
                    valueNoise(key.x() * scarScale + 11.7 + seedOffset,
                            key.y() * scarScale + 3.3 + seedOffset));
            RGBColor material = ground.lerp(exposed, scar);

            double total = lit + lip * lipBoost * sway;

            for (int i = 0; i < gustCount; i++) {
                if (gustLevel[i] <= 0.02) continue;
                double front = gustCenter[i] + GUST_LEAN * (key.y() - 0.5);
                double d = Math.abs(key.x() - front);
                if (d >= GUST_WIDTH) continue;
                double falloff = 1.0 - d / GUST_WIDTH;
                total += gustLevel[i] * falloff * falloff * gustStrength
                        * (GUST_ON_SHELF + (1.0 - GUST_ON_SHELF) * lip);
            }

            // One add per key, in that key's own material. Lighting the lips and
            // the gusts in the accent instead would be easier to tune and would
            // put grey highlights on a green hillside, which is a thing stone
            // does and grass does not.
            budget.add(key.ref(), material, total);
        }

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Terrain
    // ---------------------------------------------------------------

    /** Which terrace the ground falls in at a point. No time term: hillsides stay put. */
    private int bandAt(double x, double y) {
        double hx = x * ridgeScaleX + seedOffset;
        double hy = y * ridgeScaleY + seedOffset * 0.61;
        // Two octaves, the second offset so it does not share lattice corners
        // with the first. One octave alone gives terraces of all one width,
        // which reads as a pattern rather than as ground.
        double h = 0.66 * valueNoise(hx, hy) + 0.34 * valueNoise(hx * 2.1 + 5.2, hy * 2.1 + 1.7);
        int band = (int) (clamp01(h) * terraceCount);
        return Math.min(band, terraceCount - 1);
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

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / Math.max(1e-6, edge1 - edge0)));
        return v * v * (3 - 2 * v);
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

    // ---------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------

    /**
     * Windswept hills: grass shelves with stone showing through.
     *
     * <p>The middle setting of the family in every respect, and the one the
     * other three are described against. Four terraces is as many as six key
     * rows will carry while every band still gets a row to itself somewhere on
     * the board.
     */
    public static WindsweptRidgePattern windsweptHills() {
        return new WindsweptRidgePattern(2.2, 3.0, 4, 0.14, 0.40, 1.00, 0.34, 3.4, 0.27, 3, 0.30, 0);
    }

    /**
     * Windswept gravelly hills: the same ground with the soil gone.
     *
     * <p>This is the preset where base and accent swap jobs — the board is rock,
     * and the accent is the grass still holding on across a fifth of it. Six
     * terraces rather than four because scree breaks into finer steps than turf
     * does, and the shelf span is tighter so the bands sit closer together;
     * between them the hillside reads as loose material rather than as ledges
     * with fields on top of them.
     */
    public static WindsweptRidgePattern windsweptGravellyHills() {
        return new WindsweptRidgePattern(2.6, 3.6, 6, 0.16, 0.36, 0.90, 0.32, 3.0, 0.32, 3, 0.26, 3);
    }

    /**
     * Windswept forest: spruce on eroded slopes, with bare dirt on the steps.
     *
     * <p>Darkest floor of the four and the highest contrast between shelves,
     * because this is the only one of them with a canopy over it — the light
     * reaching the low ground has been through trees first. Three wide terraces
     * rather than four narrow ones for the same reason: what is legible under
     * cover is the few big steps, not the detail on them.
     */
    public static WindsweptRidgePattern windsweptForest() {
        return new WindsweptRidgePattern(2.0, 2.6, 3, 0.14, 0.46, 1.20, 0.40, 3.8, 0.21, 3, 0.28, 7);
    }

    /**
     * Windswept savanna: the most dramatic terrain of the four, drawn that way.
     *
     * <p>Tallest and fewest shelves, brightest lips, most wind. The biome
     * generates the steepest cliffs in the overworld and carries almost nothing
     * on them, so there is nothing between the eye and the shape of the ground —
     * the other three presets are all, in one way or another, softening what
     * this one states outright.
     */
    public static WindsweptRidgePattern windsweptSavanna() {
        return new WindsweptRidgePattern(1.8, 2.4, 3, 0.12, 0.40, 1.15, 0.40, 2.8, 0.21, 4, 0.36, 11);
    }
}
