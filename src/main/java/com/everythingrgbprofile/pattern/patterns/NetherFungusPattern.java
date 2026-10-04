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
 * The Nether's two forests: huge fungi, shroomlights glowing in their caps,
 * and vines. Crimson and warped, which are the same forest in opposite
 * colours with their vines growing opposite ways.
 *
 * <p>Replaces {@code ember-rise} on the crimson forest and {@code spore-drift}
 * on the warped one. Particle fields, the pair of them, which is a strange way
 * to draw the only two biomes in the game that are forests made of giant
 * mushrooms.
 *
 * <h2>What's on the board</h2>
 * <ul>
 *   <li><b>Huge fungi,</b> side on. Wide blocky caps with a skirt drooping at
 *       each edge, up on thick stems. The caps run off the top of the board,
 *       because the real ones are taller than the ceiling of most screenshots
 *       too.</li>
 *   <li><b>Shroomlights</b> set into the caps: warm orange blocks that glow
 *       and spill light around them. In the game they're the forest's main
 *       light source, so here they're the brightest thing on the board.</li>
 *   <li><b>Nylium</b> along the bottom row, in the forest's own colour.</li>
 *   <li><b>A few spores</b> drifting about, the way both biomes are full of
 *       them. Deliberately few. They used to be the whole effect, and now
 *       they're a garnish.</li>
 * </ul>
 *
 * <h2>Where the two split</h2>
 * The game mirrors these forests, and so does this, one step further than the
 * game does: the spores here follow the vines. (In the game the crimson ones
 * hang nearly still and the warped ones sink.)
 * <ul>
 *   <li><b>Crimson</b> has weeping vines hanging down under the caps, swaying,
 *       with a brighter bud on each tip. Its spores drift down.</li>
 *   <li><b>Warped</b> has twisting vines growing up out of the floor, with a
 *       wiggle that climbs them like they're twisting. Its spores drift up.
 *       And it has endermen, because the warped forest is where they spawn
 *       the most: every so often a pair of purple eyes opens in the dark,
 *       blinks, and vanishes in a burst of purple teleport particles. It's
 *       very funny the first time and slightly unnerving every time after.</li>
 * </ul>
 */
public final class NetherFungusPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** Crimson forest: weeping vines hanging, spores falling. */
    public static NetherFungusPattern crimsonForest() {
        return new NetherFungusPattern(false, ColorPalette.CRIMSON_STEM, ColorPalette.CRIMSON_VINE, ColorPalette.CRIMSON_SPORE, 47);
    }

    /** Warped forest: twisting vines rising, spores rising, endermen. */
    public static NetherFungusPattern warpedForest() {
        return new NetherFungusPattern(true, ColorPalette.WARPED_STEM, ColorPalette.WARPED_VINE, ColorPalette.WARPED_SPORE, 53);
    }

    /** Warped: vines grow up from the floor, spores rise, endermen turn up. Crimson does the opposite. */
    private final boolean warped;
    private final RGBColor stemColour, vineColour, sporeColour;
    private final int seed;

    private NetherFungusPattern(boolean warped, RGBColor stemColour, RGBColor vineColour,
                                RGBColor sporeColour, int seed) {
        this.warped = warped;
        this.stemColour = stemColour;
        this.vineColour = vineColour;
        this.sporeColour = sporeColour;
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    /** The air between the fungi. Tinted with the forest's colour, never black. */
    private static final double AIR_LEVEL = 0.22;
    private static final double NYLIUM_LEVEL = 0.55;
    private static final double CAP_LEVEL = 0.70;
    private static final double STEM_LEVEL = 0.60;
    private static final double SHROOMLIGHT_LEVEL = 0.95;
    private static final double SHROOMLIGHT_SPILL = 0.30;
    private static final double VINE_LEVEL = 0.70;
    private static final double SPORE_LEVEL = 0.55;

    // --- motion --------------------------------------------------------

    private static final double VINE_SWAY = 0.3;
    private static final int SPORES = 6;
    /** Spore speed, rows per second. Slow. They're floating, not falling. */
    private static final double SPORE_SPEED = 0.45;

    // --- endermen, warped only ------------------------------------------

    private static final double ENDER_EVERY = 11.0;
    /** When in the cycle the eyes open, so the first one doesn't greet you on arrival. */
    private static final double ENDER_START = 4.0;
    private static final double ENDER_FADE_IN = 0.4;
    private static final double ENDER_HOLD = 2.2;
    /** The blink: eyes shut for this long, this far into the hold. */
    private static final double BLINK_AT = 1.1, BLINK_SECONDS = 0.15;
    private static final double POOF_SECONDS = 0.7;
    private static final int POOF_PARTICLES = 6;

    private KeyGrid layoutFor;
    /** {x, capBottom, capHalfWidth, stemHalfWidth} */
    private final List<double[]> fungi = new ArrayList<>();
    /** {x, elev} per shroomlight. */
    private final List<double[]> shroomlights = new ArrayList<>();
    /** {x, rootElev, length, phase} per vine. Root is the cap underside for crimson, the floor for warped. */
    private final List<double[]> vines = new ArrayList<>();

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor cap = ctx.baseColor();
        RGBColor shroom = ctx.resolvedAccentColor();

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

        // Spores: each loops through the board on its own track.
        double[] sporeX = new double[SPORES], sporeE = new double[SPORES];
        for (int s = 0; s < SPORES; s++) {
            double travel = boardHeight + 1;
            double pos = (t * SPORE_SPEED * (0.7 + 0.6 * hash(s, 60)) + hash(s, 61) * travel) % travel;
            sporeE[s] = warped ? pos - 0.5 : boardHeight - pos;
            sporeX[s] = hash(s, 62) * widthKeys + 0.8 * Math.sin(TAU * t / (5 + 3 * hash(s, 63)) + TAU * hash(s, 64));
        }

        // The enderman, if there is one right now.
        double eyesAmt = 0, eyeX = 0, eyeE = 0, poofAge = -1;
        if (warped) {
            int cycle = (int) Math.floor(t / ENDER_EVERY);
            double age = t - cycle * ENDER_EVERY - ENDER_START;
            eyeX = 1.5 + hash(cycle, 70) * (widthKeys - 4);
            eyeE = lerp(2.0, boardHeight - 2.6, hash(cycle, 71));
            if (age >= 0 && age < ENDER_FADE_IN + ENDER_HOLD) {
                eyesAmt = smoothstep(0, ENDER_FADE_IN, age);
                double held = age - ENDER_FADE_IN;
                if (held > BLINK_AT && held < BLINK_AT + BLINK_SECONDS) eyesAmt = 0;
            } else if (age >= ENDER_FADE_IN + ENDER_HOLD && age < ENDER_FADE_IN + ENDER_HOLD + POOF_SECONDS) {
                poofAge = age - ENDER_FADE_IN - ENDER_HOLD;
            }
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double row = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - row;
            KeyGrid.LedRef ref = key.ref();

            // --- fungi -----------------------------------------------
            double capCover = 0, stemCover = 0;
            for (double[] f : fungi) {
                double dx = col - f[0];
                double u = Math.abs(dx) / f[2];
                if (u < 1.25) {
                    // Blocky cap with the edges drooping into a skirt.
                    double bottom = f[1] - 0.8 * smoothstep(0.6, 1.0, u)
                            + 0.25 * (valueNoise(col * 1.3 + seed, f[0]) - 0.5);
                    double c = smoothstep(bottom - 0.3, bottom + 0.3, elev) * smoothstep(1.15, 0.95, u);
                    capCover = Math.max(capCover, c);
                }
                if (elev < f[1] + 0.2) {
                    stemCover = Math.max(stemCover, smoothstep(f[3] + 0.3, f[3] - 0.1, Math.abs(dx)));
                }
            }
            stemCover *= 1 - capCover;

            // --- vines -----------------------------------------------
            double vine = 0, bud = 0;
            for (double[] v : vines) {
                double along = warped ? elev - v[1] : v[1] - elev;   // distance from the root
                if (along < -0.2 || along > v[2] + 0.3) continue;
                double frac = Math.max(0, along) / v[2];
                double sway;
                if (warped) {
                    // A wiggle that climbs the vine, so it looks twisted.
                    sway = VINE_SWAY * Math.sin(TAU * (frac * 1.2 - t / 4.0) + v[3]) * (0.3 + 0.7 * frac);
                } else {
                    // Hanging: swings from the root, more at the tip.
                    sway = VINE_SWAY * Math.pow(frac, 1.3) * Math.sin(TAU * t / 3.4 + v[3]);
                }
                double c = smoothstep(0.55, 0.15, Math.abs(col - v[0] - sway))
                        * smoothstep(v[2] + 0.3, v[2] - 0.2, along);
                if (c > vine) {
                    vine = c;
                    bud = smoothstep(v[2] - 0.6, v[2], along);
                }
            }
            vine *= 1 - capCover;

            // --- floor -----------------------------------------------
            double nylium = smoothstep(0.95, 0.6, elev) * (1 - stemCover);

            // --- light it --------------------------------------------
            double air = (1 - capCover) * (1 - stemCover) * (1 - vine) * (1 - nylium);
            if (air > 0) budget.add(ref, cap, air * AIR_LEVEL);
            if (nylium > 0) {
                double texture = 0.85 + 0.15 * valueNoise(col * 1.5 + seed, 3.3);
                budget.add(ref, cap, nylium * NYLIUM_LEVEL * texture);
            }
            if (stemCover > 0) budget.add(ref, stemColour, stemCover * STEM_LEVEL);
            if (capCover > 0) {
                double texture = 0.85 + 0.15 * valueNoise(col * 1.2 + seed, row * 1.2);
                budget.add(ref, cap, capCover * CAP_LEVEL * texture);
            }
            if (vine > 0) {
                budget.add(ref, vineColour, vine * VINE_LEVEL);
                if (bud > 0) budget.add(ref, shroom, vine * bud * 0.35);
            }

            // --- shroomlights ----------------------------------------
            double light = 0, spill = 0;
            for (double[] s : shroomlights) {
                double d2 = sq(col - s[0]) + sq((elev - s[1]) * 1.2);
                light = Math.max(light, smoothstep(0.7, 0.25, Math.sqrt(d2)));
                spill = Math.max(spill, Math.exp(-d2 / 3.5));
            }
            if (light > 0) {
                double flicker = 0.9 + 0.1 * Math.sin(TAU * t / 2.7 + col);
                budget.add(ref, shroom, light * SHROOMLIGHT_LEVEL * flicker);
            }
            if (spill > 0.02) budget.add(ref, shroom, spill * SHROOMLIGHT_SPILL * (1 - light));

            // --- spores ----------------------------------------------
            for (int s = 0; s < SPORES; s++) {
                double d = Math.hypot(col - sporeX[s], elev - sporeE[s]);
                double a = smoothstep(0.6, 0.15, d);
                if (a > 0) budget.add(ref, sporeColour, SPORE_LEVEL * a);
            }

            // --- the enderman ----------------------------------------
            if (eyesAmt > 0) {
                // Two eyes, one key apart. Endermen don't do subtle.
                double e = Math.max(
                        smoothstep(0.5, 0.15, Math.hypot(col - (eyeX - 0.5), elev - eyeE)),
                        smoothstep(0.5, 0.15, Math.hypot(col - (eyeX + 0.5), elev - eyeE)));
                if (e > 0) budget.add(ref, ColorPalette.ENDER_EYE, 0.95 * eyesAmt * e);
            }
            if (poofAge >= 0) {
                double f = poofAge / POOF_SECONDS;
                for (int p = 0; p < POOF_PARTICLES; p++) {
                    double angle = TAU * p / POOF_PARTICLES + 0.4;
                    double r = 0.3 + 1.8 * f;
                    double px = eyeX + r * Math.cos(angle);
                    double pe = eyeE + 0.7 * r * Math.sin(angle);
                    double a = smoothstep(0.55, 0.15, Math.hypot(col - px, elev - pe)) * (1 - f);
                    if (a > 0) budget.add(ref, ColorPalette.ENDER_PARTICLE, 0.85 * a);
                }
            }
        }

        return budget.resolve();
    }

    /**
     * Grows the forest: fungi in slots across the board, shroomlights set
     * into each cap, then vines. Crimson vines hang off the cap undersides.
     * Warped vines come up from the floor in the gaps between stems.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double boardHeight) {
        fungi.clear();
        shroomlights.clear();
        vines.clear();
        int count = 3;
        double slot = (widthKeys + 2) / count;
        for (int i = 0; i < count; i++) {
            double x = -1 + slot * (i + 0.3 + 0.4 * hash(i, 10));
            double capBottom = lerp(boardHeight - 2.6, boardHeight - 1.9, hash(i, 11));
            double half = lerp(2.6, 3.4, hash(i, 12));
            fungi.add(new double[]{x, capBottom, half, lerp(0.45, 0.65, hash(i, 13))});
            int lights = 2 + (int) (hash(i, 14) * 2);
            for (int l = 0; l < lights; l++) {
                double lx = x + (hash(i * 10 + l, 15) - 0.5) * 1.6 * half;
                double le = capBottom + lerp(0.5, 1.6, hash(i * 10 + l, 16));
                shroomlights.add(new double[]{lx, le});
            }
            if (!warped) {
                int hanging = 2 + (int) (hash(i, 17) * 3);
                for (int v = 0; v < hanging; v++) {
                    double vx = x + (hash(i * 10 + v, 18) - 0.5) * 1.7 * half;
                    if (Math.abs(vx - x) < 0.9) vx += 1.2;   // not through the stem
                    vines.add(new double[]{vx, capBottom - 0.2, lerp(1.0, 2.4, hash(i * 10 + v, 19)), TAU * hash(i * 10 + v, 20)});
                }
            }
        }
        if (warped) {
            double x = 0.6 + hash(0, 30) * 1.5;
            for (int v = 0; x < widthKeys + 0.5 && v < 32; v++) {
                boolean clear = true;
                for (double[] f : fungi) if (Math.abs(x - f[0]) < f[3] + 0.8) clear = false;
                if (clear) vines.add(new double[]{x, 0.6, lerp(1.4, 3.0, hash(v, 31)), TAU * hash(v, 32)});
                x += lerp(1.6, 3.2, hash(v, 33));
            }
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
