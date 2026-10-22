package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Swamps: murky water under a canopy, lily pads, vines, and gas coming up
 * through the muck and rippling out across the surface.
 *
 * <p>Replaces a plain {@code shimmer} on both swamps, which was a lookup table
 * wearing a costume: the board went olive and breathed. Ask anyone what a swamp looks
 * like and not one of them says "olive, breathing".
 *
 * <h2>How the board is laid out</h2>
 * You're standing at the edge of it looking across. The top row is the canopy:
 * swamp oak, dark leaves, vines hanging off it. Everything below that is water,
 * and the water is where the scene happens.
 *
 * <ol>
 *   <li><b>Water.</b> Dark, murky, the base colour. Never black. A swamp is
 *       stagnant, not absent.</li>
 *   <li><b>Duckweed.</b> Patches of scum on the surface in the accent colour,
 *       circling very slowly, because stagnant water still turns over a
 *       little. Slow enough that you notice it has moved rather than watch it
 *       moving.</li>
 *   <li><b>Lily pads.</b> Single bright keys scattered over the water, each
 *       bobbing on its own rate. They are the thing that says "swamp" rather
 *       than "pond with the lights off", so they're the brightest steady
 *       element on the board.</li>
 *   <li><b>Vines.</b> Strands hanging from the canopy down into the top of
 *       the water rows, creeping longer and shorter. Fading as they hang,
 *       brightest where they leave the leaves.</li>
 *   <li><b>Gas.</b> This is the layer that keeps the board alive at a glance.
 *       Something surfaces from the mud every half second: a
 *       key swells as the bubble comes up, pops, and a ring runs out across
 *       the water from where it broke and fades. A ring crossing a lily pad
 *       lights the pad, which is the detail that makes the pads read as ON
 *       the water rather than painted over it.</li>
 * </ol>
 *
 * <h2>Why the motion is bubbles and not the water itself</h2>
 * The obvious move is making the water flow. Swamp water doesn't flow, it
 * sits, and a board full of moving water reads as a river. So the water
 * holds still and things happen TO it, which is what you actually see in a
 * swamp: the surface is flat right up until something breaks it.
 *
 * <p>Still has to be visibly alive, though, which the windswept biomes learned
 * the hard way (see {@code WindsweptRidgePattern}). The gas rate and the ring
 * life were tuned so 40-odd percent of keys swing noticeably in any given
 * second (41% for the swamp, 47% for the mangrove, as measured then, whose roots and scum give
 * the rings more to catch). Same neighbourhood as the windswept wind and the
 * Terraria reference it was measured against. Flat water, busy surface.
 *
 * <h2>It loops</h2>
 * Everything runs off one six-second cycle: the duckweed circles once, the
 * vines creep once, the pads bob a whole number of times, and the gas repeats
 * its twelve surfacing spots. So a GIF export of this has no seam. Six rather
 * than something longer because the tuner looks for a loop in the first eight
 * seconds and gives up after that, and twelve spots is already more than
 * anyone tracks by eye.
 */
public final class SwampBogPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** The whole scene repeats on this. Everything periodic below divides it. */
    private static final double LOOP_SECONDS = 6.0;

    // --- presets -------------------------------------------------------

    /** Swamp: the full set. Canopy, vines, pads, gas, and no roots. */
    public static SwampBogPattern swamp() {
        return new SwampBogPattern(5, 3, 9, 0, null, 0.45, 1);
    }

    /**
     * Mangrove swamp: roots arching up out of the water along the bottom rows,
     * fewer vines and pads because the roots are taking the space, and more
     * scum on water that's more mud than water.
     *
     * <p>The roots get their own fixed colour rather than one of the profile
     * slots. They're mangrove wood, which is a specific reddish brown, and a
     * profile only has two colours to spend: the water and the plants are
     * those two.
     */
    public static SwampBogPattern mangroveSwamp() {
        return new SwampBogPattern(3, 2, 5, 4, RGBColor.fromHex("#8A3414"), 0.60, 5);
    }

    private final int vineCount;
    /** Longest a vine hangs, in rows below the canopy. */
    private final int vineMaxRows;
    private final int padCount;
    private final int rootCount;
    private final RGBColor rootColor;
    /** How far a duckweed patch pulls the water towards the accent, 0..1. */
    private final double scumAmount;
    private final int seed;

    private SwampBogPattern(int vineCount, int vineMaxRows, int padCount, int rootCount,
                            RGBColor rootColor, double scumAmount, int seed) {
        this.vineCount = vineCount;
        this.vineMaxRows = vineMaxRows;
        this.padCount = padCount;
        this.rootCount = rootCount;
        this.rootColor = rootColor;
        this.scumAmount = scumAmount;
        this.seed = seed;
    }

    // --- levels, 0..1 --------------------------------------------------

    /**
     * Open water. Dim, but it has to read as water rather than as the board
     * being off: at 0.26 it came out near black on screen, and the pads looked
     * like they were floating on nothing.
     */
    private static final double WATER_LEVEL = 0.40;
    /** Brightness a duckweed patch adds on top of the water it sits on. */
    private static final double SCUM_LIFT = 0.10;
    /** The canopy row. Leaves in shade, so darker than the pads floating in the open. */
    private static final double CANOPY_LEVEL = 0.38;
    private static final double VINE_LEVEL = 0.50;
    private static final double PAD_LEVEL = 0.62;
    private static final double PAD_BOB = 0.10;
    private static final double ROOT_LEVEL = 0.50;

    // --- duckweed ------------------------------------------------------

    /** Noise cells per board width. Coarse, so a patch is a few keys across. */
    private static final double SCUM_SCALE = 3.2;
    /** Radius of the slow circle the whole scum field turns on, in noise cells. */
    private static final double SCUM_SWIRL = 0.5;

    // --- gas -----------------------------------------------------------

    /**
     * Independent surfacing points, each on {@link #GAS_PERIOD}. Six at three
     * seconds is one every half second somewhere on the board, staggered so
     * they never bunch up. Four, at one every 0.75s, left the board still for
     * long enough to look paused: about 10% of keys moving per second, against
     * the 40-odd it lands on now.
     */
    private static final int GAS_EMITTERS = 6;
    private static final double GAS_PERIOD = 3.0;
    /** Seconds from the bubble starting to rise to it breaking the surface. */
    private static final double GAS_RISE = 0.45;
    /** How far the ring travels per second, in key widths. */
    private static final double RING_SPEED = 3.2;
    /** Half-thickness of the ring, in key widths. */
    private static final double RING_HALF = 0.75;
    /** Seconds for the ring to lose about two thirds of its brightness. */
    private static final double RING_FADE = 1.3;
    private static final double RING_LEVEL = 0.70;
    /** The pop itself: brief, and the brightest thing on the board. */
    private static final double POP_LEVEL = 0.95;
    private static final double POP_SECONDS = 0.18;

    /**
     * Which keys are lily pads. Chosen once per grid and kept: a lily pad that
     * moves to a new key every frame isn't a lily pad, it's a glitch.
     *
     * <p>Rolled against whatever grid turns up first rather than in the
     * constructor, because the constructor doesn't know the board yet. If the
     * grid changes underneath (hardware reconnected, say) they get rolled
     * again for the new one.
     */
    private Set<KeyGrid.LedRef> pads = Set.of();
    private KeyGrid padsFor;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = (elapsedMillis / 1000.0) % LOOP_SECONDS;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor water = ctx.baseColor();
        RGBColor plant = ctx.resolvedAccentColor();
        // Bubble gas, a touch paler than the plants. Only lightened a little:
        // the pop is the one place white is earned, and even then an LED turns
        // a lot of white into "the key is broken" rather than "a bubble".
        RGBColor gas = plant.lightened(0.30);
        // Vines are the plant colour muddied towards the water. Lit in the
        // pad colour, a vine hanging into the water rows was indistinguishable
        // from a pad, and the vines vanished as a thing.
        RGBColor vine = plant.lerp(water, 0.40);
        // Ripples are the water catching light: the water's own hue, pushed
        // to full brightness. Lit in the water colour as it comes from the
        // profile, a ring was invisible, because swamp water is supposed to be
        // dark and "dark, but more" is still dark. Measured, 1% of keys
        // changed noticeably per second. That's a screenshot.
        RGBColor shine = atFull(water.lerp(plant, 0.25));

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, Math.round((maxY - minY) / rowStep));
        double widthKeys = maxX / keyWidth;
        if (padsFor != grid) choosePads(grid, minY, rowStep);

        // Resolved per frame, not per key: a handful of vines and bubbles
        // against a hundred-odd keys.
        double[] vineCol = new double[vineCount];
        double[] vineLen = new double[vineCount];
        for (int v = 0; v < vineCount; v++) {
            vineCol[v] = 0.5 + hash(v, 1) * (widthKeys - 1);
            double rest = 1 + hash(v, 2) * (vineMaxRows - 1);
            // Creeps once per loop, each vine at its own point in the cycle.
            vineLen[v] = rest + 0.6 * Math.sin(TAU * t / LOOP_SECONDS + TAU * hash(v, 3));
        }

        double[] gasX = new double[GAS_EMITTERS];
        double[] gasRow = new double[GAS_EMITTERS];
        double[] gasAge = new double[GAS_EMITTERS];
        int spots = (int) Math.round(LOOP_SECONDS / GAS_PERIOD);
        for (int e = 0; e < GAS_EMITTERS; e++) {
            double phase = t / GAS_PERIOD + (double) e / GAS_EMITTERS;
            int cycle = (int) Math.floor(phase);
            gasAge[e] = (phase - cycle) * GAS_PERIOD;
            // Wrapped to the loop, so the same spots come round every cycle
            // and the whole thing repeats exactly.
            int spot = Math.floorMod(cycle, spots);
            gasX[e] = 1 + hash(e * 31 + spot, 4) * Math.max(1, widthKeys - 2);
            // Water rows only: never in the canopy.
            gasRow[e] = 1 + hash(e * 31 + spot, 5) * (lastRow - 1);
        }

        double swirl = TAU * t / LOOP_SECONDS;
        double scumDx = SCUM_SWIRL * Math.cos(swirl), scumDy = SCUM_SWIRL * Math.sin(swirl);

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            int row = (int) Math.round(rowF);

            if (row == 0) {
                // Canopy. A static leaf texture so the top row isn't one flat
                // stripe, which reads as a status bar.
                double leaf = 0.75 + 0.5 * valueNoise(col * 0.9 + seed * 7.1, 3.3);
                budget.add(key.ref(), plant, CANOPY_LEVEL * leaf);
                continue;
            }

            budget.add(key.ref(), water, WATER_LEVEL);

            double n = valueNoise(key.x() * SCUM_SCALE + scumDx + seed * 13.7,
                    key.y() * SCUM_SCALE * 1.4 + scumDy + seed * 5.3);
            double patch = smoothstep(0.52, 0.72, n);
            if (patch > 0) budget.add(key.ref(), plant, patch * scumAmount * (WATER_LEVEL + SCUM_LIFT));

            if (pads.contains(key.ref())) {
                // Whole-number bob rates, 1 to 3 per loop, so the pads come
                // round with everything else.
                int k = key.ref().luid();
                double bobs = 1 + Math.floorMod(k * 7 + seed, 3);
                double bob = Math.sin(TAU * bobs * t / LOOP_SECONDS + k * 1.7);
                budget.add(key.ref(), plant, PAD_LEVEL + PAD_BOB * bob);
            }

            for (int v = 0; v < vineCount; v++) {
                if (Math.abs(col - vineCol[v]) >= 0.5) continue;
                double reach = vineLen[v] - (row - 1);
                if (reach <= 0) continue;
                // Brightest at the leaves, fading down the strand, and the tip
                // key only partly lit as the vine creeps into it.
                double fade = 1.0 - 0.25 * (row - 1);
                budget.add(key.ref(), vine, VINE_LEVEL * Math.max(0.2, fade) * Math.min(1, reach));
            }

            if (rootCount > 0) {
                double root = rootAt(col, rowF, lastRow, widthKeys);
                if (root > 0) budget.add(key.ref(), rootColor, ROOT_LEVEL * root);
            }

            double gasLight = 0;
            for (int e = 0; e < GAS_EMITTERS; e++) {
                double dx = col - gasX[e];
                double dy = rowF - gasRow[e];
                double d = Math.sqrt(dx * dx + dy * dy);
                double age = gasAge[e];
                if (age < GAS_RISE) {
                    // Rising: only the key it's coming up under, swelling.
                    if (d < 0.6) {
                        double f = age / GAS_RISE;
                        gasLight = Math.max(gasLight, 0.35 * f * f);
                    }
                    continue;
                }
                double since = age - GAS_RISE;
                if (d < 0.6 && since < POP_SECONDS) {
                    budget.add(key.ref(), gas, POP_LEVEL * (1 - since / POP_SECONDS));
                }
                double radius = RING_SPEED * since;
                double ring = 1 - Math.abs(d - radius) / RING_HALF;
                if (ring > 0) gasLight = Math.max(gasLight, RING_LEVEL * ring * Math.exp(-since / RING_FADE));
            }
            if (gasLight > 0) {
                // Ripples are the water catching light, so they're lit in the
                // water's own colour; on a pad the pad takes it instead.
                budget.add(key.ref(), pads.contains(key.ref()) ? atFull(plant) : shine, gasLight);
            }
        }

        return budget.resolve();
    }

    /**
     * How much of a mangrove root arch covers this spot, 0..1.
     *
     * <p>Each root is half an ellipse standing on the bottom edge. At six rows
     * of resolution that comes out as a rough arch two or three keys wide,
     * which is honestly about what a mangrove root looks like from a distance
     * anyway.
     */
    private double rootAt(double col, double rowF, double lastRow, double widthKeys) {
        double best = 0;
        double ground = lastRow + 0.5;
        for (int r = 0; r < rootCount; r++) {
            // Spread along the board in equal slots with jitter, so the roots
            // don't pile up at one end.
            double slot = widthKeys / rootCount;
            double centre = slot * (r + 0.25 + 0.5 * hash(r, 6));
            double span = 1.1 + 0.6 * hash(r, 7);
            double height = 1.6 + 0.8 * hash(r, 8);
            double ex = (col - centre) / span;
            double ey = (ground - rowF) / height;
            if (ey < 0) continue;
            double onArch = 1 - Math.abs(Math.sqrt(ex * ex + ey * ey) - 1) / 0.40;
            best = Math.max(best, onArch);
        }
        return best;
    }

    /**
     * Rolls which keys are pads: water rows only, never the canopy, and never
     * two on neighbouring keys, because two adjacent pads read as one wide
     * green blob rather than as two pads.
     */
    private void choosePads(KeyGrid grid, double minY, double rowStep) {
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        Set<KeyGrid.LedRef> chosen = new HashSet<>();
        java.util.List<KeyGrid.LedPosition> water = new java.util.ArrayList<>();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (Math.round((key.y() - minY) / rowStep) >= 1) water.add(key);
        }
        java.util.List<KeyGrid.LedPosition> placed = new java.util.ArrayList<>();
        for (int i = 0; i < water.size() * 4 && placed.size() < padCount && !water.isEmpty(); i++) {
            KeyGrid.LedPosition c = water.get((int) (hash(i, 9) * water.size()) % water.size());
            boolean crowded = false;
            for (KeyGrid.LedPosition p : placed) {
                double dx = (p.x() - c.x()) / keyWidth, dy = (p.y() - c.y()) / rowStep;
                if (dx * dx + dy * dy < 2.3) { crowded = true; break; }
            }
            if (crowded) continue;
            placed.add(c);
            chosen.add(c.ref());
        }
        pads = chosen;
        padsFor = grid;
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
}
