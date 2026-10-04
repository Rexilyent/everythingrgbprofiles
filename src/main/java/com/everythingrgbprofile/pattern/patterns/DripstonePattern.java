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
 * Dripstone caves: pointed dripstone hanging off the ceiling, water beading
 * at the tips and dropping to the floor.
 *
 * <p>Replaces {@code drip-fall}, which was generic drops falling over a flat
 * brown board. Drops falling from nowhere in particular is rain with low
 * self-esteem. The entire point of this biome is that the drips come off
 * <i>something</i>, so now there's something.
 *
 * <h2>What's on the board</h2>
 * <ul>
 *   <li><b>The ceiling.</b> A lumpy band of rock along the top edge, with
 *       stalactites hanging off it at different lengths, each one tapering to
 *       a point. Faint bands across them, because dripstone grows in layers
 *       and that's what makes it look grown instead of carved.</li>
 *   <li><b>Drops.</b> Each tip runs its own cycle. A bead swells and brightens
 *       on the point, lets go, falls under gravity (slow off the tip, fast by
 *       the bottom, with a short streak behind it), and splashes. Every tip
 *       has its own rhythm, so the board never drips in unison. Synchronised
 *       dripping is a metronome, and a metronome is a very stressful cave.</li>
 *   <li><b>The floor.</b> Stalagmites grow up under some of the drips, same as
 *       the game grows pointed dripstone upward under a dripping tip. Where
 *       there isn't one, there's a little puddle instead, and it ripples when
 *       a drop lands in it.</li>
 *   <li><b>One lava drip.</b> In the game a stalactite drips whatever fluid is
 *       sitting above it, and every so often that's lava. So one tip on the
 *       board drips slow orange drops that glow on the way down. It's the
 *       one bit of warm light in the room, and it earns its place.</li>
 * </ul>
 *
 * <h2>Coordinates</h2>
 * Rows <i>down</i> from the top this time, not up from the bottom. Everything
 * here hangs from the ceiling, so the ceiling is where you measure from.
 */
public final class DripstonePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** The dripstone caves, the only thing using this. */
    public static DripstonePattern dripstoneCaves() {
        return new DripstonePattern(29);
    }

    private final int seed;

    private DripstonePattern(int seed) {
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    /** The cave air behind everything. Dim and warm, never black: a black key reads as dead, not as dark. */
    private static final double CAVE_LEVEL = 0.40;
    private static final double STONE_LEVEL = 0.62;
    /** How much the growth bands move the stone's brightness either way. */
    private static final double BAND_DEPTH = 0.12;
    private static final double DROP_LEVEL = 0.95;
    private static final double BEAD_LEVEL = 0.80;
    private static final double SPLASH_LEVEL = 0.75;
    private static final double PUDDLE_LEVEL = 0.22;

    // --- drops ---------------------------------------------------------

    /**
     * Gravity, in rows per second squared. Picked so a drop off a short
     * stalactite takes about three quarters of a second to hit the floor.
     * Real gravity at keyboard scale would be over before you saw it.
     */
    private static final double GRAVITY = 14.0;
    /**
     * Lava is thick. It falls at about four fifths of water's speed, and its
     * tip runs a slower cycle than any water tip gets (see rollLayout), so it
     * hangs on the point for longer before it lets go.
     */
    private static final double LAVA_GRAVITY = 9.0;
    private static final double SPLASH_SECONDS = 0.35;
    /** How long a puddle keeps rippling after a drop lands in it. */
    private static final double RIPPLE_SECONDS = 1.2;
    /** Length of the streak behind a falling drop, in rows. */
    private static final double STREAK = 0.9;

    // The rolled cave, against whatever board turns up first.
    private KeyGrid layoutFor;
    /** {x, length, baseHalfWidth, period, phase, stalagmiteHeight (0 = puddle), lava (0/1)} */
    private final List<double[]> tips = new ArrayList<>();

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor stone = ctx.baseColor();
        RGBColor water = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        double floor = lastRow + 0.5;
        if (layoutFor != grid) rollLayout(grid, widthKeys, lastRow);

        // Where every drop is this frame. A handful of tips against a hundred
        // keys, so it's worked out once up front.
        int count = tips.size();
        double[] beadAmt = new double[count];
        double[] dropY = new double[count];
        double[] dropAmt = new double[count];
        double[] splashAmt = new double[count];
        double[] rippleAge = new double[count];
        for (int i = 0; i < count; i++) {
            double[] tip = tips.get(i);
            boolean lava = tip[6] > 0;
            double g = lava ? LAVA_GRAVITY : GRAVITY;
            double landing = floor - tip[5];   // stalagmite top, or the floor
            double fallDistance = Math.max(0.5, landing - tip[1]);
            double fallSeconds = Math.sqrt(2 * fallDistance / g);
            double period = tip[3];
            double age = ((t + tip[4] * period) % period);
            // The cycle: bead forms, drop falls, splash, and the bead starts
            // again straight away. Whatever's left of the period after the
            // fall goes to the bead, so slow tips are slow because they take
            // ages to let go, not because they fall slower.
            double form = period - fallSeconds;
            if (age < form) {
                double f = age / form;
                beadAmt[i] = f * f;   // swells slowly, then fast right before it goes
                dropAmt[i] = 0;
            } else {
                double s = age - form;
                dropY[i] = tip[1] + 0.5 * g * s * s;
                dropAmt[i] = 1;
            }
            // The splash and the ripple belong to the previous drop. It landed
            // the instant the last cycle ended, so the time since it landed is
            // just this cycle's age.
            splashAmt[i] = age < SPLASH_SECONDS ? 1 - age / SPLASH_SECONDS : 0;
            rippleAge[i] = age;
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double row = (key.y() - minY) / rowStep;
            KeyGrid.LedRef ref = key.ref();

            // --- ceiling and stalactites -------------------------------
            double ceiling = -0.1 + 0.4 * valueNoise(col * 0.6 + seed, 1.3);
            double rock = smoothstep(ceiling + 0.25, ceiling - 0.25, row);
            double rockDepth = row;   // for the bands
            for (int i = 0; i < count; i++) {
                double[] tip = tips.get(i);
                double dx = Math.abs(col - tip[0]);
                if (dx > tip[2] + 0.6 || row > tip[1] + 0.3) continue;
                // Tapers to a point. The 0.6 power keeps the shoulders broad,
                // so it's still a cone two rows down instead of having
                // already thinned to one key. Most boards have a gap under
                // the F-row, which means the second row is already a third of
                // the way down a short stalactite before you've started.
                double down = Math.max(0, row) / tip[1];
                double half = tip[2] * Math.pow(Math.max(0, 1 - down), 0.6);
                double c = smoothstep(half + 0.3, half - 0.1, dx) * smoothstep(tip[1] + 0.25, tip[1] - 0.3, row);
                if (c > rock) {
                    rock = c;
                    rockDepth = row + tip[4] * 3;   // offset per tip so the bands don't line up across them
                }
            }

            // --- floor and stalagmites ---------------------------------
            double ground = smoothstep(floor - 0.55, floor - 0.15, row);
            double puddle = 0, ripple = 0;
            for (int i = 0; i < count; i++) {
                double[] tip = tips.get(i);
                double dx = Math.abs(col - tip[0]);
                if (tip[5] > 0) {
                    double up = floor - row;
                    if (up < -0.3 || up > tip[5] + 0.3 || dx > 1.5) continue;
                    double half = 1.1 * Math.pow(Math.max(0, 1 - up / tip[5]), 0.8);
                    double c = smoothstep(half + 0.3, half - 0.1, dx) * smoothstep(tip[5] + 0.25, tip[5] - 0.3, up);
                    ground = Math.max(ground, c);
                } else if (row > floor - 0.6 && dx < 1.6) {
                    // A puddle: a short strip of water on the floor under the tip.
                    double p = smoothstep(1.6, 0.6, dx);
                    puddle = Math.max(puddle, p);
                    if (rippleAge[i] < RIPPLE_SECONDS) {
                        // A ring moving outward from where the drop landed.
                        double front = rippleAge[i] * 1.6;
                        double ring = Math.exp(-sq((dx - front) / 0.4)) * (1 - rippleAge[i] / RIPPLE_SECONDS);
                        ripple = Math.max(ripple, ring * p);
                    }
                }
            }
            rock = Math.max(rock, ground);

            // --- light it ----------------------------------------------
            double air = 1 - rock;
            if (air > 0) budget.add(ref, ColorPalette.CAVE_DARK, air * CAVE_LEVEL);
            if (rock > 0) {
                double bands = 1 + BAND_DEPTH * Math.sin(TAU * rockDepth * 1.6 + 2 * valueNoise(col * 0.5, seed));
                budget.add(ref, stone, rock * STONE_LEVEL * bands);
            }
            if (puddle > 0) {
                budget.add(ref, water, puddle * (PUDDLE_LEVEL + 0.55 * ripple));
            }

            // --- drops -------------------------------------------------
            for (int i = 0; i < count; i++) {
                double[] tip = tips.get(i);
                double dx = col - tip[0];
                if (Math.abs(dx) > 1.6) continue;
                boolean lava = tip[6] > 0;
                RGBColor colour = lava ? ColorPalette.DRIPSTONE_LAVA : water;

                if (beadAmt[i] > 0.02) {
                    // The bead hangs just under the point and grows as it fills.
                    double r = 0.25 + 0.3 * beadAmt[i];
                    double d = Math.hypot(dx, row - (tip[1] + 0.15));
                    double b = smoothstep(r + 0.3, r - 0.1, d);
                    if (b > 0) budget.add(ref, colour, BEAD_LEVEL * beadAmt[i] * b);
                }
                if (dropAmt[i] > 0) {
                    double above = dropY[i] - row;   // positive: the key is above the drop
                    double head = smoothstep(0.75, 0.2, Math.hypot(dx, above));
                    double trail = above > 0 && above < STREAK
                            ? (1 - above / STREAK) * smoothstep(0.55, 0.15, Math.abs(dx)) * 0.5
                            : 0;
                    double amt = Math.max(head, trail);
                    if (amt > 0) budget.add(ref, colour, DROP_LEVEL * amt);
                }
                if (splashAmt[i] > 0) {
                    // Spreads sideways as it fades, landing on whatever's
                    // under the tip.
                    double landing = floor - tips.get(i)[5];
                    double spread = 0.4 + 1.2 * (1 - splashAmt[i]);
                    double s = Math.exp(-sq(dx / spread)) * smoothstep(0.9, 0.2, Math.abs(row - (landing - 0.2)));
                    if (s > 0.02) budget.add(ref, colour, SPLASH_LEVEL * splashAmt[i] * s);
                }
                if (lava && (beadAmt[i] > 0.02 || dropAmt[i] > 0)) {
                    // Lava lights the stone around it a little. Water doesn't
                    // glow and doesn't get to.
                    double y = dropAmt[i] > 0 ? dropY[i] : tip[1];
                    double glow = Math.exp(-(sq(dx) + sq(row - y)) / 3.0) * (dropAmt[i] > 0 ? 1 : beadAmt[i]);
                    if (glow > 0.02) budget.add(ref, ColorPalette.DRIPSTONE_LAVA, 0.25 * glow * rock);
                }
            }
        }

        return budget.resolve();
    }

    /**
     * Hangs the stalactites. Spread across the board with some gaps, lengths
     * mixed so the ceiling doesn't look trimmed, and each gets its own drip
     * rhythm. Roughly half grow a stalagmite underneath, the rest get a
     * puddle. One, somewhere in the middle, drips lava.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double lastRow) {
        tips.clear();
        double x = 0.8 + 1.2 * hash(0, 10);
        for (int i = 0; x < widthKeys + 0.3 && i < 32; i++) {
            double length = lerp(2.4, Math.max(2.8, lastRow * 0.72), hash(i, 11));
            double baseHalf = lerp(1.2, 1.9, hash(i, 12));
            double period = lerp(2.6, 6.0, hash(i, 13));
            double stalagmite = hash(i, 14) < 0.5 ? lerp(0.7, 1.6, hash(i, 15)) : 0;
            tips.add(new double[]{x, length, baseHalf, period, hash(i, 16), stalagmite, 0});
            x += baseHalf + lerp(1.6, 3.4, hash(i, 17));
        }
        if (tips.size() >= 4) {
            // The lava tip: a middle one, so it's not hiding at the edge of
            // the board, and slower than the rest.
            double[] lava = tips.get(tips.size() / 2);
            lava[6] = 1;
            lava[3] = Math.max(lava[3], 6.5);
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
