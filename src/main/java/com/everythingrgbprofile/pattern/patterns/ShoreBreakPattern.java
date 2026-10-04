package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.List;
import java.util.Map;

/**
 * Beach: sand across the board with the sea washing up it and draining back.
 *
 * <p>Replaces a plain {@code shimmer} at {@code #E8DBA0}, which is a cream
 * wash that breathes. That is not a beach, it is a beige keyboard. The one
 * thing everybody actually pictures when they think of standing on a beach is
 * the water coming in and going out again, and a shimmer has no way to say it.
 *
 * <h2>How the board is laid out</h2>
 * The shoreline runs along the board, not across it. Top rows are dry sand,
 * the bottom row or two are open water, and the swash zone in between is
 * where everything happens.
 *
 * <p>Laying it out the other way — sea on the left, sand on the right, waves
 * running sideways — gives the wave eighteen columns to travel through
 * instead of six rows, which sounds better and is worse. Water arriving from
 * the side of a keyboard reads as a sweep effect. Water arriving from the
 * edge nearest you reads as the tide, because that is the direction it comes
 * from when you are standing in it.
 *
 * <h2>Four zones, and the one that sells it</h2>
 * <ol>
 *   <li><b>Dry sand</b>, the back of the beach. Grain texture, no motion. It
 *       is the thing the water is moving against, so it holds still.</li>
 *   <li><b>Wet sand</b>, and this is the layer doing the work. Sand the water
 *       has just covered stays dark and saturated and then dries out over
 *       several seconds. Without it the wave slides up and down a static
 *       background like a wipe in a slideshow; with it the beach remembers
 *       where the water has been, which is exactly what a real one does.</li>
 *   <li><b>Foam</b> at the waterline, the brightest thing on the board and
 *       the only hard edge.</li>
 *   <li><b>Water</b> below it, rippling gently so the sea is not a flat
 *       block of blue.</li>
 * </ol>
 *
 * <h2>Why the waves are not on a timer</h2>
 * Two swells of different period running at once, 6.3 and 9.1 seconds. They
 * drift in and out of phase on a roughly eighteen-second beat, so some waves
 * barely clear the last one and some run right up the sand. One sine would be
 * a metronome, and a beach that ticks is unsettling in a way that is hard to
 * place but very easy to notice.
 *
 * <p>Each wave is also skewed along the board, so it breaks at one end
 * well before the other and runs along the shore rather than arriving as a
 * level line. On a board this wide a level waterline is a lit row, and a lit
 * row is a progress bar.
 *
 * <p>The rush up is steep and the drain back is slow, which is the asymmetry
 * that makes water look like water. A symmetric sine going up and down at the
 * same rate reads as breathing.
 *
 * <h2>Why the sand is not sand-coloured</h2>
 * Same correction as the desert, same reason: the {@code #E8DBA0} this used
 * to run on is most of the way to white, and a white key is not a beach. The
 * profile drives a saturated amber and lets brightness do what the pale tones
 * were doing on a monitor.
 *
 * <h2>The snowy beach</h2>
 * Same shore, same tide, same wet-sand memory, frozen. It used to run
 * {@code snow-drift}, which is snowfall, and snowfall is the weather
 * overlay's job now. So the snow moved off the sky and onto the ground.
 * <ul>
 *   <li><b>Snow</b> where the sand was, in the cold-biome blues. See the
 *       cold section of {@link ColorPalette} for why never white.</li>
 *   <li><b>The swash zone freezes.</b> Ground the water has just been over is
 *       dark, wet sand with the snow washed off it, and as it dries it glazes
 *       over with ice before it fades back to snow. The glaze peaks halfway
 *       through drying and catches the odd glint while it's there. Same
 *       memory trick as the sand, with a cold-weather ending.</li>
 *   <li><b>The water is slushy.</b> Steel blue instead of turquoise, with
 *       pale slush drifting along the shore in it. The waves come in slower
 *       and don't reach as far, because slush and grease ice really do damp
 *       waves down. It's the one part of this that's physics and not vibes.</li>
 * </ul>
 *
 * <h2>The stony shore</h2>
 * Same tide again, this time hitting rock. Used to be a {@code shimmer} in
 * stone grey, which on an LED is a dim white key having a quiet moment. Three
 * things change:
 * <ul>
 *   <li><b>Slate, not grey.</b> The default profile gives the stone a slate
 *       blue rather than the game's grey, for the same reason the alpine rock
 *       goes violet: neutral grey is the one colour this mod is allergic
 *       to.</li>
 *   <li><b>Deep water, short runs.</b> The sea sits higher up the board than
 *       on the beach, because rock drops straight into deep water, and the
 *       swash only gets a bit over half as far up it as it does on sand.</li>
 *   <li><b>Spray.</b> Where a wave is near the top of its run it throws a
 *       speckle of foam up the rock above the waterline, and the spray
 *       collapses back down as the wave drains. Because the waves are skewed
 *       along the board, the burst travels along the shore as the wave breaks,
 *       which is the motion a stony shore actually has. Spray wets the rock it
 *       lands on, so the wet-sand memory trick still works, just higher up and
 *       in patches.</li>
 * </ul>
 * Wet rock glistens: the odd key in the damp band catches a glint in the
 * accent colour while it dries. Same idea as the snowy beach's ice glaze,
 * minus the ice.
 */
public final class ShoreBreakPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** The beach. */
    public static Pattern beach() {
        return new ShoreBreakPattern(Shore.SAND);
    }

    /** The snowy beach: the same shore, frozen. See the class doc. */
    public static Pattern snowyBeach() {
        return new ShoreBreakPattern(Shore.SNOW);
    }

    /** The stony shore: the same tide breaking on rock. See the class doc. */
    public static Pattern stonyShore() {
        return new ShoreBreakPattern(Shore.STONE);
    }

    private enum Shore { SAND, SNOW, STONE }

    // --- shore geometry, in board heights; y=0 is the top row ----------
    /**
     * Where the waterline sits at the bottom of the draw-back.
     *
     * <p>Just off the bottom edge, so at the lowest point the sea is nothing
     * but a foam line along the last row. The board is a stretch of beach
     * with the water at the end of it, not a board half full of sea.
     */
    private static final double SEA_LEVEL = 1.02;
    /**
     * How far up the sand the biggest wave reaches.
     *
     * <p>Tuned against six rows rather than picked. The swash zone has to
     * span about three of them: any narrower and the whole in-and-out
     * happens inside one row, which is a key changing colour rather than
     * water moving.
     */
    private static final double SWASH_REACH = 0.64;
    /** Thickness of the foam line either side of the waterline. */
    private static final double FOAM_HALF = 0.06;

    // --- the swells ----------------------------------------------------
    private static final double SWELL_A_SECONDS = 6.3;
    private static final double SWELL_B_SECONDS = 9.1;
    /** How much later the far end of the board breaks than the near end. */
    private static final double SKEW = 0.40;
    /**
     * Below 1 this makes the rush up steeper than the drain back. At 1 the
     * wave goes out exactly as fast as it came in, which no water has ever
     * done.
     */
    private static final double RUSH_SHAPE = 0.72;

    /**
     * Seconds for soaked sand to dry back out.
     *
     * <p>Longer than the gap between waves on purpose, so the swash zone
     * stays visibly damp and only the top of it ever fully dries. Short
     * enough and the wet sand vanishes between waves, which loses the one
     * layer that makes this look like a beach instead of a wipe.
     */
    private static final double DRY_SECONDS = 6.0;

    // --- the sand ------------------------------------------------------
    /** Grain octave. High, because sand is fine and the board is coarse. */
    private static final double GRAIN_SCALE = 26.0;
    private static final double GRAIN_DEPTH = 0.22;
    /** Dry sand in the sun: the back of the beach, and the brightest sand gets. */
    private static final double DRY_SAND_LEVEL = 0.50;
    /**
     * Sand the water has just been over. Clearly darker than dry sand, and
     * deliberately nowhere near black.
     *
     * <p>This started out as a multiplier on the dry level instead of a level
     * of its own, which drove soaked keys under 0.10 once the grain noise
     * subtracted its share. The result was a band of dead keys straight
     * across the middle of the board every time a wave went out — which reads
     * as the lighting having given up, not as wet sand.
     *
     * <p>Judge this against what the LED emits, not against the number. Sand
     * at 0.24 mixed a third of the way to the water colour came out at 26 on
     * its brightest channel against dry sand's 95, and 26 next to 95 is a
     * hole. The pairing that works is a higher level with much less of the
     * water's colour in it.
     */
    private static final double WET_SAND_LEVEL = 0.34;

    // --- the water -----------------------------------------------------
    private static final double WATER_LEVEL = 0.38;
    private static final double RIPPLE_DEPTH = 0.13;
    private static final double RIPPLE_HZ = 0.6;

    // --- frozen only ---------------------------------------------------
    /** Swells this much slower on the snowy beach. Slush is thick. */
    private static final double FROZEN_SWELL_STRETCH = 1.3;
    /** And this much of the normal reach up the shore. */
    private static final double FROZEN_REACH = 0.80;
    /** How strong the ice glaze gets at its peak, halfway through drying. */
    private static final double GLAZE_LEVEL = 0.60;
    /**
     * Snow gets its own levels rather than the sand's. At sand brightness and
     * mixed halfway to the accent, snow came out a flat grey-lavender, which
     * is the precise colour of "a key that's on but doesn't mean anything".
     */
    private static final double SNOW_LEVEL = 0.64, FROZEN_WET_LEVEL = 0.38;
    /** How much of the open water is slush. Higher numbers here mean less. */
    private static final double SLUSH_THRESHOLD = 0.62;
    private static final double SLUSH_LEVEL = 0.45;
    /** Slush drift along the shore, in board widths per second. */
    private static final double SLUSH_DRIFT = 0.025;

    // --- stone only ----------------------------------------------------
    /** Fraction of the sand's reach the water manages up rock. */
    private static final double STONY_REACH = 0.58;
    /**
     * The stony shore's low-water mark, higher up the board than the beach's.
     * With the shorter reach and the beach's sea level, the water never got
     * past the bottom two rows and the whole top of the board was rock that
     * never changed. Moving the sea up gives the spray room to climb.
     */
    private static final double STONY_SEA_LEVEL = 0.90;
    /**
     * Dry rock and soaked rock, both a little over the sand's. The rock covers
     * far more of the board than the sand does, most of it above anywhere the
     * water reaches, and at the sand's levels that whole upper stretch sat as
     * one dim, unchanging slab.
     */
    private static final double STONE_LEVEL = 0.55, WET_STONE_LEVEL = 0.36;
    /**
     * Rock texture, much coarser than the sand grain. Sand is a material made
     * of dots. A rock face is a few big lumps, and fine noise on it just reads
     * as gravel.
     */
    private static final double ROCK_SCALE_X = 7.0, ROCK_SCALE_Y = 4.5;
    private static final double ROCK_DEPTH = 0.30;
    /** How far above the waterline the spray gets at the top of a wave, in board heights. About two rows. */
    private static final double SPRAY_HEIGHT = 0.36;
    private static final double SPRAY_LEVEL = 0.80;
    /** Where in the wave's run the spray starts going up. Only near the top, or every wave is a geyser. */
    private static final double SPRAY_START = 0.70;

    // Per-key wetness, carried frame to frame. Built on the first render and
    // rebuilt if the board ever changes shape under us.
    private List<KeyGrid.LedPosition> keys;
    private double[] wetness;
    private long lastMillis = Long.MIN_VALUE;

    private final Shore shore;
    private final boolean frozen;

    private ShoreBreakPattern(Shore shore) {
        this.shore = shore;
        this.frozen = shore == Shore.SNOW;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        RGBColor sand = ctx.baseColor();
        RGBColor sunlit = ctx.resolvedAccentColor();
        RGBColor shallows = switch (shore) {
            case SNOW -> ColorPalette.FROZEN_SHALLOWS;
            case STONE -> ColorPalette.STONY_WATER;
            case SAND -> ColorPalette.SHORE_SHALLOWS;
        };
        RGBColor foam = frozen ? ColorPalette.FROZEN_FOAM : ColorPalette.SHORE_FOAM;

        List<KeyGrid.LedPosition> all = grid.allKeys();
        if (keys == null || wetness == null || wetness.length != all.size()) {
            keys = all;
            wetness = new double[all.size()];
        }

        // Clamped, because the first frame has no previous one and a stalled
        // render thread would otherwise dry the entire beach in a single step.
        double dt = lastMillis == Long.MIN_VALUE
                ? 0.0
                : Math.max(0.0, Math.min(0.25, (elapsedMillis - lastMillis) / 1000.0));
        lastMillis = elapsedMillis;

        LightBudget budget = new LightBudget();

        for (int i = 0; i < keys.size(); i++) {
            KeyGrid.LedPosition key = keys.get(i);
            double x = key.x();
            double y = key.y();

            double surge = surge(x, t);
            double front = frontFor(surge);

            // Anything the water is currently over is soaked outright;
            // everything else dries at a steady rate.
            if (y > front) {
                wetness[i] = 1.0;
            } else if (dt > 0) {
                wetness[i] = Math.max(0.0, wetness[i] - dt / DRY_SECONDS);
            }

            double grain = GRAIN_DEPTH * (valueNoise(x * GRAIN_SCALE, y * GRAIN_SCALE * 0.6) - 0.5);

            if (y > front + FOAM_HALF) {
                // --- open water ---------------------------------------
                // Ripples run along the shore rather than at it, so the sea
                // reads as a surface with something moving over it instead of
                // as more incoming waves competing with the real one.
                double ripple = Math.sin(TAU * (x * 3.4 + t * RIPPLE_HZ))
                        * Math.sin(TAU * (x * 1.7 - t * RIPPLE_HZ * 0.6) + 1.1);
                double depth = clamp01((y - front) / 0.35);
                RGBColor water = shallows.lerp(foam, clamp01(0.30 - 0.30 * depth));
                budget.add(key.ref(), water, WATER_LEVEL + RIPPLE_DEPTH * ripple);
                if (frozen) {
                    // Slush: lumpy pale patches drifting along the shore.
                    // Stretched along x so it lies in bands like the real
                    // thing does, rather than in round blobs.
                    double slush = valueNoise((x - SLUSH_DRIFT * t) * 9.0, y * 14.0 + 3.7);
                    double amount = clamp01((slush - SLUSH_THRESHOLD) / (1 - SLUSH_THRESHOLD) * 2.5);
                    if (amount > 0) budget.add(key.ref(), ColorPalette.SLUSH, SLUSH_LEVEL * amount);
                }
                continue;
            }

            if (y > front - FOAM_HALF) {
                // --- the foam line ------------------------------------
                // Brightest thing on the board, and thin. A wide foam line is
                // a glowing bar; the edge is what makes it read as an edge.
                double across = 1.0 - Math.abs(y - front) / FOAM_HALF;
                budget.add(key.ref(), foam, 0.40 + 0.45 * across);
                continue;
            }

            // --- sand, dry through to soaked --------------------------
            if (shore == Shore.STONE) {
                addStoneShore(budget, key, sand, sunlit, foam, front, surge, i, t);
                continue;
            }
            double wet = wetness[i];
            if (frozen) {
                addFrozenShore(budget, key, sand, sunlit, wet, grain, i, t);
                continue;
            }
            // Wet sand is darker than dry sand and takes on the colour of what
            // soaked it. Both of those together, because doing only the
            // darkening reads as a shadow passing over.
            RGBColor colour = sand.lerp(sunlit, clamp01(0.45 + grain * 2.0) * (1.0 - wet))
                    // Only a little of the water's colour. Pushing wet sand
                    // a third of the way to cyan greys it out into something
                    // that is neither sand nor sea; wet sand in life is darker
                    // brown with a cool cast, not teal.
                    .lerp(ColorPalette.SHORE_SHALLOWS, 0.18 * wet);
            // Interpolated between two real levels rather than scaled down
            // from one, so soaked sand has a floor of its own. The grain
            // fades out as it wets, which is also just true of sand.
            double level = DRY_SAND_LEVEL + (WET_SAND_LEVEL - DRY_SAND_LEVEL) * wet
                    + grain * (1.0 - 0.55 * wet);
            budget.add(key.ref(), colour, clamp01(level));
        }

        return budget.resolve();
    }

    /**
     * The snowy beach's ground: snow when dry, bare wet sand when soaked, and
     * an ice glaze over the in-between.
     *
     * <p>The glaze is {@code 4 * wet * (1 - wet)}, which is zero at both ends
     * and peaks at exactly half wet. So fresh-soaked ground hasn't frozen yet,
     * fully dried ground has been snowed back over, and the ice lives in the
     * band between them, which is the band that's always moving.
     */
    private static void addFrozenShore(LightBudget budget, KeyGrid.LedPosition key, RGBColor snow,
                                       RGBColor sunlit, double wet, double grain, int index, double t) {
        RGBColor colour = snow.lerp(sunlit, clamp01(0.18 + grain * 1.5) * (1.0 - wet))
                .lerp(ColorPalette.FROZEN_WET_SAND, 0.75 * wet);
        double level = SNOW_LEVEL + (FROZEN_WET_LEVEL - SNOW_LEVEL) * wet
                + grain * (1.0 - 0.55 * wet);
        budget.add(key.ref(), colour, clamp01(level));

        double glaze = 4 * wet * (1 - wet);
        if (glaze > 0.05) {
            budget.add(key.ref(), ColorPalette.ICE_GLAZE, GLAZE_LEVEL * glaze);
            // The odd glint off the glaze: each key gets a fresh roll every
            // 0.4s, and only rarely wins it.
            double slot = Math.floor(t / 0.4);
            if (hash(index * 1.37, slot) > 0.94) {
                budget.add(key.ref(), ColorPalette.SNOW_SPARKLE, 0.8 * glaze);
            }
        }
    }

    /**
     * The stony shore's rock, plus the spray thrown up it.
     *
     * <p>Spray height follows how far through its run the wave at this column
     * is, so it climbs as the wave peaks and drops back as it drains. No
     * particles, and no state beyond the wetness everything already carries. A
     * column of speckle that grows and shrinks reads as spray going up and
     * coming down, and at keyboard resolution nobody can tell the difference.
     */
    private void addStoneShore(LightBudget budget, KeyGrid.LedPosition key, RGBColor stone, RGBColor sunlit,
                               RGBColor foam, double front, double surge, int index, double t) {
        double x = key.x(), y = key.y();
        double above = front - y;   // how far up the rock from the waterline, in board heights
        double sprayTop = SPRAY_HEIGHT * clamp01((surge - SPRAY_START) / (1 - SPRAY_START));
        double spray = 0;
        if (sprayTop > 0 && above < sprayTop) {
            // Fast-moving speckle, so it's droplets and not a sheet. Thins out
            // toward the top, where there's less of it.
            double speckle = clamp01((valueNoise(x * 30.0 + 11.0, y * 22.0 - t * 6.0) - 0.45) * 3.0);
            spray = speckle * (1.0 - above / sprayTop);
        }
        // Spray wets whatever it lands on, on the same drying clock as the waterline.
        if (spray > 0.3) wetness[index] = Math.max(wetness[index], spray);
        double wet = wetness[index];

        double rock = ROCK_DEPTH * (valueNoise(x * ROCK_SCALE_X + 5.0, y * ROCK_SCALE_Y) - 0.5);
        RGBColor colour = stone.lerp(sunlit, clamp01(0.15 + rock) * (1.0 - wet))
                .lerp(ColorPalette.STONY_WATER, 0.25 * wet);
        double level = STONE_LEVEL + (WET_STONE_LEVEL - STONE_LEVEL) * wet + rock * (1.0 - 0.4 * wet);
        budget.add(key.ref(), colour, clamp01(level));

        // Wet rock glistening. Same rare per-key roll as the ice glaze, but
        // only while the rock is actually damp.
        if (wet > 0.2 && hash(index * 1.37, Math.floor(t / 0.35)) > 0.93) {
            budget.add(key.ref(), sunlit, 0.55 * wet);
        }
        if (spray > 0) budget.add(key.ref(), foam, SPRAY_LEVEL * spray);
    }

    /**
     * How far through its run the water at a given column is: 0 drained right
     * back, 1 as far up the shore as it ever gets.
     *
     * <p>Two swells summed, skewed along the board, then shaped so the rush up
     * is faster than the drain back.
     */
    private double surge(double x, double t) {
        double stretch = frozen ? FROZEN_SWELL_STRETCH : 1.0;
        double a = Math.sin(TAU * (t / (SWELL_A_SECONDS * stretch) - x * SKEW));
        double b = Math.sin(TAU * (t / (SWELL_B_SECONDS * stretch) - x * SKEW * 0.6 + 0.37));
        double swell = 0.62 * a + 0.38 * b;
        double shaped = Math.copySign(Math.pow(Math.abs(swell), RUSH_SHAPE), swell);
        return 0.5 + 0.5 * shaped;
    }

    /** Where the waterline sits for a given surge, as a board height. Smaller is further up the shore. */
    private double frontFor(double surge) {
        double reach = switch (shore) {
            case SNOW -> SWASH_REACH * FROZEN_REACH;
            case STONE -> SWASH_REACH * STONY_REACH;
            case SAND -> SWASH_REACH;
        };
        return (shore == Shore.STONE ? STONY_SEA_LEVEL : SEA_LEVEL) - reach * surge;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double hash(double x, double y) {
        double v = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
        return v - Math.floor(v);
    }

    /** Bilinear value noise. Same shape as the one the dunes use. */
    private static double valueNoise(double x, double y) {
        double xi = Math.floor(x), yi = Math.floor(y);
        double xf = x - xi, yf = y - yi;
        double u = xf * xf * (3 - 2 * xf);
        double v = yf * yf * (3 - 2 * yf);
        double a = hash(xi, yi), b = hash(xi + 1, yi);
        double c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
    }
}
