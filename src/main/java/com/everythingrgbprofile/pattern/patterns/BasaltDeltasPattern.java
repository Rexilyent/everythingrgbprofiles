package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Basalt deltas: jagged basalt columns in smoky air, magma glowing in the
 * floor, and ash drifting down through all of it.
 *
 * <p>Replaces {@code ember-rise} in grey, which did a decent impression of ash
 * rising, except that in the game the deltas' white ash drifts <i>down</i>.
 * So the ash got turned the right way up, and some actual basalt got put in
 * for it to fall past.
 *
 * <h2>What's on the board</h2>
 * <ul>
 *   <li><b>Columns.</b> Basalt pillars at uneven heights, flat-topped, some
 *       running right up off the top of the board. Faint vertical joints in
 *       them, because basalt cools into columns and the joints are what make
 *       it basalt and not just a dark rectangle.</li>
 *   <li><b>Magma.</b> Patches along the floor, pulsing slowly, the way magma
 *       blocks do in the game. They light up the foot of every column near
 *       them, which is the only light in the scene.</li>
 *   <li><b>Ash,</b> falling slowly and swaying as it goes. Pale, but dim,
 *       because a bright pale key is a light switched on, not a flake of
 *       ash. The basalt is the profile colour; the ash is a fixed one.</li>
 *   <li><b>Smoke.</b> The air between the columns is a dim violet-grey haze,
 *       slowly stirring.</li>
 * </ul>
 */
public final class BasaltDeltasPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** The basalt deltas. */
    public static BasaltDeltasPattern basaltDeltas() {
        return new BasaltDeltasPattern(59);
    }

    private final int seed;

    private BasaltDeltasPattern(int seed) {
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    private static final double SMOKE_LEVEL = 0.38;
    private static final double BASALT_LEVEL = 0.72;
    /** How much the joints darken the column face. */
    private static final double JOINT_DEPTH = 0.18;
    private static final double MAGMA_LEVEL = 1.0;
    /** How strongly the magma lights the columns, and how fast that falls off with height. */
    private static final double UPLIGHT = 0.45, UPLIGHT_FALLOFF = 1.1;
    private static final double ASH_LEVEL = 0.50;

    // --- motion --------------------------------------------------------

    private static final double MAGMA_PULSE_SECONDS = 3.2;
    private static final int ASH = 9;

    private KeyGrid layoutFor;
    /** {left, right, height} per column, in keys and rows. */
    private final List<double[]> columns = new ArrayList<>();

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor basalt = ctx.baseColor();
        RGBColor magma = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        double boardHeight = lastRow + 1;
        if (layoutFor != grid) rollLayout(grid, widthKeys, boardHeight);

        // Ash: each flake loops top to bottom on its own track and speed.
        double[] ashX = new double[ASH], ashE = new double[ASH];
        for (int a = 0; a < ASH; a++) {
            double travel = boardHeight + 1;
            double speed = lerp(0.45, 0.85, hash(a, 60));
            double pos = (t * speed + hash(a, 61) * travel) % travel;
            ashE[a] = boardHeight - pos;
            ashX[a] = hash(a, 62) * widthKeys + 0.7 * Math.sin(TAU * t / (3.5 + 2 * hash(a, 63)) + TAU * hash(a, 64));
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double row = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - row;
            KeyGrid.LedRef ref = key.ref();

            // --- columns ---------------------------------------------
            double stone = 0;
            int owner = -1;
            for (int i = 0; i < columns.size(); i++) {
                double[] c = columns.get(i);
                double across = Math.min(col - c[0], c[1] - col);   // positive inside
                double s = smoothstep(-0.35, 0.25, across) * smoothstep(c[2] + 0.3, c[2] - 0.3, elev);
                if (s > stone) {
                    stone = s;
                    owner = i;
                }
            }

            // --- magma in the floor ----------------------------------
            double floor = smoothstep(0.95, 0.6, elev);
            double patch = smoothstep(0.36, 0.58, valueNoise(col * 0.45 + seed, 2.9));
            double pulse = 0.75 + 0.25 * Math.sin(TAU * t / MAGMA_PULSE_SECONDS + col * 0.6);
            double magmaHere = floor * patch;
            // How much magma is near this column, for lighting it from below.
            double nearMagma = smoothstep(0.30, 0.58, valueNoise(col * 0.45 + seed, 2.9));
            double uplight = UPLIGHT * nearMagma * pulse * Math.exp(-UPLIGHT_FALLOFF * Math.max(0, elev - 0.6));

            // --- light it --------------------------------------------
            double air = (1 - stone) * (1 - floor);
            if (air > 0) {
                double stir = 0.8 + 0.2 * valueNoise(col * 0.3 + t * 0.15, elev * 0.5 - t * 0.08 + seed);
                budget.add(ref, ColorPalette.BASALT_SMOKE, air * SMOKE_LEVEL * stir);
                if (uplight > 0.02) budget.add(ref, magma, air * uplight * 0.4);
            }
            if (stone > 0) {
                double[] c = columns.get(owner);
                // Vertical joints, about one per key, offset per column so
                // neighbours don't line up into one big grid.
                double joints = 1 - JOINT_DEPTH * smoothstep(0.6, 0.95, Math.abs(Math.sin(Math.PI * (col - c[0]) * 0.9 + owner)));
                // Flat tops catch a bit more light than the faces.
                double top = smoothstep(c[2] - 0.7, c[2] - 0.1, elev);
                budget.add(ref, basalt, stone * BASALT_LEVEL * joints * (1 + 0.25 * top));
                if (uplight > 0.02) budget.add(ref, magma, stone * uplight);
            }
            if (floor > 0) {
                budget.add(ref, ColorPalette.BLACKSTONE, floor * (1 - patch) * 0.45 * (1 - stone));
                if (magmaHere > 0) {
                    double crack = 0.8 + 0.2 * valueNoise(col * 1.6 + seed, t * 0.4);
                    budget.add(ref, magma, magmaHere * MAGMA_LEVEL * pulse * crack * (1 - stone));
                }
            }

            // --- ash -------------------------------------------------
            for (int a = 0; a < ASH; a++) {
                double d = Math.hypot(col - ashX[a], elev - ashE[a]);
                double s = smoothstep(0.55, 0.15, d);
                if (s > 0) budget.add(ref, ColorPalette.BASALT_ASH, ASH_LEVEL * s);
            }
        }

        return budget.resolve();
    }

    /**
     * Stands the columns up: walking left to right, each a key or two wide,
     * heights all over the place, with gaps of smoke between some of them.
     * Real deltas are crowded, so the gaps are narrow and not every column
     * gets one.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double boardHeight) {
        columns.clear();
        double x = -0.5 + hash(0, 10);
        for (int i = 0; x < widthKeys + 0.5 && i < 48; i++) {
            double width = lerp(1.0, 2.6, hash(i, 11));
            // Mostly mid-height, with the odd one running off the top.
            double height = hash(i, 12) < 0.2 ? boardHeight + 1 : lerp(1.6, boardHeight - 1.2, hash(i, 13));
            columns.add(new double[]{x, x + width, height});
            x += width + (hash(i, 14) < 0.6 ? lerp(0.9, 2.2, hash(i, 15)) : 0.2);
        }
        layoutFor = grid;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

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
