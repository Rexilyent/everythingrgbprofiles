package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The Sift: coral mesas under a teal sky, frost-capped trees in front of them,
 * an aurora hanging overhead and glowing motes drifting through all of it.
 *
 * <p>Built for a dimension that is not in the game yet. Everything here comes
 * from the promotional screenshots, which is the only reference that exists,
 * so treat the colours as a first pass to re-check against the real textures
 * the day it ships. The whole thing sits behind {@code Feature.SIFT}, which
 * keeps it out of release builds until then.
 *
 * <h2>Two hues and a lot of frost</h2>
 * Sampled across all three reference shots, the place is almost entirely two
 * colours: a coral pink at hue 350-ish on the mesas and the grass, and a teal
 * at hue 173-190 on the sky, the trees and the ground cover. Everything else
 * is frost (the snow-heavy canopies, the portal glow) or the aurora's pink and
 * green ribbons.
 *
 * <p>That is unusually good news for a keyboard. Coral and teal sit close to
 * opposite each other on the wheel, so the two read apart at a glance even at
 * the same brightness, which most biomes cannot say. What has to be avoided is
 * ever BLENDING them: a lerp from teal to coral passes straight through grey
 * on the way, which is why every layer below picks one hue and stays in it.
 *
 * <p>Base is the coral of the mesas, accent the teal of the sky. Frost, the
 * aurora, the motes and the critter are fixed {@link ColorPalette} colours,
 * because a profile has two slots and the scene needs about eight.
 *
 * <h2>The layers, back to front</h2>
 * <ol>
 *   <li><b>Sky.</b> Teal, paling slightly toward the horizon.</li>
 *   <li><b>Aurora.</b> A few ribbons hanging from the top of the board,
 *       striated like curtains, drifting sideways and breathing slowly. Pink
 *       and green, alternating, and only ever drawn over sky.</li>
 *   <li><b>Mesas.</b> Coral columns with wider flat caps, the shape every one
 *       of the reference shots is full of, lit along the top of the cap.</li>
 *   <li><b>Frost trees.</b> Canopies in front of the mesas, frost on top and
 *       teal underneath, swaying a little on their trunks.</li>
 *   <li><b>Grass.</b> The bottom row, pink, with wind running through it.</li>
 *   <li><b>Motes and the critter.</b> Glowing cyan motes wandering the whole
 *       board, and every so often one of the little blue cube creatures from
 *       the screenshots hopping along the grass.</li>
 * </ol>
 *
 * <p>The occluding layers (mesas, trees, grass) are decided per key rather
 * than added, because they are solid things standing in front of each other
 * and light does not pass through a mesa. The motes and the critter are added
 * on top, since they glow.
 *
 * <h2>No state</h2>
 * Every layer is a closed-form function of elapsed time and a fixed per-slot
 * hash, the same as the sulfur cave. The menu clock goes back to zero every
 * time somebody quits to the title screen, and there is nothing in here to go
 * stale when it does.
 */
public final class SiftPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // --- presets -------------------------------------------------------

    /**
     * The title-screen theme. Slow, so it can sit behind a menu for an hour
     * without becoming the thing you notice: everything runs at the speed the
     * scene would have if you were standing still in it looking out.
     */
    public static SiftPattern menu() {
        return new SiftPattern(0.42, 4, 3, 7, 0.8, 17);
    }

    // --- layout --------------------------------------------------------

    /** Where the sky gives way to land, as a share of the board's height from the top. */
    private final double horizon;
    private final int mesaCount;
    private final int treeCount;
    private final int moteCount;
    /** Multiplies every clock in here. Below 1 for the menu. */
    private final double speed;
    private final int seed;

    /** Where the grass starts, as a share of the board's height. The bottom row and a little above it. */
    private static final double GROUND_LINE = 0.90;

    // --- levels --------------------------------------------------------

    private static final double SKY_LEVEL = 0.46;
    /** How much paler the sky gets toward the horizon, as a share of the way to frost. */
    private static final double SKY_HAZE = 0.22;
    private static final double MESA_LEVEL = 0.80;
    /** The lit top of a mesa's cap, on top of its body. */
    private static final double MESA_RIM_LIFT = 0.35;
    private static final double TREE_LEVEL = 0.85;
    private static final double TRUNK_LEVEL = 0.40;
    private static final double GRASS_LEVEL = 0.85;
    private static final double AURORA_LEVEL = 0.95;
    private static final double MOTE_LEVEL = 1.1;
    private static final double CRITTER_LEVEL = 1.25;

    // --- aurora --------------------------------------------------------

    private static final int RIBBONS = 3;
    /** Half-width of a ribbon, in key widths. */
    private static final double RIBBON_HALF_KEYS = 2.4;
    /** Seconds for a ribbon's slow sideways drift and its breathing. */
    private static final double RIBBON_DRIFT_SECONDS = 41.0;
    private static final double RIBBON_BREATH_SECONDS = 13.0;

    // --- motes and the critter ------------------------------------------

    /** Glow radius around a mote, in key widths. */
    private static final double MOTE_RADIUS_KEYS = 1.35;
    /** Seconds between the critter's crossings, and how long one crossing takes. */
    private static final double CRITTER_EVERY = 19.0;
    private static final double CRITTER_CROSSING = 6.5;
    private static final int CRITTER_HOPS = 7;
    /** How high a hop gets, in rows. */
    private static final double CRITTER_HOP_ROWS = 1.1;
    private static final double CRITTER_RADIUS_KEYS = 0.95;

    private SiftPattern(double horizon, int mesaCount, int treeCount, int moteCount, double speed, int seed) {
        this.horizon = horizon;
        this.mesaCount = Math.max(1, mesaCount);
        this.treeCount = Math.max(0, treeCount);
        this.moteCount = Math.max(0, moteCount);
        this.speed = speed;
        this.seed = seed;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyX = Math.max(1e-4, grid.keyWidthNormalised());
        double keyY = keyX / aspect;
        double t = elapsedMillis / 1000.0 * speed;

        RGBColor mesa = ctx.baseColor();
        RGBColor sky = ctx.resolvedAccentColor();
        RGBColor mesaRim = mesa.lerp(ColorPalette.SIFT_FROST, 0.30);
        RGBColor haze = sky.lerp(ColorPalette.SIFT_FROST, SKY_HAZE);

        LightBudget budget = new LightBudget();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double x = key.x();
            double y = key.y();

            // --- the solid things, front to back; the first hit wins -----
            if (y >= GROUND_LINE) {
                // Wind through the grass: a slow wave travelling across the
                // row, so the ground moves without anything on it moving.
                double sway = 0.80 + 0.20 * Math.sin(TAU * (t * 0.23 - x * 2.6));
                budget.add(key.ref(), ColorPalette.SIFT_GRASS, GRASS_LEVEL * sway);
                continue;
            }
            int tree = treeAt(x, y, t, keyX, keyY);
            if (tree != 0) {
                if (tree == 1) budget.add(key.ref(), ColorPalette.SIFT_FROST, TREE_LEVEL);
                else if (tree == 2) budget.add(key.ref(), ColorPalette.SIFT_TREE, TREE_LEVEL);
                else budget.add(key.ref(), ColorPalette.SIFT_TREE, TRUNK_LEVEL);
                continue;
            }
            int m = mesaAt(x, y, keyX, keyY);
            if (m != 0) {
                // Faint horizontal banding, because the reference mesas are
                // stacked layers of rock rather than smooth columns.
                double band = 0.90 + 0.10 * Math.sin(y * 34.0 + seed);
                if (m == 2) budget.add(key.ref(), mesaRim, (MESA_LEVEL + MESA_RIM_LIFT) * band);
                else budget.add(key.ref(), mesa, MESA_LEVEL * band);
                continue;
            }

            // --- sky, and the aurora in it --------------------------------
            double toHorizon = Math.min(1, y / Math.max(0.05, horizon));
            budget.add(key.ref(), sky.lerp(haze, toHorizon), SKY_LEVEL);
            if (y < horizon) aurora(budget, key, x, y, t, keyX);
        }

        motes(budget, grid, t, keyX, keyY);
        critter(budget, grid, t, keyX, keyY);
        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Layers
    // ---------------------------------------------------------------

    /**
     * The aurora over one key of sky. Ribbons hang from the top: strongest
     * there, fading toward the horizon, with vertical striations that drift
     * sideways at their own pace so the curtain ripples instead of sliding.
     */
    private void aurora(LightBudget budget, KeyGrid.LedPosition key, double x, double y, double t, double keyX) {
        double fade = 1.0 - y / horizon;
        for (int r = 0; r < RIBBONS; r++) {
            double home = (r + 0.5) / RIBBONS + 0.10 * (hash(r, 1) - 0.5);
            double centre = home + 0.07 * Math.sin(TAU * (t / RIBBON_DRIFT_SECONDS + hash(r, 2)));
            double d = (x - centre) / (RIBBON_HALF_KEYS * keyX);
            if (Math.abs(d) > 2.5) continue;
            double across = Math.exp(-d * d);
            double striae = 0.55 + 0.45 * Math.sin(TAU * (x * 7.0 + t * (0.05 + 0.02 * r) + hash(r, 3)));
            double breath = 0.55 + 0.45 * Math.sin(TAU * (t / RIBBON_BREATH_SECONDS + hash(r, 4)));
            double level = AURORA_LEVEL * across * striae * breath * fade;
            if (level < 0.01) continue;
            RGBColor colour = r % 2 == 0 ? ColorPalette.SIFT_AURORA_GREEN : ColorPalette.SIFT_AURORA_PINK;
            budget.add(key.ref(), colour, level);
        }
    }

    /**
     * Which part of a mesa, if any, is at this point: 0 none, 1 body, 2 the
     * lit top of its cap.
     *
     * <p>Spread across the width one per slot, so they never pile into one
     * corner, and each with its own height so the skyline is ragged. The cap
     * overhangs the column on both sides, which is the silhouette that makes
     * them read as the Sift's rock rather than as generic pillars.
     */
    private int mesaAt(double x, double y, double keyX, double keyY) {
        for (int i = 0; i < mesaCount; i++) {
            double cx = (i + 0.5) / mesaCount + 0.12 / mesaCount * (hash(i, 10) * 2 - 1);
            double stemHalf = (0.55 + 0.45 * hash(i, 11)) * keyX;
            double capHalf = stemHalf + (0.6 + 0.5 * hash(i, 12)) * keyX;
            double top = 0.14 + (horizon - 0.04) * hash(i, 13);
            double capBottom = top + 0.95 * keyY;
            double dx = Math.abs(x - cx);
            if (y < top) continue;
            if (y < capBottom) {
                if (dx > capHalf) continue;
                return y < top + 0.45 * keyY ? 2 : 1;
            }
            if (dx <= stemHalf) return 1;
        }
        return 0;
    }

    /**
     * Which part of a frost tree, if any, is at this point: 0 none, 1 frost on
     * top of the canopy, 2 the teal underside, 3 the trunk.
     *
     * <p>The trees sit between the mesas, a half-slot over, so the two layers
     * interleave instead of one hiding the other. Each canopy sways on its
     * trunk on its own clock.
     */
    private int treeAt(double x, double y, double t, double keyX, double keyY) {
        for (int i = 0; i < treeCount; i++) {
            double base = (i + 1.0) / (treeCount + 1) + 0.08 * (hash(i, 20) - 0.5);
            double sway = 0.18 * keyX * Math.sin(TAU * (t / (7.0 + 2.0 * hash(i, 21))) + hash(i, 22) * TAU);
            double cx = base + sway;
            double cy = horizon + 0.06 + 0.12 * hash(i, 23);
            double rx = (1.6 + 0.6 * hash(i, 24)) * keyX;
            double ry = 0.85 * keyY;
            double u = (x - cx) / rx;
            double v = (y - cy) / ry;
            if (u * u + v * v <= 1.0) {
                // Frost on the upper half, and drooping a little lower in the
                // middle, the way snow sits on a rounded crown.
                double frostLine = 0.15 - 0.25 * u * u;
                return v < frostLine ? 1 : 2;
            }
            if (y > cy && y < GROUND_LINE && Math.abs(x - base) <= 0.32 * keyX) return 3;
        }
        return 0;
    }

    /**
     * Glowing motes wandering the whole board: the cyan specks that fill the
     * air in every one of the reference shots. Each follows its own slow
     * looping path and twinkles on its own clock. Additive, so a mote passing
     * in front of a mesa brightens it rather than replacing it.
     */
    private void motes(LightBudget budget, KeyGrid grid, double t, double keyX, double keyY) {
        double radius = MOTE_RADIUS_KEYS;
        for (int i = 0; i < moteCount; i++) {
            double px = (i + 0.5) / moteCount + 0.35 / moteCount * Math.sin(TAU * (t / (23.0 + 9.0 * hash(i, 30)) + hash(i, 31)));
            double py = 0.12 + 0.70 * (0.5 + 0.5 * Math.sin(TAU * (t / (17.0 + 7.0 * hash(i, 32)) + hash(i, 33))));
            double twinkle = 0.5 + 0.5 * Math.sin(TAU * (t / (2.6 + 1.4 * hash(i, 34)) + hash(i, 35)));
            twinkle *= twinkle;
            if (twinkle < 0.02) continue;
            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double d = Math.hypot((key.x() - px) / keyX, (key.y() - py) / keyY);
                if (d >= radius) continue;
                // The rounder falloff from LevelUpPattern, so a mote sitting
                // between two keys lights both rather than neither.
                budget.add(key.ref(), ColorPalette.SIFT_MOTE, MOTE_LEVEL * twinkle * Math.sqrt(1 - d / radius));
            }
        }
    }

    /**
     * Every so often one of the little blue cube creatures from the
     * screenshots hops along the grass, left to right and then back the next
     * time. Nothing else on the board moves like it, which is the point: it is
     * the scene's one event, and it is small enough not to become the thing
     * the menu is about.
     */
    private void critter(LightBudget budget, KeyGrid grid, double t, double keyX, double keyY) {
        double cycle = t / CRITTER_EVERY;
        long pass = (long) Math.floor(cycle);
        double into = (cycle - pass) * CRITTER_EVERY;
        if (into > CRITTER_CROSSING) return;
        double p = into / CRITTER_CROSSING;
        double along = -0.06 + 1.12 * p;
        double cx = (pass & 1) == 0 ? along : 1.0 - along;
        double hop = Math.abs(Math.sin(Math.PI * CRITTER_HOPS * p));
        double cy = 1.0 - hop * CRITTER_HOP_ROWS * keyY;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot((key.x() - cx) / keyX, (key.y() - cy) / keyY);
            if (d >= CRITTER_RADIUS_KEYS) continue;
            budget.add(key.ref(), ColorPalette.SIFT_CRITTER, CRITTER_LEVEL * Math.sqrt(1 - d / CRITTER_RADIUS_KEYS));
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** A fixed pseudo-random number in [0, 1) for a slot and a salt, mixed with this preset's seed. */
    private double hash(int index, int salt) {
        long h = (index * 0x9E3779B97F4A7C15L) ^ ((salt + 1L) * 0xC2B2AE3D27D4EB4FL) ^ (seed * 0x165667B19E3779F9L);
        h ^= (h >>> 31);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }
}
