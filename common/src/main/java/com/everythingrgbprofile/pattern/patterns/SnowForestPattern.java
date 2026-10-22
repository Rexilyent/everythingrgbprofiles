package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Snow-loaded spruces, side on: the grove and the snowy taiga. Also the plain
 * taiga, which is the same forest with the snow taken off; see the end of
 * this doc.
 *
 * <p>Both of these used to run {@code snow-drift}, which is snowfall, and
 * snowfall belongs to the weather overlay now. It turns up when it's actually
 * snowing and goes away when it isn't, like weather is supposed to.
 *
 * <h2>The scene</h2>
 * Dark spruces standing in snow, under sky. Each tree is stacked tiers, wide at
 * the bottom of each one and pinched in at the top, which is about the most
 * spruce a tree can look in five rows of LEDs. Snow sits on the top of every
 * tier in the accent colour.
 *
 * <p>There are two rows of trees. The back row is smaller and hazed toward the
 * sky colour, and that's the whole depth effect. It's cheap and it works,
 * which is the best kind of trick.
 *
 * <h2>The thing that moves</h2>
 * Every few seconds one tree shakes and dumps its load: a quick shudder, then a
 * puff of snow drops off it and spreads as it falls. The tree comes out of it
 * mostly green and builds its snow back up slowly over the next twenty seconds.
 *
 * <p>One tree at a time, then quiet. Constant motion everywhere was snowfall's
 * whole problem; this is one thing happening, and then nothing happening for
 * a bit, which is what a snowy forest mostly sounds like too.
 *
 * <h2>Grove vs taiga</h2>
 * The grove sits on a rising slope with its trees packed close, because groves
 * are mountainside forest. The snowy taiga is flat ground with trees spread
 * out and gaps of open snow between them. Same trees, different land.
 *
 * <h2>The taiga</h2>
 * Used to be a {@code shimmer} in a dull green, which is a forest the way a
 * paint swatch is a forest. The spruces are exactly the same trees as the
 * snowy taiga's, so they come from here rather than from a copy of this
 * class, and everything that was about snow gets swapped out:
 * <ul>
 *   <li><b>No snow on the tiers.</b> The tops of each tier take a little of the
 *       accent instead, as sunlit needle tips, so the trees keep their shape
 *       without the white.</li>
 *   <li><b>Forest floor, not snowfield.</b> Olive-green moss and ferns, a
 *       warmer green than the trees so the two don't merge.</li>
 *   <li><b>Ground mist.</b> Pale patches drifting slowly along the floor
 *       between the trunks. Thinner in front of the front-row trees, so they
 *       stand out of it rather than vanishing into it.</li>
 *   <li><b>A fox.</b> The dump-and-recover cycle has nothing to dump, so the
 *       taiga's one event is a fox trotting across the board along the
 *       forest floor, every twelve seconds or so, from a random side. Taigas
 *       are where foxes spawn, and an orange thing moving over green is about
 *       the most readable event a keyboard can show. It runs in front of
 *       every tree, because at the bottom of a spruce is where it's widest and
 *       a fox behind the trunks would be a fox nobody sees.</li>
 * </ul>
 */
public final class SnowForestPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // --- presets -------------------------------------------------------

    /** Grove: dense trees on a slope. */
    public static SnowForestPattern grove() {
        return new SnowForestPattern(true, true, 2.8, 4.2, 0.82, 1.0, 5);
    }

    /** Snowy taiga: flat ground, trees spread out with open snow between. */
    public static SnowForestPattern snowyTaiga() {
        return new SnowForestPattern(true, false, 4.0, 6.5, 0.70, 0.88, 9);
    }

    /** Taiga: the snowy taiga thawed, a little denser, with mist and a fox. */
    public static SnowForestPattern taiga() {
        return new SnowForestPattern(false, false, 3.4, 5.6, 0.72, 0.92, 13);
    }

    /** False for the taiga. Swaps the snow, the dumps and the sparkle for floor, mist and fox. */
    private final boolean snowy;
    /** Ground rises left to right instead of lying flat. */
    private final boolean sloped;
    /** Gap between front-row trunks, in keys. */
    private final double spacingMin, spacingMax;
    /** Front-row tree heights, as a share of the board's height. */
    private final double heightMin, heightMax;
    private final int seed;

    private SnowForestPattern(boolean snowy, boolean sloped, double spacingMin, double spacingMax,
                              double heightMin, double heightMax, int seed) {
        this.snowy = snowy;
        this.sloped = sloped;
        this.spacingMin = spacingMin;
        this.spacingMax = spacingMax;
        this.heightMin = heightMin;
        this.heightMax = heightMax;
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    private static final double SKY_LEVEL = 0.28;
    private static final double GROUND_LEVEL = 0.70;
    private static final double TREE_LEVEL = 0.85;
    private static final double TREE_SNOW_LEVEL = 0.95;
    /** Back row: how far toward the sky it's hazed, and how much dimmer it runs. */
    private static final double BACK_HAZE = 0.55, BACK_LEVEL = 0.50;

    /** Tiers per tree. Three reads as spruce. Two reads as a Christmas tree drawn by a kid, four turns to mush. */
    private static final int TIERS = 3;

    // --- dumps ---------------------------------------------------------

    private static final double DUMP_EVERY = 5.0;
    /** How long the tree shudders before the snow comes off. */
    private static final double SHAKE_SECONDS = 0.5;
    private static final double FALL_SECONDS = 2.2;
    /** How long a tree takes to get its snow back. */
    private static final double RECOVER_SECONDS = 20.0;
    /** How much snow is left on a tree that just dumped. Not none: the inside branches keep some. */
    private static final double LOAD_AFTER_DUMP = 0.2;
    private static final double PUFF_LEVEL = 0.85;

    // --- sparkle -------------------------------------------------------

    private static final int SPARKLES = 4;
    private static final double SPARKLE_PERIOD = 1.6;
    private static final double SPARKLE_SECONDS = 0.30;

    // --- taiga only ----------------------------------------------------

    /** How much of each tier top goes to the accent when there's no snow on it. Tips, not a coat. */
    private static final double TAIGA_TIP = 0.35;
    private static final double TAIGA_FLOOR_LEVEL = 0.55;
    private static final double MIST_LEVEL = 0.48;
    /** How high the mist reaches above the floor, in rows. */
    private static final double MIST_HEIGHT = 2.4;
    /** Keys per second. Slow, or it's smoke. */
    private static final double MIST_DRIFT = 1.1;
    private static final double FOX_EVERY = 12.0;
    /** Keys per second. A trot: fast enough to be going somewhere, slow enough to follow. */
    private static final double FOX_SPEED = 4.0;
    private static final double FOX_LEVEL = 0.95;

    private KeyGrid layoutFor;
    /** {x, height, halfWidth, backRow (0/1)}. Back row first, so the front draws over it. */
    private final List<double[]> trees = new ArrayList<>();
    private int frontStart;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor spruce = ctx.baseColor();
        RGBColor snow = ctx.resolvedAccentColor();
        RGBColor backSpruce = spruce.lerp(ColorPalette.ALPINE_SKY, BACK_HAZE);
        RGBColor backSnow = snow.lerp(ColorPalette.ALPINE_SKY, BACK_HAZE * 0.6);

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

        int frontCount = trees.size() - frontStart;
        int cycle = (int) Math.floor(t / DUMP_EVERY);
        double cycleAge = t - cycle * DUMP_EVERY;
        int dumping = snowy && frontCount > 0 ? frontStart + pickTree(cycle, frontCount) : -1;

        // How much snow every tree is carrying right now. A tree that dumped
        // in any of the last few cycles is still building back up. On the
        // taiga it's the needle tips instead, and they never go anywhere.
        double[] load = new double[trees.size()];
        Arrays.fill(load, snowy ? 1.0 : TAIGA_TIP);
        if (snowy && frontCount > 0) {
            int lookBack = (int) Math.ceil(RECOVER_SECONDS / DUMP_EVERY) + 1;
            for (int k = 0; k <= lookBack; k++) {
                int c = cycle - k;
                if (c < 0) break;
                int tree = frontStart + pickTree(c, frontCount);
                double since = t - (c * DUMP_EVERY + SHAKE_SECONDS);
                if (since < 0) continue;
                double l = LOAD_AFTER_DUMP + (1 - LOAD_AFTER_DUMP) * smoothstep(0, RECOVER_SECONDS, since);
                load[tree] = Math.min(load[tree], l);
            }
        }
        double shake = 0;
        if (dumping >= 0 && cycleAge < SHAKE_SECONDS + 0.2) {
            shake = 0.22 * Math.sin(TAU * cycleAge * 7) * smoothstep(SHAKE_SECONDS + 0.2, 0, cycleAge);
        }
        // The falling puff: starts in the tree's crown and spreads as it drops.
        double puffAge = cycleAge - SHAKE_SECONDS;
        double puffX = 0, puffE = 0, puffR = 0, puffAmt = 0;
        if (dumping >= 0 && puffAge >= 0 && puffAge < FALL_SECONDS) {
            double[] tr = trees.get(dumping);
            double base = ground(tr[0], widthKeys);
            double f = puffAge / FALL_SECONDS;
            puffX = tr[0] + 0.6 * f;   // a touch of drift
            puffE = lerp(base + tr[1] * 0.6, base + 0.2, f * f);   // accelerating, as falling things do
            puffR = lerp(0.9, 1.9, f);
            puffAmt = Math.sin(Math.PI * Math.min(1, f * 1.3)) * (1 - 0.4 * f);
        }

        double[] sparkX = new double[SPARKLES], sparkE = new double[SPARKLES], sparkAmt = new double[SPARKLES];
        for (int s = 0; snowy && s < SPARKLES; s++) {
            double phase = t / SPARKLE_PERIOD + (double) s / SPARKLES;
            int c = (int) Math.floor(phase);
            double age = (phase - c) * SPARKLE_PERIOD;
            if (age >= SPARKLE_SECONDS) continue;
            int roll = s * 977 + c;
            sparkX[s] = hash(roll, 60) * widthKeys;
            sparkE[s] = ground(sparkX[s], widthKeys) - hash(roll, 61) * 1.0;
            sparkAmt[s] = Math.sin(Math.PI * age / SPARKLE_SECONDS);
        }

        // The fox, if one's out. Each run picks its own side to come in from.
        // It starts and finishes a few keys off the board, so it trots on and
        // off rather than appearing.
        double foxX = 0, foxE = 0, foxDir = 0;
        if (!snowy) {
            int run = (int) Math.floor(t / FOX_EVERY);
            double travel = (t - run * FOX_EVERY) * FOX_SPEED;
            if (travel < widthKeys + 6) {
                foxDir = hash(run, 50) < 0.5 ? 1 : -1;
                foxX = foxDir > 0 ? travel - 3 : widthKeys + 3 - travel;
                // A trot bounces. Abs of a sine, so it's a hop off the ground
                // each stride and never a dip into it.
                foxE = ground(foxX, widthKeys) + 0.45 + 0.15 * Math.abs(Math.sin(TAU * t * 2.2));
            }
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - rowF;
            KeyGrid.LedRef ref = key.ref();

            double surface = ground(col, widthKeys);
            double depth = surface - elev;
            double solid = smoothstep(-0.35, 0.35, depth);

            // --- which trees cover this key ----------------------------
            // The strongest tree in each row, tracked separately, so the
            // front row can sit over the back one instead of fighting it.
            double backCover = 0, backSnowAmt = 0, frontCover = 0, frontSnowAmt = 0;
            for (int i = 0; i < trees.size(); i++) {
                double[] tr = trees.get(i);
                double x = tr[0] + (i == dumping ? shake : 0);
                double e = elev - ground(tr[0], widthKeys);
                if (e < -0.3 || e > tr[1] + 0.3) continue;
                double frac = Math.max(0, e) / tr[1];
                // Sawtooth tiers: wide at the bottom of each, pinched at the top.
                double tierPos = (frac * TIERS) % 1.0;
                double half = tr[2] * (1 - frac) * (0.55 + 0.45 * (1 - tierPos));
                if (e < 0.6) half = Math.max(half, 0.25);   // trunk
                double c = smoothstep(half + 0.35, half - 0.15, Math.abs(col - x))
                        * smoothstep(tr[1] + 0.3, tr[1] - 0.2, e);
                // Snow lies on the top of each tier: the upper part of the
                // sawtooth, where the branches flatten out.
                double s = smoothstep(0.35, 0.65, tierPos) * smoothstep(0.15, 0.4, frac) * load[i];
                if (i < frontStart) {
                    if (c > backCover) { backCover = c; backSnowAmt = s; }
                } else if (c > frontCover) {
                    frontCover = c;
                    frontSnowAmt = s;
                }
            }
            backCover *= 1 - frontCover;
            double treeCover = backCover + frontCover;

            // --- the fox, worked out first so it can cover the rest ----
            // Body, a tail streaming out behind, and a pale tail tip. Three
            // round blobs, which at this resolution is a fox. Nobody's
            // counting its legs.
            double foxBody = 0, foxTip = 0;
            if (foxDir != 0) {
                double body = Math.hypot((col - foxX) / 1.3, (elev - foxE) / 0.5);
                double tail = Math.hypot(col - (foxX - foxDir * 1.9), (elev - foxE - 0.15) / 0.5) / 0.75;
                double tip = Math.hypot(col - (foxX - foxDir * 2.7), (elev - foxE - 0.2) / 0.5) / 0.6;
                foxBody = Math.max(smoothstep(1.0, 0.55, body), smoothstep(1.0, 0.5, tail));
                foxTip = smoothstep(1.0, 0.5, tip) * (1 - foxBody);
            }
            double keep = 1 - Math.min(1, foxBody + foxTip);
            backCover *= keep;
            frontCover *= keep;

            // --- sky, then ground --------------------------------------
            double open = (1 - solid) * (1 - treeCover) * keep;
            if (open > 0) {
                double skyLevel = SKY_LEVEL * (0.75 + 0.35 * (1 - elev / boardHeight));
                budget.add(ref, ColorPalette.ALPINE_SKY, open * skyLevel);
            }
            double ground = solid * (1 - treeCover) * keep;
            if (ground > 0 && !snowy) {
                // Moss and ferns: lumpy, never flat.
                double fern = 0.8 + 0.2 * (2 * valueNoise(col * 1.3 + seed, elev * 1.1) - 1);
                budget.add(ref, ColorPalette.TAIGA_FLOOR, ground * TAIGA_FLOOR_LEVEL * fern);
            } else if (ground > 0) {
                // Snow on the ground in the same blues as the alpine scenes,
                // with the sunlit crust along the top.
                double under = Math.min(depth, 2.5) / 2.5;
                budget.add(ref, ColorPalette.SNOW_SHADE, ground * (GROUND_LEVEL - 0.25 * under));
                double crust = smoothstep(1.0, 0.0, depth);
                if (crust > 0) budget.add(ref, snow, ground * 0.35 * crust);
            }

            // --- the trees ---------------------------------------------
            if (backCover > 0) {
                budget.add(ref, backSpruce, backCover * (1 - backSnowAmt) * TREE_LEVEL * BACK_LEVEL);
                if (backSnowAmt > 0) budget.add(ref, backSnow, backCover * backSnowAmt * TREE_SNOW_LEVEL * BACK_LEVEL);
            }
            if (frontCover > 0) {
                budget.add(ref, spruce, frontCover * (1 - frontSnowAmt) * TREE_LEVEL);
                if (frontSnowAmt > 0) budget.add(ref, snow, frontCover * frontSnowAmt * TREE_SNOW_LEVEL);
            }

            // --- mist and fox (taiga) ----------------------------------
            if (!snowy) {
                double above = elev - surface;
                if (above < MIST_HEIGHT) {
                    double mist = smoothstep(0.40, 0.70,
                            valueNoise((col - MIST_DRIFT * t) * 0.25 + seed, elev * 0.6 + t * 0.05))
                            * smoothstep(MIST_HEIGHT, 0.2, above);
                    budget.add(ref, ColorPalette.TAIGA_MIST, MIST_LEVEL * mist * (1 - 0.6 * frontCover) * keep);
                }
                if (foxBody > 0) budget.add(ref, ColorPalette.FOX, FOX_LEVEL * foxBody);
                if (foxTip > 0) budget.add(ref, ColorPalette.FOX_TAIL_TIP, FOX_LEVEL * foxTip);
            }

            // --- the puff ----------------------------------------------
            if (puffAmt > 0) {
                double d2 = sq(col - puffX) + sq((elev - puffE) * 1.3);
                double puff = Math.exp(-d2 / (puffR * puffR))
                        * smoothstep(0.35, 0.7, valueNoise(col * 1.1 + seed, elev * 1.1 - puffAge * 2.0));
                if (puff > 0.01) budget.add(ref, ColorPalette.SNOW_PLUME, PUFF_LEVEL * puffAmt * puff);
            }

            // --- sparkle -----------------------------------------------
            if (ground > 0.5) {
                double s = 0;
                for (int i = 0; i < SPARKLES; i++) {
                    if (sparkAmt[i] <= 0) continue;
                    double gx = col - sparkX[i], ge = elev - sparkE[i];
                    if (gx * gx + ge * ge < 0.36) s = Math.max(s, sparkAmt[i]);
                }
                if (s > 0) budget.add(ref, ColorPalette.SNOW_SPARKLE, 0.9 * s);
            }
        }

        return budget.resolve();
    }

    /** Ground height at a column, in rows up from under the bottom row. */
    private double ground(double col, double widthKeys) {
        double wobble = valueNoise(col * 0.35 + seed, 5.3) - 0.5;
        if (sloped) return lerp(0.9, 2.6, col / Math.max(1, widthKeys)) + 0.3 * wobble;
        return 1.2 + 0.3 * wobble;
    }

    /**
     * Which front-row tree dumps in a given cycle. Never the same one twice
     * running, because the same tree shaking twice in a row looks like it's
     * stuck, not like the wind picked it.
     */
    private int pickTree(int cycle, int frontCount) {
        int pick = (int) (hash(cycle, 40) * frontCount) % frontCount;
        if (frontCount > 1 && cycle > 0) {
            int prev = (int) (hash(cycle - 1, 40) * frontCount) % frontCount;
            if (pick == prev) pick = (pick + 1) % frontCount;
        }
        return pick;
    }

    /** Back row first, then the front row, each walking left to right with random gaps. */
    private void rollLayout(KeyGrid grid, double widthKeys, double boardHeight) {
        trees.clear();
        double x = -0.5 + hash(0, 20) * 1.5;
        for (int i = 0; x < widthKeys + 1 && i < 48; i++) {
            double height = boardHeight * lerp(heightMin, heightMax, hash(i, 21)) * 0.62;
            trees.add(new double[]{x, height, lerp(1.0, 1.5, hash(i, 22)), 1});
            x += lerp(1.6, 3.0, hash(i, 23));
        }
        frontStart = trees.size();
        x = 0.8 + hash(0, 30) * spacingMin;
        for (int i = 0; x < widthKeys + 1 && i < 48; i++) {
            double height = boardHeight * lerp(heightMin, heightMax, hash(i, 31));
            trees.add(new double[]{x, height, lerp(1.6, 2.3, hash(i, 32)), 0});
            x += lerp(spacingMin, spacingMax, hash(i, 33));
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
