package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plains and meadow: open grass seen from above, with cloud shadows rolling
 * over it and the wind running through it.
 *
 * <p>Replaces a plain {@code shimmer}, which on a green this bright comes out
 * so gentle that the board nearly reads as a solid colour. One of the most
 * common biomes in the game, getting the lighting equivalent of a screensaver
 * someone forgot to turn on.
 *
 * <h2>Looking down, not across</h2>
 * The savanna and the alpine scenes are side views: ground at the bottom, sky
 * at the top, a horizon in between. This one is the field from overhead, so
 * the whole board is grass. A flat open biome has no skyline worth drawing, and
 * what it DOES have is the one thing you only get on flat open ground, which is
 * cloud shadows sliding across it.
 *
 * <h2>What's on the board</h2>
 * <ol>
 *   <li><b>Grass.</b> The profile colour across every key, with a static
 *       clumpy texture so it isn't a flat fill.</li>
 *   <li><b>Cloud shadows.</b> Big soft patches, several keys across, drifting
 *       downwind and dimming the grass under them with a cooler tint: to
 *       about a third of its brightness on the plains, a bit over half in the
 *       meadow. This is the main event. A shadow edge
 *       crossing the board is a large, slow, high-contrast change, which is
 *       precisely the kind of motion an LED shows well and a shimmer doesn't
 *       have.</li>
 *   <li><b>Sheen.</b> Gust patches racing across faster than the clouds, in
 *       the accent colour: grass bending over and catching the sun. Sunlight
 *       only. A gust going into a cloud shadow fades with it (all the way out
 *       under a plains cloud, about two thirds of the way under the meadow's
 *       thinner ones) and comes back on the far side, which does a lot of the
 *       work of selling the shadow as a shadow.</li>
 *   <li><b>Flowers.</b> Single keys in fixed colours, planted once and never
 *       moved. They dim in shade like everything else, and dip briefly when a
 *       gust goes over them.</li>
 *   <li><b>Bees.</b> Meadow only. Small orange points hopping from flower to
 *       flower, hovering on each for a moment. Every tree a meadow generates
 *       comes with a bee nest, so the meadow gets the bees and the plains get
 *       the weather.</li>
 * </ol>
 *
 * <h2>Base is the grass, accent is the sun</h2>
 * Same way round as the sunflower field. The base covers the board and is what
 * {@code BiomeColorEffect} crossfades through, so it has to be the grass. The
 * accent is the light the sheen is made of. The flowers, the bees and the
 * shadow tint are fixed palette colours, because a profile carries two colours
 * and the meadow alone needs seven.
 *
 * <h2>Coordinates</h2>
 * Keys across and rows down, both in key widths, so a cloud is round on the
 * board rather than squashed into a wide flat smear.
 */
public final class GrasslandPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // --- presets -------------------------------------------------------

    /**
     * Plains: heavy weather, sparse flowers, no bees. The clouds are the act
     * here, so they get the most coverage and the deepest shade.
     */
    public static GrasslandPattern plains() {
        return new GrasslandPattern(0.50, 1.0, 1.3, 4.0, 3.0, 0.55,
                new RGBColor[]{ColorPalette.DANDELION, ColorPalette.POPPY, ColorPalette.AZURE_BLUET},
                0, 0.30, 3);
    }

    /**
     * Meadow: fewer, thinner, faster clouds and a lot more flowers, with bees
     * working them. The weather backs off so the field can be the busy part.
     *
     * <p>Sheen comes down because the meadow's green is paler than the plains'.
     * At the plains amount, grass plus a full gust works out to a luminance
     * around 215, well past the rain overlay's blue-grey; at this amount it
     * lands around 196, level with the plains.
     */
    public static GrasslandPattern meadow() {
        return new GrasslandPattern(0.56, 0.65, 1.6, 3.0, 2.0, 0.75,
                new RGBColor[]{ColorPalette.DANDELION, ColorPalette.POPPY, ColorPalette.CORNFLOWER,
                        ColorPalette.ALLIUM, ColorPalette.AZURE_BLUET},
                3, 0.22, 11);
    }

    /** Where the cloud noise starts counting as shadow. Higher is fewer clouds. */
    private final double cloudThreshold;
    /** How dark a full shadow gets, as a fraction of the full drop. */
    private final double cloudStrength;
    /** Cloud drift, keys per second. */
    private final double cloudSpeed;
    /** Flower grid cell, in keys across and rows down. One flower per cell, at most. */
    private final double flowerCellKeys, flowerCellRows;
    /** Chance a cell gets a flower at all. */
    private final double flowerChance;
    private final RGBColor[] flowerColours;
    private final int beeCount;
    private final double sheenLevel;
    private final int seed;

    private GrasslandPattern(double cloudThreshold, double cloudStrength, double cloudSpeed,
                             double flowerCellKeys, double flowerCellRows, double flowerChance,
                             RGBColor[] flowerColours, int beeCount, double sheenLevel, int seed) {
        this.cloudThreshold = cloudThreshold;
        this.cloudStrength = cloudStrength;
        this.cloudSpeed = cloudSpeed;
        this.flowerCellKeys = flowerCellKeys;
        this.flowerCellRows = flowerCellRows;
        this.flowerChance = flowerChance;
        this.flowerColours = flowerColours;
        this.beeCount = beeCount;
        this.sheenLevel = sheenLevel;
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    /**
     * Grass in full sun. Kept under 1 so the sheen has headroom, and so grass
     * plus a full gust of sheen still tops out just under the rain overlay's
     * blue-grey (ColorPalette has the numbers on that one). The flowers and
     * bees go brighter than that, but they're single keys, and rain falling
     * past a dandelion is not the legibility problem a whole bright field is.
     */
    private static final double GRASS_LEVEL = 0.72;
    /** Grass under a full-strength cloud, as a fraction of GRASS_LEVEL. */
    private static final double SHADE_LEVEL = 0.36;
    /** How far toward the shade colour the grass shifts under a cloud. */
    private static final double SHADE_TINT = 0.45;
    private static final double FLOWER_LEVEL = 0.90;
    /** How much grass still shows under a flower. Low, or the flower's hue gets mixed into mud. */
    private static final double FLOWER_GRASS = 0.20;
    /** How far a flower dips when a gust goes over it. */
    private static final double FLOWER_NOD = 0.30;

    // --- clouds --------------------------------------------------------

    /**
     * Noise frequency, per key. 0.14 puts a lattice cell about seven keys
     * across, which comes out as clouds of four to seven keys. Much smaller and
     * they stop being clouds and become dapple, which is the forest's job.
     */
    private static final double CLOUD_SCALE = 0.14;
    /** Width of the soft edge, in noise units. */
    private static final double CLOUD_EDGE = 0.14;

    // --- wind ----------------------------------------------------------

    /** Gust speed, keys per second. Well over the clouds, so the two read as separate things. */
    private static final double GUST_SPEED = 3.6;
    private static final double GUST_SCALE = 0.24;

    // --- bees ----------------------------------------------------------

    /** Seconds per hop, before each bee's own spread. */
    private static final double BEE_HOP_SECONDS = 3.4;
    /** Fraction of each hop spent sitting on the flower. The rest is flying. */
    private static final double BEE_HOVER = 0.45;
    /** How far the flight path bows out sideways, as a fraction of its length. Bees do not fly straight. */
    private static final double BEE_ARC = 0.35;
    private static final double BEE_RADIUS = 1.1;
    private static final double BEE_CORE_LEVEL = 0.60;
    private static final double BEE_HALO_LEVEL = 0.40;

    private KeyGrid layoutFor;
    private final Map<KeyGrid.LedRef, Flower> flowers = new HashMap<>();
    private final List<Flower> flowerList = new ArrayList<>();

    private record Flower(RGBColor colour, double col, double row) {
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor grass = ctx.baseColor();
        RGBColor sun = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        if (layoutFor != grid) rollLayout(grid, keyWidth, rowStep, minY, maxX / keyWidth, lastRow);

        // One breath of wind for the whole field, so the gusts come in sets
        // instead of at one constant rate forever.
        double gust = 0.6 + 0.4 * Math.sin(TAU * t / 7.1) * Math.sin(TAU * t / 2.9 + 0.4);

        // Bees first, because the grass under them needs to know they're there.
        double[][] bees = new double[flowerList.isEmpty() ? 0 : beeCount][];
        for (int i = 0; i < bees.length; i++) bees[i] = beePosition(i, t, lastRow);
        Map<KeyGrid.LedRef, Double> beeLight = new HashMap<>();
        for (double[] bee : bees) lightBee(grid, keyWidth, rowStep, minY, bee, beeLight);

        RGBColor shadeColour = grass.lerp(ColorPalette.GRASS_SHADE, SHADE_TINT);
        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double row = (key.y() - minY) / rowStep;
            KeyGrid.LedRef ref = key.ref();

            double shadow = cloudStrength * shadowAt(col, row, t);
            double light = 1.0 - (1.0 - SHADE_LEVEL) * shadow;
            double sheen = smoothstep(0.58, 0.85,
                    valueNoise((col - GUST_SPEED * t) * GUST_SCALE + seed, row * 0.45 + t * 0.15)) * gust;

            Flower flower = flowers.get(ref);
            double bee = Math.min(1.0, beeLight.getOrDefault(ref, 0.0));
            double cover = Math.max(flower != null ? 1.0 - FLOWER_GRASS : 0.0, 0.8 * bee);

            // --- grass -------------------------------------------------
            double texture = 0.85 + 0.15 * (2 * valueNoise(col * 0.9 + seed * 3, row * 0.9) - 1);
            budget.add(ref, grass.lerp(shadeColour, shadow), GRASS_LEVEL * texture * light * (1 - cover));
            // Sheen is sunlight off the blades, so it goes with the shadow's full
            // strength rather than the grass's softer dimming. Under a plains
            // cloud that's all of it.
            double sunlit = 1.0 - shadow;
            budget.add(ref, sun, sheenLevel * sheen * sunlit * (1 - cover));

            // --- flower ------------------------------------------------
            if (flower != null) {
                budget.add(ref, flower.colour(), FLOWER_LEVEL * light * (1 - FLOWER_NOD * sheen));
            }

            // --- bee ---------------------------------------------------
            // Not shaded. It's the smallest thing on the board and the only one
            // going anywhere, and a bee that disappears every time a cloud goes
            // over is a bee nobody ever notices.
            if (bee > 0) budget.add(ref, ColorPalette.BEE, bee);
        }

        return budget.resolve();
    }

    /** 0 in full sun, 1 under the middle of a cloud. */
    private double shadowAt(double col, double row, double t) {
        double u = col - cloudSpeed * t;
        // Two octaves. The big one is the cloud; the small one ragged-edges it
        // and drifts on its own clock so the clouds change shape as they go
        // instead of sliding past like cardboard cutouts.
        double n = 0.7 * valueNoise(u * CLOUD_SCALE + seed, row * CLOUD_SCALE * 1.3 + 0.37)
                + 0.3 * valueNoise(u * CLOUD_SCALE * 2.3 + seed * 3, row * CLOUD_SCALE * 2.6 + t * 0.07);
        return smoothstep(cloudThreshold, cloudThreshold + CLOUD_EDGE, n);
    }

    /**
     * Where a bee is right now, in keys and rows.
     *
     * <p>Stateless, like everything else here. Each bee's life is cut into hops;
     * hop {@code k} goes from flower {@code k} to flower {@code k + 1}, both
     * picked by hash, so the end of one hop is always the start of the next
     * and nothing has to be remembered between frames. If the hash picks the
     * same flower twice in a row the bee just sits on it for an extra hop,
     * which is behaviour a real bee would sign off on.
     */
    private double[] beePosition(int bee, double t, double lastRow) {
        double period = BEE_HOP_SECONDS * (0.8 + 0.4 * hash(bee, 40));
        double clock = t / period + 7 * hash(bee, 41);
        int hop = (int) Math.floor(clock);
        double u = clock - hop;
        Flower from = flowerList.get(pick(bee, hop));
        Flower to = flowerList.get(pick(bee, hop + 1));

        double x = from.col(), y = from.row();
        if (u > BEE_HOVER) {
            double s = smoothstep(0, 1, (u - BEE_HOVER) / (1 - BEE_HOVER));
            double dx = to.col() - from.col(), dy = to.row() - from.row();
            // Bow the path out sideways, to a side picked fresh each hop, so a
            // flight is a curve and not a laser.
            double side = hash(bee * 31 + hop, 42) < 0.5 ? -1 : 1;
            double bow = BEE_ARC * side * Math.sin(Math.PI * s);
            x += dx * s - dy * bow;
            y += dy * s + dx * bow;
        }
        // Always a little wobble, sitting or flying. A perfectly still dot is a
        // flower, not a bee.
        x += 0.22 * Math.sin(TAU * t * 2.7 + bee * 1.9);
        y += 0.15 * Math.sin(TAU * t * 3.9 + bee * 4.1);
        return new double[]{x, Math.max(0, Math.min(lastRow, y))};
    }

    private int pick(int bee, int hop) {
        int n = flowerList.size();
        return Math.min(n - 1, (int) (latticeHash(bee * 7919 + seed * 131, hop) * n));
    }

    /**
     * Lights one bee: a halo that falls off over about a key, plus the nearest
     * key lit outright. Without that core a bee sitting between two keys
     * splits into two half-bright keys and stops reading as a point, which is
     * the same trick the desert gusts use for the same reason.
     */
    private static void lightBee(KeyGrid grid, double keyWidth, double rowStep, double minY,
                                 double[] bee, Map<KeyGrid.LedRef, Double> out) {
        KeyGrid.LedRef nearest = null;
        double best = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() / keyWidth - bee[0], (key.y() - minY) / rowStep - bee[1]);
            if (d < best) {
                best = d;
                nearest = key.ref();
            }
            if (d < BEE_RADIUS) out.merge(key.ref(), BEE_HALO_LEVEL * smoothstep(BEE_RADIUS, 0, d), Double::sum);
        }
        if (nearest != null) out.merge(nearest, BEE_CORE_LEVEL, Double::sum);
    }

    /**
     * Plants the flowers. The board's cut into cells and each cell gets at most
     * one, jittered inside it, then snapped to the nearest key. Rolling them
     * freely clumps, and a field with four poppies stacked in one corner and
     * nothing anywhere else looks like a rendering bug, not a field.
     */
    private void rollLayout(KeyGrid grid, double keyWidth, double rowStep, double minY,
                            double widthKeys, double lastRow) {
        flowers.clear();
        flowerList.clear();
        int cellsX = Math.max(1, (int) Math.round((widthKeys + 1) / flowerCellKeys));
        int cellsY = Math.max(1, (int) Math.round((lastRow + 1) / flowerCellRows));
        double cellW = (widthKeys + 1) / cellsX;
        double cellH = (lastRow + 1) / cellsY;
        int n = 0;
        for (int cy = 0; cy < cellsY; cy++) {
            for (int cx = 0; cx < cellsX; cx++, n++) {
                if (hash(n, 20) >= flowerChance) continue;
                double col = (cx + 0.2 + 0.6 * hash(n, 21)) * cellW - 0.5;
                double row = (cy + 0.2 + 0.6 * hash(n, 22)) * cellH - 0.5;

                KeyGrid.LedPosition nearest = null;
                double best = Double.MAX_VALUE;
                for (KeyGrid.LedPosition key : grid.allKeys()) {
                    double d = Math.hypot(key.x() / keyWidth - col, (key.y() - minY) / rowStep - row);
                    if (d < best) {
                        best = d;
                        nearest = key;
                    }
                }
                if (nearest == null || flowers.containsKey(nearest.ref())) continue;
                RGBColor colour = flowerColours[(int) (hash(n, 23) * flowerColours.length) % flowerColours.length];
                // Stored at the key's own position, so a bee lands on the flower
                // and not on the spot the flower was aiming for before it snapped.
                Flower f = new Flower(colour, nearest.x() / keyWidth, (nearest.y() - minY) / rowStep);
                flowers.put(nearest.ref(), f);
                flowerList.add(f);
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

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return v * v * (3 - 2 * v);
    }

    /** Same lattice hash the canopy and the savanna use; see CanopyDapplePattern for the notes. */
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
