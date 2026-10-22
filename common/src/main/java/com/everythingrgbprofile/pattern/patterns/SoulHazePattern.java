package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The soul sand valley. Teal fog rolling through the dark, with patches of
 * soul fire burning along the floor and the odd wisp lifting off them.
 *
 * <p>This replaced a {@code pulse-slow} on a grey-teal, which is to say the
 * whole board breathing in unison in a colour best described as "wet
 * concrete". Technically a soul sand valley. Spiritually a car park.
 *
 * <h2>Why there is no brown</h2>
 * The ground in there is brown, and the ground is the one thing this board
 * does not try to draw. Soul sand is a dark, low-saturation brown, and a
 * keyboard LED cannot do dark and desaturated at the same time: turn it down
 * far enough to be dark and it reads as off, keep it bright enough to see and
 * it reads as orange. Either way the Nether's red biomes are right next door,
 * already doing orange, much louder.
 *
 * <p>What you actually remember from the valley is the air, so the board is
 * the air. The base colour is the biome's own fog colour, {@code 1787717} in
 * its biome file, which is {@code #1B4745}. The accent is soul fire. Everything
 * in between is a blend of those two, so recolouring the profile recolours the
 * whole scene rather than leaving a hard-coded teal stranded in it.
 *
 * <h2>Three layers, in the order they are drawn</h2>
 * <ol>
 *   <li><b>Haze.</b> Two octaves of value noise, stretched wide, drifting
 *       sideways and slightly upward. The top rows are held darker than the
 *       bottom ones, because the valley has a Nether ceiling over it and the
 *       glow sits low, near the fires. Without that falloff it reads as
 *       generic underwater, and the oceans already have that covered.</li>
 *   <li><b>Soul fire.</b> A few patches along the bottom rows, each one a row
 *       of flame tongues whose heights flicker independently. Patches fade out
 *       and come back somewhere else on a slow cycle, which is roughly what
 *       walking past them looks like.</li>
 *   <li><b>Wisps.</b> Small sparks that rise out of a patch and dissolve
 *       partway up. Nothing bright actually comes off a soul fire block in
 *       vanilla, only {@code large_smoke}, so these are mood and not a
 *       particle. The smoke is grey, and so is {@code ash}, the one ambient
 *       particle the biome has, and grey specks drifting through the fog
 *       would be putting back exactly the colour this class exists to get rid
 *       of.</li>
 * </ol>
 *
 * <h2>No state</h2>
 * Same as every other scene in this package. Patch positions, flicker and
 * wisps are all pure functions of elapsed time and an index, so there is
 * nothing to reseed when the effect clock restarts.
 */
public final class SoulHazePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    private final int patchCount;
    private final int wispCount;
    /**
     * How bright the thinnest fog is, as a fraction of the thickest. High on
     * purpose: the fog colour is already dark, and multiplying a dark colour
     * by a small number gets you an LED that is technically on.
     */
    private final double hazeFloor;

    private SoulHazePattern(int patchCount, int wispCount, double hazeFloor) {
        this.patchCount = Math.max(1, patchCount);
        this.wispCount = wispCount;
        this.hazeFloor = hazeFloor;
    }

    /** Noise cells across the board, and how much wider than tall each one is. */
    private static final double HAZE_SCALE = 2.4;
    private static final double HAZE_FLATTEN = 1.8;
    /** Fog drift, in noise cells per second. Mostly sideways, a little up. */
    private static final double HAZE_DRIFT_X = 0.07;
    private static final double HAZE_DRIFT_Y = 0.025;
    /** Above 1 thins the fog out into banks with clearer air between them. */
    private static final double HAZE_SHARPNESS = 1.5;
    /**
     * How far the thick fog leans from the fog colour toward soul fire. The
     * lit haze in the valley is noticeably bluer and brighter than its fog
     * colour, because it is fog with soul fire behind it.
     */
    private static final double HAZE_TINT = 0.55;
    /** Brightness of the top row relative to the bottom one. The ceiling. */
    private static final double CEILING_DEPTH = 0.55;

    /** Half-width of a fire patch, in key widths, before its random stretch. */
    private static final double PATCH_HALF_WIDTH_KEYS = 2.2;
    /** How far up the board the tallest tongue can reach, as a fraction of its height. */
    private static final double FLAME_HEIGHT = 0.42;
    /** Seconds a patch stays before fading out and turning up somewhere else. */
    private static final double PATCH_PERIOD_SECONDS = 18.0;
    /** How fast the tongues flicker. Fire, not candle. */
    private static final double FLICKER_HZ = 5.5;

    /** Wisp halo size in key widths, and its timing. */
    private static final double WISP_RADIUS_KEYS = 1.2;
    private static final double WISP_PERIOD_SECONDS = 7.0;
    /** The fraction of each wisp's period it is actually in the air. */
    private static final double WISP_ACTIVE = 0.45;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        RGBColor fog = ctx.baseColor();
        RGBColor soulFire = ctx.resolvedAccentColor();
        RGBColor haze = fog.lerp(soulFire, HAZE_TINT);
        RGBColor flameCore = soulFire.lightened(0.35);
        RGBColor wispColor = soulFire.lightened(0.5);

        LightBudget budget = new LightBudget();

        // --- the fog --------------------------------------------------------
        // A very slow swell over the whole thing, small enough that nobody
        // could call it a pulse. The previous version of this biome WAS a
        // pulse, and one is plenty.
        double swell = 0.92 + 0.08 * Math.sin(TAU * 0.045 * t);
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            // Adding time to y scrolls the noise upward: a feature sitting at
            // noise coordinate n shows up at y = n - drift * t, which shrinks.
            double hx = key.x() * HAZE_SCALE + t * HAZE_DRIFT_X;
            double hy = key.y() * HAZE_SCALE / HAZE_FLATTEN + t * HAZE_DRIFT_Y;
            // The fine octave drifts the other way a little, so the fog churns
            // instead of sliding past as one rigid sheet.
            double field = 0.65 * valueNoise(hx, hy)
                    + 0.35 * valueNoise(hx * 2.3 - t * 0.05, hy * 2.3 + 11.0);
            double thick = Math.pow(clamp01(field), HAZE_SHARPNESS);

            double depth = CEILING_DEPTH + (1.0 - CEILING_DEPTH) * smoothstep(0.0, 0.8, key.y());
            RGBColor color = fog.lerp(haze, thick);
            budget.add(key.ref(), color, (hazeFloor + (1.0 - hazeFloor) * thick) * depth * swell);
        }

        // --- soul fire ------------------------------------------------------
        // Worked out up front because the wisps need to know where the fires
        // are, and how lit they currently are, to rise out of them.
        double[] patchX = new double[patchCount];
        double[] patchLight = new double[patchCount];
        double halfWidthBase = PATCH_HALF_WIDTH_KEYS * keyWidth;
        for (int i = 0; i < patchCount; i++) {
            double cyclePos = t / PATCH_PERIOD_SECONDS * (0.8 + 0.4 * hash(i * 7 + 2)) + hash(i * 13 + 1);
            int cycle = (int) Math.floor(cyclePos);
            double progress = cyclePos - cycle;
            // Stratified across the width, re-rolled every cycle. The cycle
            // number goes into the hash so each return lands somewhere new;
            // the stratification keeps two patches from ever piling up in the
            // same corner and leaving the rest of the floor unlit.
            double px = (i + 0.15 + 0.70 * hash(i * 31 + cycle * 97 + 3)) / patchCount;
            double halfWidth = halfWidthBase * (0.75 + 0.5 * hash(i * 17 + cycle * 53 + 5));
            // Slow in, slow out. A fire that pops into existence looks like a
            // rendering bug; one that swells up looks like you walked round a
            // corner.
            double light = progress < 0.15
                    ? progress / 0.15
                    : Math.min(1.0, (1.0 - progress) / 0.20);
            patchX[i] = px;
            patchLight[i] = light;
            if (light <= 0.02) continue;

            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double dx = Math.abs(key.x() - px);
                if (dx >= halfWidth) continue;
                // 0 on the bottom edge, 1 at the tallest a tongue can go.
                double rise = (1.0 - key.y()) / FLAME_HEIGHT;
                if (rise >= 1.0) continue;

                // Noise across the columns and scrolling up through time, so
                // each column's tongue gets taller and shorter on its own
                // schedule and the flicker visibly travels upward. Offset by
                // patch so two fires never flicker in sync.
                double flicker = valueNoise(key.x() / keyWidth * 0.9 + i * 17.0,
                        t * FLICKER_HZ - key.y() * 6.0);
                // Tallest in the middle of the patch, stubby at the edges.
                double reach = (1.0 - dx / halfWidth) * (0.55 + 0.45 * flicker);
                if (rise >= reach) continue;

                // Hottest at the root of the tongue, cooling toward its tip.
                double heat = 1.0 - rise / reach;
                RGBColor color = soulFire.lerp(flameCore, heat * heat);
                budget.add(key.ref(), color, light * (0.35 + 0.65 * heat) * (0.75 + 0.25 * flicker));
            }
        }

        // --- wisps ----------------------------------------------------------
        double wispRadius = WISP_RADIUS_KEYS * keyWidth;
        for (int j = 0; j < wispCount; j++) {
            int patch = j % patchCount;
            if (patchLight[patch] <= 0.02) continue;

            double period = WISP_PERIOD_SECONDS * (0.85 + 0.3 * hash(j * 41 + 3));
            double progress = frac(t / period + hash(j * 43 + 7));
            // Most of the period is spent grounded. A wisp every second or two
            // across the whole board is about right; constantly would be a
            // snow globe.
            if (progress >= WISP_ACTIVE) continue;
            double p = progress / WISP_ACTIVE;

            // Leaves from partway up the flames and dissolves before the top
            // third, which is up in the dark where the haze stops too.
            double wy = 1.0 - FLAME_HEIGHT * 0.6 - p * 0.7;
            double wx = patchX[patch] + (hash(j * 47 + 1) - 0.5) * 0.04
                    + 0.03 * p * Math.sin(TAU * 0.6 * t + j * 2.1);
            double envelope = patchLight[patch] * (p < 0.15 ? p / 0.15 : (1.0 - p) / 0.85);
            if (envelope <= 0.02) continue;

            // Nearest key at strength, then a halo. The glow motes explain why
            // the halo on its own is not enough for something this small.
            KeyGrid.LedPosition core = nearestKey(grid, wx, wy);
            if (core != null) budget.add(core.ref(), wispColor, envelope * 0.8);

            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double d = Math.hypot(key.x() - wx, (key.y() - wy) * aspect);
                if (d >= wispRadius) continue;
                double falloff = 1.0 - d / wispRadius;
                budget.add(key.ref(), wispColor, falloff * falloff * envelope * 0.4);
            }
        }

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static double smoothstep(double edge0, double edge1, double v) {
        double x = clamp01((v - edge0) / (edge1 - edge0));
        return x * x * (3 - 2 * x);
    }

    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    /** Same lattice hash the canopy uses; see CanopyDapplePattern for the notes. */
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

    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double nx, double ny) {
        KeyGrid.LedPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - nx, key.y() - ny);
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        return best;
    }

    /**
     * The soul sand valley.
     *
     * <p>Four patches is enough that the floor is rarely dark end to end, and
     * few enough that each one reads as a fire rather than the bottom row just
     * being cyan. Three wisps between them keeps something rising most of the
     * time without the air ever getting busy.
     */
    public static SoulHazePattern soulSandValley() {
        return new SoulHazePattern(4, 3, 0.70);
    }
}
