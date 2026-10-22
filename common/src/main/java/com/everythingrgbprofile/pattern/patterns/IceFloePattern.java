package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Arrays;
import java.util.Map;

/**
 * Frozen water: dark sea with slabs of ice drifting across it.
 *
 * <p>Replaces {@code bubble-rise} on the frozen oceans and a plain
 * {@code shimmer} on the frozen river. The bubbles were the problem. Pale blue
 * dots floating upward off a pale blue board doesn't read as underwater, it
 * reads as ash coming off something that's on fire, which is roughly the
 * opposite of a frozen ocean. Impressive, honestly.
 *
 * <h2>What it is instead</h2>
 * You're looking straight down at the water. Everything below is built so the
 * ice reads as solid things sitting ON the water rather than as brighter bits
 * of it.
 * <ol>
 *   <li><b>Water.</b> The base colour, dim, with a slow swell rolling through
 *       it on the oceans and current streaks running along it on the river.
 *       Never black: dark water is still water.</li>
 *   <li><b>Floes.</b> Irregular slabs several keys across, in the accent
 *       colour, drifting sideways. Each has a surface texture pinned to the
 *       floe rather than to the board, so it slides past as one object instead
 *       of a bright shape that re-textures itself every frame. Each one also
 *       bobs on its own, slightly out of step with the others.</li>
 *   <li><b>The lap.</b> A thin lighter band in the water right against each
 *       floe's edge, which is the water sloshing against it. This is the layer
 *       that sells "on the water". Without it a floe is a stencil.</li>
 *   <li><b>Glints.</b> A key on some floe catches the light for a third of a
 *       second, a few times a second across the board. Pinned to the floe like
 *       the texture, so a glint that lands mid-drift drifts with it.</li>
 * </ol>
 *
 * <h2>Why the floes never crash into each other</h2>
 * They all drift at the same speed. Pack ice goes where the wind and the
 * current push it, and they push the whole field at once, so the field moves
 * as one sheet with gaps in it. Nothing ever catches anything up.
 *
 * <p>The layout is rolled once per board: floes scattered at random over a
 * strip a bit wider than the board, each one rejected if it lands too close to
 * one already placed. Then the whole strip slides sideways and wraps. The gap
 * off the edge is wide enough that a floe is fully gone before it comes back
 * round, rather than being sliced in half across the two ends of the board.
 *
 * <p>Each floe also sways a little on its own, a fraction of a key either
 * way. The spacing allows for it, so the sway can never close a gap.
 *
 * <p>An earlier version ran the floes in horizontal lanes at different speeds.
 * On a board six rows tall that leaves room for floes about one row high,
 * which come out as lit bars sliding along the rows. A lit row is a progress
 * bar, and a progress bar is the one thing this was never supposed to look
 * like.
 *
 * <h2>Edges are soft on purpose</h2>
 * Coverage fades across about a key's width at a floe's edge. At roughly half
 * a key a second of drift, a hard edge would move by snapping a whole key on
 * every couple of seconds, which reads as a slideshow. Soft edges let the
 * board show a floe halfway across a key, and that's what makes it glide.
 *
 * <h2>The river is not just a faster ocean</h2>
 * Rivers freeze from the banks inward, so the frozen river has shelf ice fixed
 * along the top and bottom rows with a jagged edge, and an open channel down
 * the middle carrying smaller broken chunks, faster. The water in the channel
 * streaks along with the current, where the oceans get a slow swell instead.
 *
 * <h2>Colour</h2>
 * The shipped water colour is vanilla's own frozen water, {@code 3750089}
 * ({@code #3938C9}) in the biome files, which is the same for all three
 * biomes. The deep ocean runs it darker because it's supposed to be the deep
 * one. The ice is a strong cyan rather than white, for the usual reason (see
 * the {@code white} number in {@code tuning/README.md}): the paler a colour,
 * the more of it an LED puts out as plain white, and plain white on a keyboard
 * reads as "a light is on", not "ice". Which is also more or less how the old
 * bubbles ended up looking like ash.
 */
public final class IceFloePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // --- presets -------------------------------------------------------

    /** Frozen ocean: a loose field of floes, big and small, in no hurry. */
    public static IceFloePattern frozenOcean() {
        return new IceFloePattern(10, 1.5, 3.4, 1.00, 1.50, 0.60, false, 0.38, 3);
    }

    /**
     * Deep frozen ocean: a few big slow slabs, closer to bergs than floes,
     * over darker water. Fewer things moving, and each one bigger, which is
     * most of what "deep" means from above.
     */
    public static IceFloePattern deepFrozenOcean() {
        return new IceFloePattern(5, 2.8, 4.6, 1.35, 1.85, 0.35, false, 0.34, 11);
    }

    /** Frozen river: shelf ice on both banks, and broken chunks running down the channel between them. */
    public static IceFloePattern frozenRiver() {
        return new IceFloePattern(8, 1.3, 2.4, 0.75, 1.00, 1.50, true, 0.40, 7);
    }

    /** How many floes the layout tries to fit. It stops short if the board runs out of room. */
    private final int floeCount;
    /** Floe half-width range, in key widths. */
    private final double halfWidthMin, halfWidthMax;
    /** Floe half-height range, in rows. */
    private final double halfHeightMin, halfHeightMax;
    /** Drift speed, in key widths per second. */
    private final double speed;
    /** River: shelf ice fixed along the top and bottom rows, and current streaks in the water. */
    private final boolean river;
    private final double waterLevel;
    private final int seed;

    private IceFloePattern(int floeCount,
                           double halfWidthMin, double halfWidthMax,
                           double halfHeightMin, double halfHeightMax,
                           double speed, boolean river,
                           double waterLevel, int seed) {
        this.floeCount = floeCount;
        this.halfWidthMin = halfWidthMin;
        this.halfWidthMax = halfWidthMax;
        this.halfHeightMin = halfHeightMin;
        this.halfHeightMax = halfHeightMax;
        this.speed = speed;
        this.river = river;
        this.waterLevel = waterLevel;
        this.seed = seed;
    }

    // --- levels, 0..1 --------------------------------------------------

    private static final double FLOE_LEVEL = 0.78;
    /** How much the pinned surface texture moves a floe's brightness either way. */
    private static final double FLOE_TEXTURE = 0.12;
    private static final double FLOE_BOB = 0.05;
    /**
     * Shelf ice. Dimmer than the floes, so the eye goes to the things that
     * move. At floe brightness the shelves would be two lit bars framing the
     * board, which is a very nice picture frame and not a very good river.
     */
    private static final double SHELF_LEVEL = 0.55;
    private static final double LAP_LEVEL = 0.26;
    /** How far out into the water the lap reaches, in key widths. */
    private static final double LAP_REACH = 0.9;
    private static final double SWELL_DEPTH = 0.10;
    private static final double STREAK_DEPTH = 0.14;

    // --- glints --------------------------------------------------------

    /** Independent glint sources, each firing once per {@link #GLINT_PERIOD}. */
    private static final int GLINTS = 5;
    private static final double GLINT_PERIOD = 1.7;
    private static final double GLINT_SECONDS = 0.35;
    private static final double GLINT_LEVEL = 0.95;

    // --- floe shape ----------------------------------------------------

    /**
     * How lumpy a floe's outline is. Three wobbles round the edge at these
     * strengths. Push the total much past a quarter and the outline starts
     * growing limbs, which at this resolution would look like two floes
     * glued together rather than one floe with character.
     */
    private static final double EDGE_2 = 0.10, EDGE_3 = 0.08, EDGE_5 = 0.05;
    /** Half the width of the soft edge, in key widths. See "Edges are soft on purpose". */
    private static final double EDGE_SOFT = 0.5;

    /** Half-height of the shelf's jagged edge blend, in rows. */
    private static final double SHELF_SOFT = 0.35;
    /** Most a floe sways off its spot, in keys or rows. Placement spacing allows for it. */
    private static final double SWAY = 0.15;

    // The rolled layout, in keys across and rows down, relative to the strip
    // before it has slid anywhere. Rolled against whatever board turns up
    // first and rolled again if the board changes, same as the swamp's pads.
    private KeyGrid layoutFor;
    private double lap;
    private double offBoard;
    private double[] homeX = new double[0], homeY = new double[0];
    private double[] ha = new double[0], hb = new double[0];

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor water = ctx.baseColor();
        RGBColor ice = ctx.resolvedAccentColor();
        // Water catching light: its own hue, nudged towards the ice and taken
        // to full brightness. The swamp found out the hard way that "dark
        // water, slightly more of it" is invisible; same trick as there.
        RGBColor sheen = atFull(water.lerp(ice, 0.35));
        RGBColor glint = ice.lightened(0.55);

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        // The real last row, not a rounded one. Boards with a gap under the
        // F-row put it half a row off the grid.
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        if (layoutFor != grid) rollLayout(grid, widthKeys, lastRow);

        // Where every floe is this frame. A handful of floes against a
        // hundred-odd keys, so this is resolved once up front.
        int count = homeX.length;
        double[] cx = new double[count], cy = new double[count];
        double[] p2 = new double[count], p3 = new double[count], p5 = new double[count];
        double[] bob = new double[count];
        double slide = speed * t;
        for (int f = 0; f < count; f++) {
            double swayX = SWAY * Math.sin(TAU * t / (9 + 5 * hash(f, 4)) + TAU * hash(f, 5));
            double swayY = SWAY * Math.sin(TAU * t / (7 + 4 * hash(f, 6)) + TAU * hash(f, 7));
            cx[f] = (homeX[f] + slide) % lap - offBoard + swayX;
            cy[f] = homeY[f] + swayY;
            p2[f] = TAU * hash(f, 8);
            p3[f] = TAU * hash(f, 9);
            p5[f] = TAU * hash(f, 10);
            bob[f] = FLOE_BOB * Math.sin(TAU * t / (3 + 2 * hash(f, 11)) + TAU * hash(f, 12));
        }

        // Glints, each pinned to a spot on one floe for its short life.
        double[] glintX = new double[GLINTS], glintY = new double[GLINTS], glintAmt = new double[GLINTS];
        for (int g = 0; g < GLINTS && count > 0; g++) {
            double phase = t / GLINT_PERIOD + (double) g / GLINTS;
            int cycle = (int) Math.floor(phase);
            double age = (phase - cycle) * GLINT_PERIOD;
            if (age >= GLINT_SECONDS) continue;
            int roll = g * 977 + cycle;
            int f = (int) (hash(roll, 13) * count) % count;
            glintX[g] = cx[f] + (hash(roll, 14) - 0.5) * 1.2 * ha[f];
            glintY[g] = cy[f] + (hash(roll, 15) - 0.5) * 1.2 * hb[f];
            glintAmt[g] = Math.sin(Math.PI * age / GLINT_SECONDS);
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;

            // --- which floe, if any, is over this key ------------------
            double cover = 0, lapping = 0;
            int owner = -1;
            for (int f = 0; f < count; f++) {
                double dx = col - cx[f];
                double dy = rowF - cy[f];
                if (Math.abs(dx) > ha[f] * 1.3 + LAP_REACH + EDGE_SOFT) continue;
                if (Math.abs(dy) > hb[f] * 1.3 + LAP_REACH + EDGE_SOFT) continue;
                double nx = dx / ha[f], ny = dy / hb[f];
                double d = Math.sqrt(nx * nx + ny * ny);
                double theta = Math.atan2(ny, nx);
                double edge = 1 + EDGE_2 * Math.sin(2 * theta + p2[f])
                        + EDGE_3 * Math.sin(3 * theta + p3[f])
                        + EDGE_5 * Math.sin(5 * theta + p5[f]);
                // Distance to the edge in keys rather than in the floe's
                // stretched units, so a long thin floe gets the same soft edge
                // along its sides as at its ends.
                double polar = Math.hypot(ha[f] * Math.cos(theta), hb[f] * Math.sin(theta));
                double inside = (edge - d) * polar;
                // Biased outward a touch, so a floe comes out at its nominal
                // size rather than losing half a key all the way round.
                double c = smoothstep(-EDGE_SOFT - 0.1, EDGE_SOFT - 0.15, inside);
                if (c > cover) {
                    cover = c;
                    owner = f;
                }
                if (inside < 0) lapping = Math.max(lapping, 1 + inside / LAP_REACH);
            }

            // --- shelf ice, river only ---------------------------------
            double shelf = 0;
            if (river) {
                // Reaches from not at all to over a row out, so the shelf is
                // broken along its length. A shelf with no gaps in it is a
                // lit border round the board.
                double topReach = -1.0 + 2.4 * valueNoise(col * 0.7 + seed * 3.1, 1.7);
                double bottomReach = -1.0 + 2.4 * valueNoise(col * 0.7 + seed * 5.9, 8.3);
                shelf = Math.max(
                        smoothstep(SHELF_SOFT, -SHELF_SOFT, rowF - topReach),
                        smoothstep(-SHELF_SOFT, SHELF_SOFT, rowF - (lastRow - bottomReach)));
            }

            // --- the water ---------------------------------------------
            double ripple;
            if (river) {
                // Streaks stretched along the flow and carried by it, at the
                // speed the ice is going.
                ripple = STREAK_DEPTH * (2 * valueNoise((col - slide) * 0.35 + seed, rowF * 1.6) - 1);
            } else {
                // Two swells at odd angles and periods, so the water never
                // settles into one obvious travelling stripe.
                ripple = SWELL_DEPTH * (0.6 * Math.sin(TAU * (col * 0.16 + rowF * 0.10 - t / 7.3))
                        + 0.4 * Math.sin(TAU * (col * 0.07 - rowF * 0.19 - t / 11.1) + 1.3));
            }
            double openWater = (1 - cover) * (1 - shelf);
            if (openWater > 0) {
                budget.add(key.ref(), water, openWater * Math.max(0.12, waterLevel + ripple));
                double lapLight = LAP_LEVEL * lapping * (0.75 + 0.25 * Math.sin(TAU * t / 2.3 + col * 0.8));
                if (lapLight > 0) budget.add(key.ref(), sheen, openWater * lapLight);
            }

            // --- the ice -----------------------------------------------
            if (cover > 0) {
                int f = owner;
                // Pinned to the floe: sampled in its own coordinates, offset
                // per floe so no two share a surface.
                double tex = valueNoise((col - cx[f]) * 0.9 + f * 17.3 + seed, (rowF - cy[f]) * 1.3 + f * 5.1);
                double level = FLOE_LEVEL + FLOE_TEXTURE * (2 * tex - 1) + bob[f];
                budget.add(key.ref(), ice, cover * (1 - shelf) * level);
            }
            if (shelf > 0) {
                double tex = valueNoise(col * 0.8 + seed * 2.3, rowF * 1.4 + 4.4);
                budget.add(key.ref(), ice, shelf * (SHELF_LEVEL + 0.16 * (2 * tex - 1)));
            }

            if (cover > 0.5) {
                double sparkle = 0;
                for (int g = 0; g < GLINTS; g++) {
                    if (glintAmt[g] <= 0) continue;
                    double gx = col - glintX[g], gy = rowF - glintY[g];
                    if (gx * gx + gy * gy < 0.36) sparkle = Math.max(sparkle, glintAmt[g]);
                }
                if (sparkle > 0) budget.add(key.ref(), glint, GLINT_LEVEL * sparkle * cover);
            }
        }

        return budget.resolve();
    }

    /**
     * Scatters the floes over the strip. Random spots, and a spot is thrown
     * away if its floe would come within a key or so of one already placed,
     * measured round the wrap so the last floe can't land on top of the first.
     *
     * <p>Floes may hang off the top and bottom of the board. A field of ice
     * where every floe sits neatly inside the frame looks arranged, and a sea
     * that has been arranged is not a sea.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double lastRow) {
        offBoard = halfWidthMax * 1.3 + 1.0;
        lap = widthKeys + 2 * offBoard;
        // On the river the floes stay in the channel between the shelves.
        double top = river ? 1.5 : -0.1;
        double bottom = river ? lastRow - 1.4 : lastRow + 0.1;

        double[] xs = new double[floeCount], ys = new double[floeCount];
        double[] as = new double[floeCount], bs = new double[floeCount];
        int placed = 0;
        for (int attempt = 0; attempt < 400 && placed < floeCount; attempt++) {
            double a = lerp(halfWidthMin, halfWidthMax, hash(attempt, 30));
            double b = lerp(halfHeightMin, halfHeightMax, hash(attempt, 31));
            double x = hash(attempt, 32) * lap;
            double y = lerp(top, bottom, hash(attempt, 33));
            boolean clear = true;
            for (int o = 0; o < placed && clear; o++) {
                double dx = Math.abs(x - xs[o]);
                dx = Math.min(dx, lap - dx);
                double dy = Math.abs(y - ys[o]);
                // Bounding boxes with room for the lumpy edge, the sway on
                // both floes, and some water between them.
                double gapX = (a + as[o]) * 1.15 + 2 * SWAY + 0.6;
                double gapY = (b + bs[o]) * 1.15 + 2 * SWAY + 0.2;
                if (dx < gapX && dy < gapY) clear = false;
            }
            if (!clear) continue;
            xs[placed] = x;
            ys[placed] = y;
            as[placed] = a;
            bs[placed] = b;
            placed++;
        }
        homeX = Arrays.copyOf(xs, placed);
        homeY = Arrays.copyOf(ys, placed);
        ha = Arrays.copyOf(as, placed);
        hb = Arrays.copyOf(bs, placed);
        layoutFor = grid;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Same hue, scaled up until the brightest channel is at 255. */
    private static RGBColor atFull(RGBColor c) {
        int max = Math.max(c.r(), Math.max(c.g(), c.b()));
        if (max == 0) return c;
        double k = 255.0 / max;
        return new RGBColor((int) Math.round(c.r() * k), (int) Math.round(c.g() * k), (int) Math.round(c.b() * k));
    }

    private double hash(int n, int salt) {
        return latticeHash(n * 7919 + seed * 131, salt * 104729 + 17);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return v * v * (3 - 2 * v);
    }

    /** Same lattice hash the canopy and the swamp use; see CanopyDapplePattern for the notes. */
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
}
