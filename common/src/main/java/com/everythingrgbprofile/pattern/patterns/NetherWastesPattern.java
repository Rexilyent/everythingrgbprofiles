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
 * Nether wastes: glowstone hanging off the ceiling, a lava sea along the
 * bottom, and a lot of netherrack in between trying its best.
 *
 * <p>Replaces {@code ember-rise}, which it shared with the crimson forest and,
 * in grey, the basalt deltas. Three biomes, one particle field, three tints.
 * The Nether's whole deal is that it's a cave the size of a dimension, ceiling
 * and floor both, and a field of embers was using neither.
 *
 * <h2>What's on the board</h2>
 * <ul>
 *   <li><b>Glowstone</b> in lumpy clusters hanging from the top rows, warm
 *       yellow, each key flickering a little on its own. It spills a bit of
 *       light onto the netherrack around it, which is what makes it read as a
 *       light source rather than a yellow sticker.</li>
 *   <li><b>Netherrack</b> filling the middle. Dim, rough, dark red. It's the
 *       background, and it behaves like one.</li>
 *   <li><b>The lava sea</b> across the bottom rows, churning slowly between
 *       bright and dark crust. The surface heaves a little, and heat haze
 *       wavers in the netherrack just above it.</li>
 *   <li><b>Pops.</b> Every so often a bubble swells on the surface, bursts,
 *       and flings a spark up in an arc that falls back. The game does exactly
 *       this with lava particles, and it's the one bit of sudden movement in an
 *       otherwise slow scene, which is why it lands.</li>
 * </ul>
 *
 * <h2>Coordinates</h2>
 * Rows up from just under the bottom row for the lava and the sparks, rows
 * down from the top for the glowstone. Both are worked out per key; each
 * layer uses whichever way round it hangs.
 */
public final class NetherWastesPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** The nether wastes. */
    public static NetherWastesPattern netherWastes() {
        return new NetherWastesPattern(43);
    }

    private final int seed;

    private NetherWastesPattern(int seed) {
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    private static final double ROCK_LEVEL = 0.34;
    private static final double ROCK_TEXTURE = 0.12;
    private static final double GLOWSTONE_LEVEL = 0.85;
    private static final double GLOW_SPILL = 0.22;
    private static final double LAVA_LEVEL = 0.85;

    // --- lava ----------------------------------------------------------

    /** Height of the lava surface, in rows up from under the bottom row. */
    private static final double LAVA_TOP = 1.45;
    private static final double HEAVE = 0.12;
    /** How far above the lava the heat haze reaches, in rows. */
    private static final double HAZE_HEIGHT = 1.6;
    private static final double HAZE_DEPTH = 0.30;

    // --- pops ----------------------------------------------------------

    /** Independent pop sources, each going off once per its own period. */
    private static final int POPS = 3;
    private static final double POP_PERIOD = 3.1;
    private static final double SWELL_SECONDS = 0.45;
    private static final double FLIGHT_SECONDS = 1.0;
    /** Spark launch speed and gravity, rows per second and rows per second squared. */
    private static final double SPARK_SPEED = 6.0, SPARK_GRAVITY = 11.0;

    private KeyGrid layoutFor;
    /** {x, halfWidth, depth} */
    private final List<double[]> clusters = new ArrayList<>();

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor rockColour = ctx.baseColor();
        RGBColor lavaBright = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        if (layoutFor != grid) rollLayout(grid, widthKeys);

        // The pops this frame: where each one is in its swell or its flight.
        double[] popX = new double[POPS], swell = new double[POPS];
        double[] sparkX = new double[POPS], sparkE = new double[POPS], sparkAmt = new double[POPS];
        for (int p = 0; p < POPS; p++) {
            double phase = t / POP_PERIOD + (double) p / POPS + 0.13 * p;
            int cycle = (int) Math.floor(phase);
            double age = (phase - cycle) * POP_PERIOD;
            int roll = p * 977 + cycle;
            popX[p] = 1 + hash(roll, 50) * (widthKeys - 2);
            if (age < SWELL_SECONDS) {
                swell[p] = age / SWELL_SECONDS;
            } else if (age < SWELL_SECONDS + FLIGHT_SECONDS) {
                double s = age - SWELL_SECONDS;
                double drift = (hash(roll, 51) - 0.5) * 2.4;
                sparkX[p] = popX[p] + drift * s;
                sparkE[p] = LAVA_TOP + SPARK_SPEED * s - 0.5 * SPARK_GRAVITY * s * s;
                // Fades as it cools, and dies when it drops back into the lava.
                sparkAmt[p] = (1 - s / FLIGHT_SECONDS) * (sparkE[p] > LAVA_TOP - 0.2 ? 1 : 0);
            }
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double row = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - row;
            KeyGrid.LedRef ref = key.ref();

            // --- glowstone -------------------------------------------
            double glow = 0, spill = 0;
            for (double[] c : clusters) {
                double dx = (col - c[0]) / c[1];
                double reach = c[2] * (1 - dx * dx) + 0.5 * (valueNoise(col * 1.7 + seed, 2.1) - 0.5);
                double g = smoothstep(reach + 0.3, reach - 0.2, row) * smoothstep(1.2, 0.9, Math.abs(dx));
                glow = Math.max(glow, g);
                double dist2 = sq(col - c[0]) / (c[1] * c[1] + 1) + sq(row - c[2] * 0.5) / 2.0;
                spill = Math.max(spill, Math.exp(-dist2));
            }

            // --- lava ------------------------------------------------
            double surface = LAVA_TOP + HEAVE * Math.sin(TAU * (col * 0.11 + t * 0.21))
                    + HEAVE * 0.6 * Math.sin(TAU * (col * 0.27 - t * 0.13) + 1.4);
            for (int p = 0; p < POPS; p++) {
                // The swelling bubble lifts the surface right under it.
                if (swell[p] > 0) surface += 0.5 * swell[p] * Math.exp(-sq((col - popX[p]) / 0.7));
            }
            double lava = smoothstep(surface + 0.3, surface - 0.3, elev);

            // --- netherrack ------------------------------------------
            double rock = (1 - glow) * (1 - lava);
            if (rock > 0) {
                double texture = 1 + ROCK_TEXTURE * (2 * valueNoise(col * 1.3 + seed, row * 1.3) - 1);
                double above = elev - surface;
                if (above < HAZE_HEIGHT) {
                    double waver = Math.sin(TAU * (col * 0.5 + t * 0.8)) * 0.6
                            + Math.sin(TAU * (col * 0.9 - t * 0.5) + 2.2) * 0.4;
                    texture *= 1 + HAZE_DEPTH * waver * smoothstep(HAZE_HEIGHT, 0.0, above);
                    // And the lava lights the rock right above it.
                    budget.add(ref, lavaBright, rock * 0.25 * smoothstep(HAZE_HEIGHT, 0.0, above));
                }
                budget.add(ref, rockColour, rock * ROCK_LEVEL * texture);
                if (spill > 0.02) budget.add(ref, ColorPalette.GLOWSTONE, rock * GLOW_SPILL * spill);
            }

            if (glow > 0) {
                double flicker = 0.85 + 0.15 * valueNoise(col * 2.3 + seed, t * 2.5 + row);
                budget.add(ref, ColorPalette.GLOWSTONE, glow * GLOWSTONE_LEVEL * flicker);
            }

            if (lava > 0) {
                // Two layers of churn at different speeds and scales, so the
                // crust breaks up and re-forms rather than sliding past.
                double churn = 0.6 * valueNoise(col * 0.45 + t * 0.12 + seed, elev * 0.9 - t * 0.08)
                        + 0.4 * valueNoise(col * 1.1 - t * 0.2, elev * 1.6 + t * 0.15 + 7.7);
                RGBColor c = ColorPalette.LAVA_CRUST.lerp(lavaBright, smoothstep(0.35, 0.7, churn));
                // The surface itself runs hot.
                double skin = smoothstep(surface - 0.7, surface, elev);
                budget.add(ref, c, lava * LAVA_LEVEL * (0.8 + 0.2 * skin));
                for (int p = 0; p < POPS; p++) {
                    if (swell[p] <= 0) continue;
                    double b = Math.exp(-sq((col - popX[p]) / 0.6)) * skin;
                    if (b > 0.02) budget.add(ref, ColorPalette.LAVA_SPARK, lava * 0.6 * swell[p] * b);
                }
            }

            // --- sparks ----------------------------------------------
            for (int p = 0; p < POPS; p++) {
                if (sparkAmt[p] <= 0) continue;
                double d = Math.hypot(col - sparkX[p], elev - sparkE[p]);
                double s = smoothstep(0.7, 0.2, d);
                if (s > 0) budget.add(ref, ColorPalette.LAVA_SPARK, 0.95 * sparkAmt[p] * s);
            }
        }

        return budget.resolve();
    }

    /** Hangs the glowstone: a few clusters spread out along the ceiling, with gaps of bare rock. */
    private void rollLayout(KeyGrid grid, double widthKeys) {
        clusters.clear();
        double x = 1.0 + 2.0 * hash(0, 10);
        for (int i = 0; x < widthKeys + 0.5 && i < 16; i++) {
            double half = lerp(1.0, 1.8, hash(i, 11));
            clusters.add(new double[]{x, half, lerp(0.9, 2.0, hash(i, 12))});
            x += half + lerp(3.0, 6.0, hash(i, 13));
        }
        layoutFor = grid;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private double hash(int n, int salt) {
        return latticeHash(n * 7919 + seed * 131, salt * 104729 + 17);
    }

    private static double sq(double x) {
        return x * x;
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
