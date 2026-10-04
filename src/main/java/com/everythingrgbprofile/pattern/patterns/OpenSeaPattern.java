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
 * The open ocean, in five flavours, none of which are bubbles.
 *
 * <p>Replaces {@code bubble-rise} on every ocean that isn't frozen. Seven
 * biomes all ran the same preset in seven slightly different blues, so the
 * entire sea, which is most of the planet, came out as one effect: blue dots
 * floating upward. You could swap any two oceans' profiles and nobody would
 * ever find out. Bit of a waste of the biggest biome family in the game.
 *
 * <h2>What's in the toolbox</h2>
 * One engine, a handful of layers, and each preset switches on the ones that
 * fit its water. Same "parameterise, don't subclass" deal as the drift engine.
 * <ul>
 *   <li><b>Swell.</b> Long crests rolling diagonally across the board, dark in
 *       the troughs and lit along the tops. Two swells at different lengths,
 *       angles and periods, so they drift in and out of step instead of
 *       marching past like a screensaver.</li>
 *   <li><b>Whitecaps.</b> Foam where the two swells stack up. It's pinned to
 *       the wave it broke on, so it rides along with the crest rather than
 *       flickering in place.</li>
 *   <li><b>The shadow.</b> Every so often something large passes underneath.
 *       What it is gets left to your imagination, which is doing a better job
 *       than eighteen columns of LEDs ever could.</li>
 *   <li><b>Kelp.</b> Strands rooted along the bottom row, swaying, with the
 *       sway travelling up the stalk so the tips lag the base.</li>
 *   <li><b>Caustics.</b> That wobbly web of light on the floor of shallow
 *       water. Nothing else in the mod looks like it, which is the point.</li>
 *   <li><b>Coral.</b> Lumpy mounds along the bottom rows in vanilla's coral
 *       colours, with the caustics playing over them.</li>
 * </ul>
 *
 * <p>Yes, the swell is looking down at the water and the kelp is looking at it
 * from the side. At six rows tall the board has room for exactly one idea per
 * row, and "water moving above, things growing below" is a better idea than
 * either camera angle on its own.
 *
 * <h2>Why the crests are bent</h2>
 * A straight crest crossing a keyboard lights a straight diagonal of keys,
 * and a straight diagonal of keys moving at a steady speed is a sweep effect.
 * We have one of those. It's called {@code SweepPattern}. So the swell's coordinates
 * get pushed around by slow noise first, which bends every crest a little and
 * keeps bending it differently as it goes.
 *
 * <h2>Why the deep ocean's waves are slower AND faster</h2>
 * Deep-water waves follow a real rule: a longer wave travels faster, and its
 * period only grows with the square root of its length. So the deep preset
 * stretches the swell 1.6x and its period about 1.26x. Crests come by less
 * often, and each one moves about a quarter faster. Is anybody going to notice?
 * No. Did it cost anything to get right? Also no.
 *
 * <h2>Colour</h2>
 * The water is the profile's base colour and the light on it (crests, foam,
 * caustics) is the accent. Kelp and coral come from {@link ColorPalette},
 * because a profile entry carries two colours and a reef needs about six.
 */
public final class OpenSeaPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // --- presets -------------------------------------------------------

    /** Plain ocean: open swell with whitecaps where the waves pile up. */
    public static OpenSeaPattern ocean() {
        return new OpenSeaPattern(1.0, true, false, false, false, false, 0.95, 3);
    }

    /**
     * Deep ocean: longer, slower swell over darker water, no foam, and
     * occasionally a shadow under it. Shared by the deep cold and deep
     * lukewarm oceans, which get the same scene in their own colours.
     */
    public static OpenSeaPattern deepOcean() {
        return new OpenSeaPattern(1.6, false, true, false, false, false, 1.0, 11);
    }

    /** Cold ocean: a choppier swell, and kelp standing up out of the bottom rows. */
    public static OpenSeaPattern coldOcean() {
        return new OpenSeaPattern(0.85, false, false, true, false, false, 0.95, 7);
    }

    /** Warm ocean: caustics over a reef. The only one that gets coral, same as the game. */
    public static OpenSeaPattern warmOcean() {
        return new OpenSeaPattern(0, false, false, false, true, true, 0.65, 5);
    }

    /** Lukewarm ocean: the caustics on their own. Clear shallow water, nothing in it showing off. */
    public static OpenSeaPattern lukewarmOcean() {
        return new OpenSeaPattern(0, false, false, false, true, false, 0.75, 13);
    }

    /** Swell length multiplier. Zero turns the swell off entirely. */
    private final double swellScale;
    private final boolean whitecaps;
    private final boolean shadow;
    private final boolean kelp;
    private final boolean caustics;
    private final boolean coral;
    /** Water brightness before the swell or anything else gets a go at it. */
    private final double waterLevel;
    private final int seed;

    private OpenSeaPattern(double swellScale, boolean whitecaps, boolean shadow,
                           boolean kelp, boolean caustics, boolean coral,
                           double waterLevel, int seed) {
        this.swellScale = swellScale;
        this.whitecaps = whitecaps;
        this.shadow = shadow;
        this.kelp = kelp;
        this.caustics = caustics;
        this.coral = coral;
        this.waterLevel = waterLevel;
        this.seed = seed;
    }

    // --- swell ---------------------------------------------------------
    // Lengths in key widths, periods in seconds, angles in radians off
    // horizontal. The two periods share no neat ratio, so the pair never
    // lines back up into a loop you could learn.

    private static final double SWELL_A_LENGTH = 7.0, SWELL_A_PERIOD = 6.7, SWELL_A_ANGLE = 0.90;
    private static final double SWELL_B_LENGTH = 4.4, SWELL_B_PERIOD = 4.3, SWELL_B_ANGLE = -1.05;
    private static final double SWELL_A_WEIGHT = 0.62, SWELL_B_WEIGHT = 0.38;
    /** How bright a trough is, as a share of the water level. */
    private static final double TROUGH = 0.60;
    private static final double CREST_LEVEL = 0.40;
    private static final double FOAM_LEVEL = 0.95;

    // --- the shadow ----------------------------------------------------

    /** One pass per this many seconds, in the back end of the cycle so it never greets you on arrival. */
    private static final double SHADOW_EVERY = 24.0;
    private static final double SHADOW_CROSSING = 15.0;
    /** Half its length in keys, and its half-height in rows at its thickest. */
    private static final double SHADOW_HALF_LENGTH = 4.2, SHADOW_HALF_HEIGHT = 1.7;
    /** How much water it blocks at its darkest. Not all of it: it's under the water, not on top. */
    private static final double SHADOW_DEPTH = 0.7;

    // --- kelp ----------------------------------------------------------

    private static final double KELP_SWAY = 0.6;
    private static final double KELP_PERIOD = 4.8;
    private static final double KELP_HALF_WIDTH = 0.38;
    private static final double KELP_LEVEL = 0.75;

    // --- caustics ------------------------------------------------------

    /**
     * Cells come out about four to six keys across. Any finer and the lines
     * crowd together until the whole thing turns to porridge, any coarser
     * and there's one line on the board doing a lonely little dance.
     */
    private static final double CAUSTIC_SCALE = 0.36;
    /**
     * How far either side of a line still counts as on it, in noise units,
     * which works out to a line about one key thick. Nearly double this and
     * half the board is lit, and the web turns into a maze.
     */
    private static final double CAUSTIC_WIDTH = 0.14;
    private static final double CAUSTIC_LEVEL = 0.75;

    // --- coral ---------------------------------------------------------

    private static final double CORAL_LEVEL = 0.80;
    /**
     * Four of vanilla's five corals. Tube coral is blue and this reef sits in
     * blue water, so tube coral would be a hole in the reef shaped like
     * nothing. It can sit this one out.
     */
    private static final RGBColor[] CORALS = {
            ColorPalette.CORAL_BRAIN, ColorPalette.CORAL_BUBBLE,
            ColorPalette.CORAL_FIRE, ColorPalette.CORAL_HORN
    };

    // The rolled kelp and coral, rolled against whatever board turns up first
    // and rolled again if it changes. Same arrangement as the ice floes.
    private KeyGrid layoutFor;
    private final List<double[]> strands = new ArrayList<>();   // {rootX, height, phase}
    private final List<double[]> mounds = new ArrayList<>();    // {centreX, halfWidth, height, colourIndex}

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor water = ctx.baseColor();
        RGBColor light = ctx.resolvedAccentColor();
        // Same trick as the ice: water catching light is its own hue pushed
        // toward the accent and run at full brightness. "The same dark blue
        // but slightly more of it" does not show up on an LED.
        RGBColor sheen = atFull(water.lerp(light, 0.35));

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        if (layoutFor != grid) rollLayout(grid, widthKeys, lastRow);

        // Where the shadow is this frame, if it's out at all. Direction and
        // depth are rolled per pass, so it never takes the same route twice
        // in a row on purpose.
        int shadowDir = 0;
        double shadowX = 0, shadowY = 0;
        if (shadow) {
            int pass = (int) Math.floor(t / SHADOW_EVERY);
            double age = t - pass * SHADOW_EVERY - (SHADOW_EVERY - SHADOW_CROSSING);
            if (age >= 0) {
                shadowDir = hash(pass, 1) < 0.5 ? 1 : -1;
                double margin = SHADOW_HALF_LENGTH + 1;
                double along = -margin + (widthKeys + 2 * margin) * age / SHADOW_CROSSING;
                shadowX = shadowDir > 0 ? along : widthKeys - along;
                shadowY = lerp(0.8, lastRow - 0.8, hash(pass, 2)) + 0.3 * Math.sin(TAU * age / 6.0);
            }
        }

        // Deep-water dispersion, as promised in the class doc: period goes
        // with the square root of length.
        double periodScale = Math.sqrt(Math.max(swellScale, 1e-6));

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            // Distance up from just under the bottom row, for things that grow.
            double fromFloor = lastRow + 0.5 - rowF;

            // --- the water's own brightness ----------------------------
            double level = waterLevel;
            double crest = 0, foam = 0;
            if (swellScale > 0) {
                double warp = 1.6 * (valueNoise(col * 0.15 + seed, rowF * 0.25 + t * 0.04) - 0.5);
                double phaseA = swellPhase(col, rowF, warp, SWELL_A_ANGLE,
                        SWELL_A_LENGTH * swellScale, SWELL_A_PERIOD * periodScale, t);
                double phaseB = swellPhase(col, rowF, -warp, SWELL_B_ANGLE,
                        SWELL_B_LENGTH * swellScale, SWELL_B_PERIOD * periodScale, t) + 0.37;
                double h = SWELL_A_WEIGHT * crestShape(phaseA) + SWELL_B_WEIGHT * crestShape(phaseB);
                level *= TROUGH + (1 - TROUGH) * h;
                crest = smoothstep(0.42, 0.85, h);
                if (whitecaps) {
                    // Sampled in swell A's own frame: across the crest and along
                    // it. The across coordinate already moves with the wave, so
                    // the foam patch goes wherever the crest goes.
                    double alongA = -col * Math.sin(SWELL_A_ANGLE) + rowF * Math.cos(SWELL_A_ANGLE);
                    double patch = valueNoise(phaseA * 1.3 + seed, alongA * 0.6 + 3.1);
                    foam = smoothstep(0.66, 0.85, h) * smoothstep(0.40, 0.70, patch);
                }
            } else {
                // No swell: just let the brightness wander a bit so open water
                // between the caustic lines isn't a flat fill.
                level *= 0.85 + 0.15 * valueNoise(col * 0.2 + seed, rowF * 0.3 + t * 0.1);
            }

            // --- the shadow --------------------------------------------
            double shade = 0;
            if (shadowDir != 0) {
                double u = (col - shadowX) * shadowDir / SHADOW_HALF_LENGTH;   // +1 at the front, -1 at the back
                double body = Math.sqrt(Math.max(0, 1 - u * u));
                // Fuller at the front, thinning toward the back. At this
                // resolution that's the whole difference between a fish-shaped
                // thing and a slow grey sausage.
                double half = SHADOW_HALF_HEIGHT * body * (u > 0 ? 1 : 1 + 0.45 * u);
                shade = smoothstep(-0.45, 0.45, half - Math.abs(rowF - shadowY))
                        * smoothstep(1.25, 0.95, Math.abs(u));
            }
            double dim = 1 - SHADOW_DEPTH * shade;

            // --- kelp --------------------------------------------------
            double kelpCover = 0, kelpHeight = 0;
            if (kelp) {
                for (double[] s : strands) {
                    if (fromFloor < 0 || fromFloor > s[1]) continue;
                    double up = fromFloor / s[1];
                    // The sway runs up the stalk, so the tip is always
                    // finishing the move the base started a moment ago.
                    double sway = KELP_SWAY * Math.pow(up, 1.4)
                            * Math.sin(TAU * (t / KELP_PERIOD - up * 0.6) + s[2]);
                    double c = smoothstep(KELP_HALF_WIDTH + 0.4, KELP_HALF_WIDTH - 0.1, Math.abs(col - s[0] - sway))
                            * smoothstep(s[1], s[1] - 0.6, fromFloor);
                    if (c > kelpCover) {
                        kelpCover = c;
                        kelpHeight = up;
                    }
                }
            }

            // --- coral -------------------------------------------------
            double coralCover = 0;
            RGBColor coralColor = null;
            if (coral) {
                for (int m = 0; m < mounds.size(); m++) {
                    double[] mound = mounds.get(m);
                    double dx = (col - mound[0]) / mound[1];
                    if (Math.abs(dx) > 1.2) continue;
                    // A dome with lumps on it. Lumps pinned to the mound, so
                    // the reef holds its shape rather than boiling.
                    double top = mound[2] * (1 - dx * dx)
                            + 0.6 * (valueNoise(col * 1.6 + m * 13.1, seed + 0.5) - 0.5);
                    double c = smoothstep(-0.3, 0.3, top - fromFloor) * smoothstep(1.15, 0.85, Math.abs(dx));
                    if (c > coralCover) {
                        coralCover = c;
                        coralColor = CORALS[(int) mound[3]];
                    }
                }
            }

            // --- caustics ----------------------------------------------
            double caustic = 0;
            if (caustics) {
                double u = col * CAUSTIC_SCALE, v = rowF * CAUSTIC_SCALE;
                // Two crossing fields of bent lines. Either one alone is a set
                // of squiggles. Together they close into cells, and cells are
                // what makes it read as light through water.
                caustic = Math.max(causticLine(u, v, t), causticLine(v + 3.7, u - 1.9, t * 0.8 + 11.0));
            }

            // --- put it all together -----------------------------------
            double open = (1 - kelpCover) * (1 - coralCover);
            KeyGrid.LedRef ref = key.ref();
            budget.add(ref, water, open * level * dim);
            if (crest > 0) budget.add(ref, sheen, open * CREST_LEVEL * crest * dim);
            if (foam > 0) budget.add(ref, light, open * FOAM_LEVEL * foam * dim);
            if (kelpCover > 0) {
                // Brighter toward the tips, which are closer to the light.
                budget.add(ref, ColorPalette.KELP, kelpCover * KELP_LEVEL * (0.75 + 0.35 * kelpHeight) * dim);
            }
            if (coralCover > 0) {
                double texture = 0.85 + 0.15 * valueNoise(col * 1.3 + seed, rowF * 1.3);
                budget.add(ref, coralColor, coralCover * CORAL_LEVEL * texture);
            }
            if (caustic > 0) {
                // Half strength on the coral. Full strength turns a pink mound
                // into a white one every time a line crosses it.
                budget.add(ref, light, CAUSTIC_LEVEL * caustic * (open + 0.5 * coralCover));
            }
        }

        return budget.resolve();
    }

    /**
     * Plants the kelp and the reef. Both walk the bottom edge left to right
     * with random gaps, and kelp sometimes doubles up into a clump, because a
     * perfectly even row of kelp is a fence.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double lastRow) {
        strands.clear();
        mounds.clear();
        double tallest = Math.max(2.2, lastRow * 0.85);

        double x = 0.6 + 1.5 * hash(0, 40);
        for (int i = 0; x < widthKeys + 0.5 && i < 64; i++) {
            strands.add(new double[]{x, lerp(2.0, tallest, hash(i, 41)), TAU * hash(i, 42)});
            x += hash(i, 43) < 0.35 ? 0.9 : lerp(2.2, 4.5, hash(i, 44));
        }

        x = 1.5 * hash(0, 50);
        for (int i = 0; x < widthKeys + 1 && i < 64; i++) {
            double halfWidth = lerp(0.8, 1.8, hash(i, 51));
            double centre = x + halfWidth;
            double height = lerp(0.9, 2.2, hash(i, 52));
            // Never the same coral twice in a row. Two neighbouring mounds in
            // one colour just merge into one long mound.
            int colour = (int) (hash(i, 53) * CORALS.length) % CORALS.length;
            if (!mounds.isEmpty() && colour == (int) mounds.get(mounds.size() - 1)[3]) {
                colour = (colour + 1) % CORALS.length;
            }
            mounds.add(new double[]{centre, halfWidth, height, colour});
            x = centre + halfWidth + lerp(0.6, 2.4, hash(i, 54));
        }
        layoutFor = grid;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Where this key sits in one swell's cycle, in waves. Grows along the swell's direction and with time. */
    private static double swellPhase(double col, double row, double warp, double angle,
                                     double length, double period, double t) {
        double across = col * Math.cos(angle) + row * Math.sin(angle) + warp;
        return across / length - t / period;
    }

    /**
     * 0 in the trough, 1 on the crest, with narrow crests and wide troughs.
     * Real swell is shaped like that. A plain sine spends as long being a
     * crest as being a trough, which looks like the sea breathing.
     */
    private static double crestShape(double phase) {
        double w = 0.5 + 0.5 * Math.sin(TAU * phase);
        return w * w * w;
    }

    /** 1 on one of this field's lines, falling to 0 about a key either side. */
    private static double causticLine(double u, double v, double t) {
        double n = 0.5 * (Math.sin(u + 0.6 * t + 1.7 * Math.sin(v * 1.3 - 0.4 * t))
                + Math.sin(v * 1.2 - 0.5 * t + 1.7 * Math.sin(u * 0.9 + 0.35 * t)));
        return smoothstep(CAUSTIC_WIDTH, 0.0, Math.abs(n));
    }

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
