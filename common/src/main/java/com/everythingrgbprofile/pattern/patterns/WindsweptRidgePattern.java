package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The windswept biomes: eroded ground in hard terraces, bare rock showing
 * through, and wind tearing across the lot of it in streaks.
 *
 * <p>Replaces a flat shimmer on all four of them. The shimmer was not wrong so
 * much as silent: it said "this place is a slightly grey green", which is
 * precisely what it also said about the swamp, the taiga and the beach.
 *
 * <h2>The wind is the point now</h2>
 * An earlier version of this class argued the opposite. "Windswept" in vanilla
 * describes what the wind already did to the terrain, it said, so draw the
 * ground and let the wind be a couple of slow slanted passes over it. Very
 * principled. Also, when measured, 0 to 4% of keys changed by a noticeable
 * amount in any given second, so a glance down at the board found a still
 * picture of some hills. Nobody glancing at their keyboard mid-fight is
 * reading terrain. They're checking whether it's alive.
 *
 * <p>So the wind now moves the way Terraria's Underground Desert moves, which
 * is in {@code reference/RGB_Underground_Desert.gif} and was measured frame by
 * frame rather than eyeballed:
 *
 * <ul>
 *   <li>Everything travels <b>right to left</b>, at <b>one key per 140ms</b>,
 *       the same speed on every row. 86 of 87 neighbouring key pairs agreed on
 *       that. The field slides; keys don't flicker on their own.</li>
 *   <li>Each row runs its own streaks. Adjacent rows barely correlate, which is
 *       what stops it reading as a column wipe.</li>
 *   <li>A streak is a soft swell about six keys long, a touch longer behind
 *       than in front.</li>
 *   <li>Nothing is ever off: the dimmest key sits around a fifth of the peak,
 *       the median around a third.</li>
 *   <li>Brighter means warmer. The dim keys are deep ember and the peaks are
 *       amber, so a streak shifts hue as it lights up, not just level.</li>
 *   <li>The whole thing loops, at 5.66 seconds, which at that speed is about
 *       forty keys of texture per row.</li>
 * </ul>
 *
 * <p>Measured on the reference, about 38% of keys swing by a quarter of full
 * brightness in any one second. That number is the target, and when the
 * presets were tuned they landed near it: hills and gravelly hills about 41%,
 * the sheltered forest 34%, the savanna a gustier 50-ish. It's also the first
 * number to re-measure if this ever looks stopped again.
 *
 * <h2>What stayed from the hillside</h2>
 * The ground is still here and still does the work of saying which biome this
 * is; the wind just sits on top of it now. Four layers, bottom up:
 *
 * <ol>
 *   <li><b>Terraces.</b> A height field <i>quantised</i> into a handful of flat
 *       bands. This is what separates the pattern from
 *       {@link DesertDunesPattern}: a dune field is a continuous surface, and
 *       eroded ground is a staircase with hard edges in it.</li>
 *   <li><b>Ledge lips.</b> Keys on a band boundary are lit above their shelf.
 *       At six rows of vertical resolution, quantising on its own reads as a
 *       posterised blob. The lit edge is what makes the eye see a step.</li>
 *   <li><b>Scars.</b> A second, unrelated noise field decides where the ground
 *       shows its other material. Static, because a hillside is.</li>
 *   <li><b>Wind.</b> The streaks above. Dimming the ground to make room for
 *       them is deliberate: the reference puts its floor at about a fifth of
 *       peak, and a bright hillside leaves the streaks nowhere to go.</li>
 * </ol>
 *
 * <h2>Base is the ground, accent is what erosion exposed</h2>
 * Which material is in the majority is a per-preset decision, not a fixed one:
 * the grassy biomes are green with rock showing through, and the gravelly hills
 * invert that, bare rock with what's left of the grass clinging to it. The
 * base colour is always the dominant material of the two, because it is also
 * what {@code BiomeColorEffect} crossfades through when you walk in, and fading
 * a whole board to a colour that covers a third of it at most looks like a mistake
 * on the way in and on the way out again.
 *
 * <h2>Not the desert, twice</h2>
 * The desert's gusts are a few blobs of sand with tails, skimming left to right
 * over dunes. These are full lanes, one per row, running right to left over
 * terraces. Same reference, different half of it: the desert took the density
 * and this takes the motion.
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
    /**
     * Share of lane cells that carry a streak, 0..1. More wind, more streaks.
     * Around 0.3 lands on the reference's 38% of keys moving per second;
     * 0.4 pushed the hills past 50%, which starts reading as static on a TV
     * rather than weather.
     */
    private final double streakDensity;
    /** How far a streak's peak lifts a key from the ground towards full brightness, 0..1. */
    private final double streakStrength;
    /**
     * Where in the noise field this preset takes its terrain from.
     *
     * <p>An earlier version left this out, and all four presets sampled the
     * same origin. Scale alone does not hide that: the shelves landed in the
     * same places and the scars in the same corner every time, so the four
     * biomes read as one hillside at four zoom levels. Which matters, because
     * these biomes generate next to each other, and walking between two of them
     * showed the board recolouring without the ground changing.
     */
    private final double seedOffset;
    /** The same seed as an int, for the lane hashes, which want integers. */
    private final int seed;

    private WindsweptRidgePattern(double ridgeScaleX, double ridgeScaleY, int terraceCount,
                                  double shelfFloor, double shelfSpan, double shelfContrast,
                                  double lipBoost, double scarScale, double scarCoverage,
                                  double streakDensity, double streakStrength, int seed) {
        this.ridgeScaleX = ridgeScaleX;
        this.ridgeScaleY = ridgeScaleY;
        this.terraceCount = Math.max(2, terraceCount);
        this.shelfFloor = shelfFloor;
        this.shelfSpan = shelfSpan;
        this.shelfContrast = shelfContrast;
        this.lipBoost = lipBoost;
        this.scarScale = scarScale;
        this.scarCoverage = scarCoverage;
        this.streakDensity = streakDensity;
        this.streakStrength = streakStrength;
        this.seedOffset = seed * 37.13;
        this.seed = seed;
    }

    /** How soft the edge of a scar is, in noise units either side of the threshold. */
    private static final double SCAR_EDGE = 0.10;
    /**
     * How far {@link #scarCoverage} moves the threshold it is tested against.
     *
     * <p>A single octave of value noise does not spread evenly over 0..1. It
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

    // ---------------------------------------------------------------
    // Wind, as measured off the reference
    // ---------------------------------------------------------------

    /**
     * One lane's texture: this many cells of {@link #CELL_KEYS} keys each,
     * repeating. Five cells of eight is forty keys, which is the reference's
     * loop at its speed, give or take a key.
     */
    private static final int LANE_CELLS = 5;
    private static final double CELL_KEYS = 8.0;
    /**
     * How long the texture takes to come round again. 5.6s rather than the
     * reference's 5.66 because 5.6 is a whole number of frames at both 20ms and
     * 50ms, and the tuner exports GIFs at both. The loop being exact is worth
     * more than the extra 0.06s being faithful.
     */
    private static final double LOOP_SECONDS = 5.6;
    /** Keys per second, leftward. Falls out as 1 key per 140ms, the measured lag. */
    private static final double WIND_KEYS_PER_SECOND = LANE_CELLS * CELL_KEYS / LOOP_SECONDS;

    /**
     * Front half of a streak, in keys, before per-streak jitter. The reference
     * swells over about three keys and drops over slightly more.
     */
    private static final double STREAK_HEAD_KEYS = 3.0;
    /** Back half as a multiple of the front. The trailing side is the longer one. */
    private static final double STREAK_TAIL_RATIO = 1.25;

    /**
     * The terrain squeezed into the bottom of the range, so the wind has the
     * top of it.
     *
     * <p>The terraces were tuned to fill the board on their own, topping out
     * near 0.9 on a lit lip. Left there, a streak has about 10% of headroom and
     * is invisible. Halved and lifted, the ground sits between roughly 0.18
     * and 0.57: the darkest key lands near the reference's fifth-of-peak floor,
     * the terraces still read, and nothing is ever off.
     *
     * <p>The lift matters as much as the halving. Scaling alone put the floor
     * at 0.07, a third of the reference's, and the board went patchy and dark
     * between streaks.
     */
    private static final double GROUND_UNDER_WIND = 0.5;
    private static final double GROUND_LIFT = 0.12;

    /**
     * How far a streak's hue drifts towards {@link #WARM_HUE} at full strength,
     * in degrees. The reference goes from ember to amber as it brightens, about
     * 25 degrees; this borrows most of that. A grey key has no hue to move, so
     * stone stays stone and only picks up brightness.
     */
    private static final double STREAK_WARM_DEG = 18.0;
    /** Yellow-orange: where sunlit dust drifts to. */
    private static final double WARM_HUE = 50.0;

    /**
     * Sway on the lips, as a fraction of the lip brightness. Tied to the loop
     * rather than given its own rate, so the whole pattern repeats exactly and
     * an exported GIF has no seam.
     */
    private static final double SWAY_AMOUNT = 0.22;
    private static final double SWAY_HZ = 1.0 / LOOP_SECONDS;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        RGBColor ground = ctx.baseColor();
        RGBColor exposed = ctx.resolvedAccentColor();

        // One key-width of real distance, expressed in normalised y. Normalised
        // coordinates run 0..1 on both axes whatever shape the board is, so a
        // step of the same physical size on both axes has to go through the
        // aspect ratio. See KeyGrid.aspectRatio. On a full-size board this
        // lands within a few percent of one key row, which is what both the lip
        // test and the lane split below want.
        double rowStep = keyWidth / aspect;

        // Texture coordinate offset. Adding it (rather than subtracting) is
        // what makes the wind blow right to left: a feature at texture position
        // u sits at x = u - speed * t, which shrinks as t grows.
        double drift = WIND_KEYS_PER_SECOND * t;
        double sway = TAU * SWAY_HZ * t;

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
            // together and it reads as air rather than as a dimmer.
            double lipSway = 1.0 + SWAY_AMOUNT * Math.sin(sway + key.x() * 6.1 + band);

            double scarThreshold = 0.5 + (0.5 - scarCoverage) * SCAR_SPREAD;
            double scar = smoothstep(scarThreshold - SCAR_EDGE, scarThreshold + SCAR_EDGE,
                    valueNoise(key.x() * scarScale + 11.7 + seedOffset,
                            key.y() * scarScale + 3.3 + seedOffset));
            RGBColor material = ground.lerp(exposed, scar);

            double terrain = GROUND_LIFT + (lit + lip * lipBoost * lipSway) * GROUND_UNDER_WIND;

            int lane = (int) Math.round(key.y() / rowStep);
            double streak = streakAt(lane, key.x() / keyWidth + drift) * streakStrength;

            // Lifted towards full, not added on top, so a streak crossing a lit
            // lip and one crossing a dark shelf both peak in the same place and
            // neither blows past what the LED can do.
            double total = terrain + (1.0 - terrain) * streak;

            // One add per key, in that key's own material, warmed by the wind.
            // Lighting the streaks in the accent instead would put grey dust on
            // a green hillside everywhere at once, and the scars already say
            // where the stone is.
            RGBColor color = streak > 0.01 ? warmed(material, STREAK_WARM_DEG * streak) : material;
            budget.add(key.ref(), color, total);
        }

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Wind
    // ---------------------------------------------------------------

    /**
     * Streak brightness, 0..1, at texture position {@code u} (in keys) along one
     * lane.
     *
     * <p>The lane is cut into cells, and a streak lives in a cell but is allowed
     * to spill into the next one, so this checks the neighbours too and keeps
     * the brightest. Cheaper than it sounds: three cells, a few hashes each.
     */
    private double streakAt(int lane, double u) {
        int cell = (int) Math.floor(u / CELL_KEYS);
        double best = 0;
        for (int c = cell - 1; c <= cell + 1; c++) {
            best = Math.max(best, streakInCell(lane, c, u));
        }
        return best;
    }

    private double streakInCell(int lane, int cell, double u) {
        // Wrapped for the lookup, unwrapped for the position: the texture
        // repeats every LANE_CELLS cells, which is what makes it loop.
        int id = Math.floorMod(cell, LANE_CELLS);
        if (!laneHasStreak(lane, id)) return 0;

        double centre = (cell + laneHash(lane, id, 1)) * CELL_KEYS;
        double head = STREAK_HEAD_KEYS * (0.8 + 0.4 * laneHash(lane, id, 2));
        double tail = head * STREAK_TAIL_RATIO;
        double peak = 0.65 + 0.35 * laneHash(lane, id, 3);

        // Left of centre is the leading edge, since the wind blows left.
        double d = u - centre;
        double q = d < 0 ? -d / head : d / tail;
        if (q >= 1) return 0;
        double bump = 1 - q * q;
        return bump * bump * peak;
    }

    /**
     * Does this cell of this lane carry a streak.
     *
     * <p>A plain roll per cell leaves some lanes with nothing at all: five cells
     * at a third each comes up empty about one lane in eight, and an empty lane
     * is a row that never moves, which is the bug this rewrite is for. So a
     * lane that rolls no streaks gets one anyway, in whichever cell came
     * closest.
     */
    private boolean laneHasStreak(int lane, int id) {
        double own = laneHash(lane, id, 0);
        if (own < streakDensity) return true;
        for (int c = 0; c < LANE_CELLS; c++) {
            double other = laneHash(lane, c, 0);
            if (other < streakDensity || other < own) return false;
        }
        return true;
    }

    /** A stable 0..1 per lane, cell and purpose. Seeded, so the presets blow differently. */
    private double laneHash(int lane, int cell, int salt) {
        return latticeHash(lane * 7919 + seed * 131 + salt * 17, cell * 104729 + salt);
    }

    /**
     * Rotates a colour's hue towards {@link #WARM_HUE} by up to {@code degrees},
     * never past it, keeping saturation and value. Done by hand rather than
     * through {@code java.awt.Color}, because pulling AWT onto the render
     * thread for twelve lines of arithmetic is how you find out which platforms
     * hate AWT.
     */
    static RGBColor warmed(RGBColor c, double degrees) {
        double r = c.r() / 255.0, g = c.g() / 255.0, b = c.b() / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double chroma = max - min;
        if (chroma < 1e-6 || degrees <= 0) return c;

        double h;
        if (max == r) h = 60 * (((g - b) / chroma) % 6);
        else if (max == g) h = 60 * ((b - r) / chroma + 2);
        else h = 60 * ((r - g) / chroma + 4);
        if (h < 0) h += 360;

        double delta = ((WARM_HUE - h + 540) % 360) - 180;
        h += Math.signum(delta) * Math.min(Math.abs(delta), degrees);
        h = ((h % 360) + 360) % 360;

        double x = chroma * (1 - Math.abs((h / 60) % 2 - 1));
        double[] rgb = switch ((int) (h / 60)) {
            case 0 -> new double[]{chroma, x, 0};
            case 1 -> new double[]{x, chroma, 0};
            case 2 -> new double[]{0, chroma, x};
            case 3 -> new double[]{0, x, chroma};
            case 4 -> new double[]{x, 0, chroma};
            default -> new double[]{chroma, 0, x};
        };
        double m = max - chroma;
        return new RGBColor((int) Math.round((rgb[0] + m) * 255),
                (int) Math.round((rgb[1] + m) * 255),
                (int) Math.round((rgb[2] + m) * 255));
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
     * other three are described against. Four terraces, comfortably inside
     * the six that six key rows can carry at most, so every band still gets a
     * row to itself somewhere on the board.
     */
    public static WindsweptRidgePattern windsweptHills() {
        return new WindsweptRidgePattern(2.2, 3.0, 4, 0.14, 0.40, 1.00, 0.34, 3.4, 0.27, 0.30, 0.90, 0);
    }

    /**
     * Windswept gravelly hills: the same ground with the soil gone.
     *
     * <p>This is the preset where base and accent swap jobs: the board is rock,
     * and the accent is the grass still holding on across about a third of
     * it. Six terraces rather than four because scree breaks into finer steps
     * than turf does, and the shelf span is tighter so the bands sit closer together;
     * between them the hillside reads as loose material rather than as ledges
     * with fields on top of them. Grey has no hue for the wind to warm, so this
     * one's streaks are brightness only.
     */
    public static WindsweptRidgePattern windsweptGravellyHills() {
        return new WindsweptRidgePattern(2.6, 3.6, 6, 0.16, 0.36, 0.90, 0.32, 3.0, 0.32, 0.30, 0.90, 3);
    }

    /**
     * Windswept forest: spruce on eroded slopes, with bare dirt on the steps.
     *
     * <p>The highest contrast between shelves of the four, because this is
     * the only one of them with a canopy over it: the light reaching the low
     * ground has been through trees first. The trees take some of the wind
     * too, so the fewest and softest streaks of the four. Fewest, not few;
     * see the class doc for what happens when a windswept board goes quiet.
     */
    public static WindsweptRidgePattern windsweptForest() {
        return new WindsweptRidgePattern(2.0, 2.6, 3, 0.14, 0.46, 1.20, 0.40, 3.8, 0.21, 0.24, 0.85, 7);
    }

    /**
     * Windswept savanna: the most dramatic terrain of the four, drawn that way.
     *
     * <p>The broadest shelves, the fewest of them (level with the forest), lips
     * as bright as the forest's, the darkest floor and the strongest wind. The
     * biome generates the steepest cliffs in the overworld and carries almost
     * nothing on them, so there is nothing between the eye and the shape of
     * the ground, or between the wind and the eye either.
     */
    public static WindsweptRidgePattern windsweptSavanna() {
        return new WindsweptRidgePattern(1.8, 2.4, 3, 0.12, 0.40, 1.15, 0.40, 2.8, 0.21, 0.30, 1.00, 11);
    }
}
