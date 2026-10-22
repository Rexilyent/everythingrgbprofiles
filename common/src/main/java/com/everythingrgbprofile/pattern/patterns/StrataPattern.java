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
 * The badlands: a cliff face of banded terracotta, with the sun crossing it and
 * gold catching the light in the rock.
 *
 * <p>Replaces a flat shimmer across all three badlands biomes, and of
 * everywhere that shimmer shipped, this is the place it was doing the most
 * damage. Not because it looked bad. Because it was working directly against
 * the biome on the one channel the biome actually speaks on.
 *
 * <h2>Badlands is a value-banded biome, which is exactly why shimmer was wrong</h2>
 * Every block in the strata ladder, and the red sand lying on top of it, sits
 * between hue 9 and hue 39. Sampled from their own textures:
 *
 * <ul>
 *   <li>{@code brown_terracotta} {@code #4D3323} — H22, V0.30</li>
 *   <li>{@code light_gray_terracotta} {@code #876A61} — H14, V0.53</li>
 *   <li>{@code red_terracotta} {@code #8F3D2E} — H9, V0.56</li>
 *   <li>{@code terracotta} {@code #985E43} — H19, V0.60</li>
 *   <li>{@code orange_terracotta} {@code #A15325} — H22, V0.63</li>
 *   <li>{@code yellow_terracotta} {@code #BA8523} — H38, V0.73</li>
 *   <li>{@code red_sand} {@code #BE6621} — H26, V0.75</li>
 *   <li>{@code white_terracotta} {@code #D1B2A1} — H21, V0.82</li>
 * </ul>
 *
 * <p>Thirty degrees of hue against half the entire brightness range. The
 * layers are not different colours. They are the <i>same</i> colour at
 * different brightnesses, and that is precisely what makes a mesa read as
 * stone instead of as painted stripes.
 *
 * <p>Now: shimmer modulates brightness, per key, at random.
 *
 * <p>So on a badlands board shimmer was not merely generic the way it is on a
 * forest. It was actively scrambling the one axis the biome carries all of its
 * information on. The place has exactly one thing to say and the pattern was
 * talking over it the entire time.
 *
 * <h2>One colour generates the whole ladder</h2>
 * The two extremes of that list are not independent colours. Measured against
 * plain {@code terracotta}, per channel:
 *
 * <ul>
 *   <li>{@code brown_terracotta} is it scaled by 0.507, 0.543, 0.522</li>
 *   <li>{@code white_terracotta} is it lightened by 0.553, 0.522, 0.500</li>
 * </ul>
 *
 * <p>Both cluster on 0.52, in opposite directions. Which is what you would
 * expect, given they are the same block dyed differently rather than three
 * separate minerals.
 *
 * <p>So the ladder here is GENERATED from the profile's single colour by
 * {@link #bandColor}: scaled toward black at the bottom, lightened toward
 * white at the top. Recolouring badlands in the config therefore moves the
 * entire cliff coherently, instead of leaving seven hand-picked hexes sitting
 * there no longer matching the one somebody actually changed.
 *
 * <p>The cost is the hue wander. This draws {@code red_terracotta} and
 * {@code yellow_terracotta} as brightness steps rather than as a genuine lean
 * toward red and toward yellow, and that is a real thing being given up.
 *
 * <p>It is a deliberate trade rather than purely a concession, though.
 * Thirty degrees of hue at these saturations is close to unresolvable
 * through a plastic diffuser anyway, and spending the colour budget on the
 * axis the hardware <i>can</i> actually show is the entire lesson of the
 * tuning log, learned repeatedly and at some expense.
 *
 * <h2>Four layers</h2>
 * <ol>
 *   <li><b>The bands.</b> Level, of deliberately uneven thickness, and not
 *       moving at all. Colour carries the ladder while brightness stays flat
 *       across them, which is the exact opposite of
 *       {@link WindsweptRidgePattern} and is the single thing keeping those
 *       two patterns from looking like each other. See below.</li>
 *   <li><b>Band edges.</b> A thin lift where one layer meets the next. At six
 *       key rows of vertical resolution, a boundary with no edge drawn on it
 *       is just a colour change, and the eye reads a colour change as a
 *       gradient rather than as a seam. So the seam has to be put there
 *       explicitly.</li>
 *   <li><b>The sun.</b> One very wide, very slow pass across the face. It
 *       <i>multiplies</i> rather than adds — see {@link #SUN_GAIN}.</li>
 *   <li><b>Gold.</b> Rare, brief, bright. The pattern's one event.</li>
 * </ol>
 *
 * <p>Gold is here because it is the only thing in the badlands files that
 * belongs to the badlands. {@code ore_gold_extra} appears in all three of these
 * biomes and in no other biome in the game, and it generates high enough to sit
 * in the exposed faces rather than only down a mineshaft. Everything else in
 * their feature lists — the ores, the springs, the dead bushes — is shared with
 * half the overworld.
 *
 * <h2>How this differs from the windswept ridges, and why that matters</h2>
 * Both quantise the board into bands with lit edges, so it is worth being
 * precise about what keeps them from collapsing into each other:
 *
 * <ul>
 *   <li>A windswept terrace comes out of a two-dimensional height field, so its
 *       bands wander along x and no two boards look alike. Strata are level,
 *       because sediment settles flat and erosion cuts down through it.</li>
 *   <li>Windswept puts <b>two materials</b> on the board and uses
 *       <b>brightness</b> to say how high the ground is. This puts <b>one
 *       material</b> on the board and uses <b>colour</b> to say which layer you
 *       are looking at. They are using opposite channels for opposite
 *       jobs.</li>
 * </ul>
 *
 * <p>If this ever starts reading as windswept in orange, the thing that has
 * gone wrong is almost certainly that brightness has crept into the band loop.
 *
 * <h2>Nothing here moves except the light</h2>
 * The bands, their thicknesses, the hoodoo skyline and the canopy are all pure
 * functions of position, fixed once in the constructor. That is the honest way
 * to draw a cliff and also a good way to end up with a board that looks frozen,
 * which is the risk {@code WindsweptRidgePattern} names as well. Two things
 * cover it here: the sun is always somewhere on the board, and the gold keeps
 * the face punctuated. If it ever does read as stopped, the sun is the first
 * thing to widen — not the gold, which stops being an event the moment there is
 * enough of it to count on.
 */
public final class StrataPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // ---------------------------------------------------------------
    // The ladder
    // ---------------------------------------------------------------

    /**
     * How far the ladder reaches from the profile's colour, down and up.
     *
     * <p>The measured figure is 0.52 in <b>both</b> directions — see the class
     * notes for the six per-channel ratios that average out to it. These are
     * not that, and the asymmetry is the one place this pattern knowingly
     * leaves the data.
     *
     * <p>Lightening is the problem. Moving a colour 52% toward white spends
     * more than half its budget on white light, and white light is exactly what
     * a keyboard's three LEDs behind a diffuser already produce too much of. At
     * the measured reach, from the profile colour badlands used at the time,
     * the pale band came out {@code #E3B7A1} — a beige, and
     * on hardware a beige at that brightness is not a colour, it is a dim white
     * key. Pulling the upward half in to 0.34 keeps the top of the cliff
     * recognisably terracotta.
     *
     * <p>Darkening has the opposite property: it removes white light, so the
     * bottom of the ladder can afford to reach further than the data says and
     * gains contrast by doing it. Hence 0.40 down against 0.34 up.
     */
    private static final double LADDER_DARK = 0.40;
    private static final double LADDER_LIGHT = 0.34;

    /**
     * Where each stratum sits on the ladder, 0 darkest to 1 lightest, with how
     * often it turns up.
     *
     * <p>These are the measured V values <b>spread out</b>, and that spreading
     * is deliberate rather than sloppy. Normalised across the range
     * brown..white the real ladder reads 0.00, 0.44, 0.50, 0.58, 0.63, 0.83,
     * 1.00 — four of its seven stops inside a fifth of the range, because real
     * terracotta variants genuinely are that close together. Drawn honestly
     * (from an earlier profile colour), the four middle stops came out as
     * {@code #BA6438}, {@code #C56A3B},
     * {@code #CA764B} and {@code #CD7E55}: twenty units apart across three
     * channels, which is nothing on an LED. Most of the board would have been
     * one colour with the two extremes as occasional punctuation.
     *
     * <p>A real mesa gets away with it by showing you dozens of layers over a
     * hundred blocks of cliff, where a boundary is legible even when the two
     * sides nearly match. Six key rows do not have that, so the stops are
     * spaced out and the ladder carries six stops that can actually be told
     * apart. Same liberty the lush-caves greens take, and for the same reason.
     *
     * <p>The weights are separate and are not spread: they are what the vanilla
     * surface builder does, which is to run mostly orange and plain terracotta
     * and use the rest as punctuation. Weighting them evenly gives a board with
     * as much brown and white on it as orange, which stops looking like a mesa
     * and starts looking like a test card.
     */
    private static final double[] BAND_STOPS = {0.00, 0.20, 0.40, 0.58, 0.78, 1.00};
    private static final double[] BAND_WEIGHTS = {0.08, 0.16, 0.26, 0.26, 0.16, 0.08};

    /**
     * The top band is always red sand, at its measured place on the ladder.
     *
     * <p>Fixed rather than rolled because it is not a stratum — it is the
     * surface lying on top of them, and in the world it is on top of every
     * mesa without exception. It also does structural work: the board gets a
     * consistent bright edge along its top row, so the cliff has a rim instead
     * of simply running out of keys.
     */
    private static final double SURFACE_LEVEL = 0.90;

    // ---------------------------------------------------------------
    // Light
    // ---------------------------------------------------------------

    /**
     * Brightness every band is drawn at, before edges, sun and gold.
     *
     * <p>Flat across the ladder on purpose. The colour is already carrying the
     * layer, and dimming a dark band as well as darkening it would count the
     * same thing twice and crush the bottom of the ladder into one murky
     * brown. That would cost more here than almost anywhere, because the
     * ladder <i>is</i> the pattern.
     *
     * <p>High for an ambient layer, and it should be: this is the driest, most
     * exposed biome in the game. The badlands files declare
     * {@code downfall: 0.0} and {@code has_precipitation: false}, and there is
     * no canopy over any of it.
     */
    private static final double BAND_LIGHT = 0.50;

    /** Extra light on the seam between two layers. */
    private static final double LIP_BOOST = 0.28;

    /**
     * Static mottling within a band, and how fine it is.
     *
     * <p>Without this every key in a layer is identical edge to edge, and the
     * board reads as printed stripes rather than as rock — which is a real
     * failure, because the whole argument for this pattern is that badlands is
     * stone rather than paint.
     *
     * <p>It has <b>no time term</b>, and that is the entire difference between
     * grain and the shimmer this replaced. Shimmer modulates brightness per key
     * <i>over time</i>, which is what scrambles the ladder. A fixed noise field
     * modulates brightness per key <i>in place</i>, which is what stone does.
     * The windswept scars are static for the same reason and say so.
     */
    private static final double GRAIN = 0.16;
    private static final double GRAIN_SCALE = 7.5;

    /**
     * Half-width of the sun pass, and how long it takes to cross.
     *
     * <p>Wide and slow to the point of being hard to catch in the act, which is
     * the intent. A narrower or quicker pass reads as a {@code SweepPattern}
     * wipe — a lighting effect crossing the board rather than the hour getting
     * later. The windswept wind dodges that by splitting into per-row streaks
     * that don't line up; this dodges it by being too large and too slow to
     * look like a gesture at all.
     */
    private static final double SUN_WIDTH = 0.55;
    private static final double SUN_PERIOD_SECONDS = 48.0;

    /**
     * How much the sun lifts what it is on.
     *
     * <p>Applied as a multiplier, which is the one arithmetic decision in this
     * pattern worth arguing about. Adding a constant wash would lift the dark
     * bands by the same absolute amount as the pale ones and so compress the
     * ladder every time the sun went past — the board would visibly flatten and
     * un-flatten. Scaling preserves the ratios between the layers, so the cliff
     * gets brighter without getting less banded, which is what happens when
     * light falls on rock.
     */
    private static final double SUN_GAIN = 0.55;

    /** A touch of movement on the seams between sun passes, so nothing is ever fully still. */
    private static final double SEAM_SWAY = 0.18;
    private static final double SEAM_SWAY_HZ = 0.13;

    // ---------------------------------------------------------------
    // Gold
    // ---------------------------------------------------------------

    /** Share of a slot's cycle that a glint is actually visible for. */
    private static final double GLINT_DUTY = 0.22;
    /** Halo around a glint, in key widths. */
    private static final double GLINT_HALO_KEYS = 1.9;

    // ---------------------------------------------------------------
    // Hoodoos and canopy
    // ---------------------------------------------------------------

    /** What is left of a key the skyline has cut away. Dim, not black: there is still haze. */
    private static final double SKY_FLOOR = 0.10;
    /** Share of spires that are not cut down at all. */
    private static final double SKYLINE_FULL_HEIGHT_SHARE = 0.34;
    /** Canopy noise frequency, in cells across the board. */
    private static final double CANOPY_SCALE = 5.5;
    /** How much of the light a covered key loses under leaves. */
    private static final double CANOPY_SHADE = 0.30;

    // ---------------------------------------------------------------
    // Preset knobs
    // ---------------------------------------------------------------

    /**
     * Thickness wobble, and how many times it turns over the board's height.
     *
     * <p>Their product against tau is what has to stay under 1; see
     * {@link #warp}. A preset scales the first of them by its own
     * {@code bandVariation}, so the bound holds for every preset as long as
     * that stays in 0..1.
     */
    private static final double BAND_WOBBLE = 0.075;
    private static final double BAND_WOBBLE_CYCLES = 1.5;

    private final int bandCount;
    /** How uneven the band thicknesses are, 0 for even stripes. */
    private final double bandVariation;
    private final int glintCount;
    private final double glintPeriodSeconds;
    /** Hoodoo columns across the board, or 0 for an unbroken face. */
    private final int columnCount;
    /** Share of a column's width taken by the gap beside it. */
    private final double columnGap;
    /** How far down the board the skyline can cut. */
    private final double skylineMax;
    /** How far down the canopy reaches, or 0 for a bare plateau. */
    private final double canopyDepth;

    // ---------------------------------------------------------------
    // Fixed at construction
    // ---------------------------------------------------------------

    /** Phase of the thickness wobble, so two presets do not band identically. */
    private final double wobblePhase;
    /** Each band's place on the ladder. */
    private final double[] bandLevel;
    /** Where each hoodoo's skyline sits, and how far down the gap beside it reaches. */
    private final double[] columnTop;
    private final double[] columnBase;

    private StrataPattern(int bandCount, double bandVariation,
                          int glintCount, double glintPeriodSeconds,
                          int columnCount, double columnGap, double skylineMax,
                          double canopyDepth, int seed) {
        this.bandCount = Math.max(2, bandCount);
        this.bandVariation = Math.max(0, Math.min(1.0, bandVariation));
        this.glintCount = Math.max(0, glintCount);
        this.glintPeriodSeconds = Math.max(1.0, glintPeriodSeconds);
        this.columnCount = Math.max(0, columnCount);
        this.columnGap = Math.max(0, Math.min(0.6, columnGap));
        this.skylineMax = skylineMax;
        this.canopyDepth = canopyDepth;

        this.wobblePhase = hash(seed * 61 + 17) * TAU;
        this.bandLevel = buildLevels(this.bandCount, seed);
        this.columnTop = new double[Math.max(1, this.columnCount)];
        this.columnBase = new double[Math.max(1, this.columnCount)];
        for (int c = 0; c < this.columnCount; c++) {
            // Skewed so roughly a third of the spires reach the top of the
            // board outright. An earlier version rolled the top uniformly,
            // which sounds like the same thing and is not: a uniform roll gives
            // every column *some* cut, so the entire top row went dark and the
            // silhouette had nothing to be a silhouette against. A skyline
            // needs the tall ones to touch the ceiling.
            double roll = hash(seed * 131 + c * 17 + 5);
            columnTop[c] = roll < SKYLINE_FULL_HEIGHT_SHARE
                    ? 0.0
                    : skylineMax * (roll - SKYLINE_FULL_HEIGHT_SHARE) / (1.0 - SKYLINE_FULL_HEIGHT_SHARE);
            // The gap beside a spire stops short of the floor, because hoodoos
            // stand on the same bench they were cut out of. Letting the gaps
            // run to the bottom edge turns them into free-floating pillars,
            // which is a thing the End does and the badlands does not.
            columnBase[c] = 0.58 + 0.24 * hash(seed * 197 + c * 23 + 11);
        }
    }

    /**
     * Which stratum each band is.
     *
     * <p>Two rules beyond the weighted roll. The top band is always the
     * surface, and no band may land within {@link #MIN_BAND_SEPARATION} of the
     * one above it.
     *
     * <p>That second rule started out as "not the same stop", which was not
     * enough. Rejecting only exact ties still allowed neighbours one step apart
     * on the ladder, and one step is about twenty units per channel — so two
     * bands would come out visually identical, merge into one of double
     * thickness, and quietly cost a layer. The seed shipped for plain badlands
     * did exactly that: it put a 1.00 band directly under the 0.90 surface cap,
     * which wasted the cap as well, since the rim is only legible if the layer
     * beneath it is darker.
     *
     * <p>Note the first comparison is against the surface level rather than
     * against nothing, for that reason — the cap is a band like any other as
     * far as this rule is concerned.
     */
    private static double[] buildLevels(int count, int seed) {
        double[] levels = new double[count];
        levels[0] = SURFACE_LEVEL;
        double previous = SURFACE_LEVEL;
        for (int i = 1; i < count; i++) {
            int pick = weightedStop(hash(seed * 53 + i * 29 + 7));
            pick = nudgeApart(pick, previous);
            levels[i] = BAND_STOPS[pick];
            previous = levels[i];
        }
        return levels;
    }

    /**
     * How far apart two touching layers must sit on the ladder.
     *
     * <p>0.18 is one full step of {@link #BAND_STOPS} less a rounding margin,
     * so neighbours are always at least one stop apart and usually more.
     */
    private static final double MIN_BAND_SEPARATION = 0.18;

    /**
     * The nearest stop to {@code pick} that clears {@link #MIN_BAND_SEPARATION}
     * from the layer above.
     *
     * <p>Searched outward — one up, one down, two up, two down — rather than
     * cycled forward, which is what an earlier version did and which biased the
     * whole pattern badly. Cycling forward from a rejected pale stop walks off
     * the top of the table and wraps to index 0, so a band sitting under the
     * pale surface cap was rejected at 0.78, rejected again at 1.00, and landed
     * on 0.00 essentially every time. Two of the three presets came out with
     * their darkest possible stratum directly beneath the rim, which is not
     * what the weights asked for and is not what a mesa looks like.
     *
     * <p>Returns the original pick if nothing clears, so a tight separation
     * against a short table degrades to "closest available" instead of looping
     * forever or throwing during registration.
     */
    private static int nudgeApart(int pick, double previous) {
        int n = BAND_STOPS.length;
        if (Math.abs(BAND_STOPS[pick] - previous) >= MIN_BAND_SEPARATION) return pick;
        for (int step = 1; step <= n; step++) {
            int up = pick + step;
            if (up < n && Math.abs(BAND_STOPS[up] - previous) >= MIN_BAND_SEPARATION) return up;
            int down = pick - step;
            if (down >= 0 && Math.abs(BAND_STOPS[down] - previous) >= MIN_BAND_SEPARATION) return down;
        }
        return pick;
    }

    private static int weightedStop(double roll) {
        double total = 0;
        for (double w : BAND_WEIGHTS) total += w;
        double target = roll * total;
        double at = 0;
        for (int i = 0; i < BAND_WEIGHTS.length; i++) {
            at += BAND_WEIGHTS[i];
            if (target <= at) return i;
        }
        return BAND_WEIGHTS.length - 1;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        RGBColor rock = ctx.baseColor();
        RGBColor gold = ctx.resolvedAccentColor();

        // One key-width of real distance expressed in normalised y, the same
        // conversion the windswept lip test uses — see KeyGrid.aspectRatio.
        double rowStep = keyWidth / aspect;

        // The sun is one pass, resolved once rather than per key.
        double sunAt = frac(t / SUN_PERIOD_SECONDS) * (1.0 + 2 * SUN_WIDTH) - SUN_WIDTH;

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            int band = bandAt(key.y());
            RGBColor color = bandColor(rock, bandLevel[band]);
            // Grain is folded into the band light rather than added after the
            // sun, so a mottle stays a fixed proportion of its layer instead of
            // being a constant speckle that is invisible on the pale bands and
            // overwhelming on the dark ones.
            double grain = 1.0 + GRAIN * (valueNoise(key.x() * GRAIN_SCALE,
                    key.y() * GRAIN_SCALE + band * 4.7) - 0.5) * 2.0;
            double light = BAND_LIGHT * grain;

            // A seam is a key with a different layer one key-row above it.
            // Hard-edged on purpose: softening it by distance to the boundary
            // produces a gradient, and a gradient is the one thing strata are
            // not.
            if (bandAt(key.y() - rowStep) != band) {
                double sway = 1.0 + SEAM_SWAY * Math.sin(TAU * SEAM_SWAY_HZ * t + key.x() * 5.3 + band);
                light += LIP_BOOST * sway;
            }

            // --- hoodoos ------------------------------------------------
            if (columnCount > 0 && cutAway(key.x(), key.y())) {
                light *= SKY_FLOOR;
            }

            // --- canopy -------------------------------------------------
            if (canopyDepth > 0 && key.y() < canopyDepth) {
                double n = valueNoise(key.x() * CANOPY_SCALE + 3.1, key.y() * CANOPY_SCALE + 7.9);
                // Densest along the very top and thinning downward, because
                // what is being drawn is the edge of a plateau seen from below:
                // trees on the rim, bare rock under it.
                double cover = smoothstep(0.40, 0.64, n) * (1.0 - key.y() / canopyDepth);
                color = color.lerp(ColorPalette.BADLANDS_CANOPY, cover);
                light *= 1.0 - CANOPY_SHADE * cover;
            }

            double d = Math.abs(key.x() - sunAt);
            if (d < SUN_WIDTH) {
                double falloff = 1.0 - d / SUN_WIDTH;
                light *= 1.0 + SUN_GAIN * falloff * falloff;
            }

            budget.add(key.ref(), color, light);
        }

        renderGold(grid, t, keyWidth, gold, budget);

        return budget.resolve();
    }

    /**
     * Gold catching the light in the face.
     *
     * <p>Each slot is dark for most of its cycle and lands somewhere new every
     * time it fires, which is what a closed-form glint buys over a spawned one:
     * there is no list to recycle and nothing to reseed when the effect clock
     * restarts.
     *
     * <p>The envelope is the mineshaft menu's, and the wobble in it is doing
     * the same job — a plain fade up and down reads as a lamp being turned on,
     * and it is the flicker that makes it read as light catching a facet as you
     * move past it.
     */
    private void renderGold(KeyGrid grid, double t, double keyWidth, RGBColor gold, LightBudget budget) {
        double halo = GLINT_HALO_KEYS * keyWidth;
        for (int i = 0; i < glintCount; i++) {
            double period = glintPeriodSeconds / (0.80 + 0.40 * hash(i * 19 + 3));
            double u = t / period + hash(i * 31 + 13);
            double p = frac(u);
            if (p > GLINT_DUTY) continue;
            double q = p / GLINT_DUTY;

            // Which appearance this is, so the spot moves between firings
            // rather than the same key blinking forever.
            int cycle = (int) Math.floor(u);
            double gx = hash(i * 37 + cycle * 101 + 5);
            double gy = hash(i * 41 + cycle * 103 + 9);
            // Held off the very top of the board: the surface layer is sand,
            // and gold showing through it would be gold lying on the ground.
            // The margin covers the thickest the top band can wobble to.
            double below = 1.5 / bandCount;
            gy = below + gy * (1.0 - below);
            if (columnCount > 0 && cutAway(gx, gy)) continue;

            double envelope = q < 0.10 ? q / 0.10 : Math.pow(1.0 - (q - 0.10) / 0.90, 1.7);
            double sparkle = 0.78 + 0.22 * Math.sin(q * Math.PI * 5.0);
            double brightness = Math.max(0, envelope) * sparkle;
            if (brightness <= 0.01) continue;

            KeyGrid.LedPosition core = nearestKey(grid, gx, gy);
            if (core != null) budget.add(core.ref(), gold, brightness * 0.85);

            for (KeyGrid.LedPosition key : grid.allKeys()) {
                if (core != null && key.ref().equals(core.ref())) continue;
                double d = Math.hypot(key.x() - gx, key.y() - gy);
                if (d >= halo) continue;
                double falloff = 1.0 - d / halo;
                budget.add(key.ref(), gold, brightness * falloff * falloff * 0.25);
            }
        }
    }

    // ---------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------

    /**
     * Which layer a given height falls in. No time term: cliffs stay put.
     *
     * <p>Bands come from warping y and then cutting it into equal pieces,
     * rather than from a table of boundaries rolled per band. Both give the
     * uneven thicknesses strata need — they are laid down over different spans
     * of time, and even stripes read as a test pattern immediately — but the
     * warp bounds how uneven they get, and the rolled table did not.
     *
     * <p>An earlier version did roll them, and on a full-size board it lost a
     * layer outright: six rolled thicknesses put one band between y 0.366 and
     * 0.443, and the six key rows sit at 0, 0.27, 0.45, 0.64, 0.82 and 1, so
     * nothing sampled it. The band was in the model, had a colour, and never
     * reached a key. Because {@link #warp} is monotonic with a bounded
     * derivative, the thickest band here is at most about three and a half
     * times the thinnest (and that is the eroded preset at its worst seed), so
     * that failure cannot recur at the same scale.
     *
     * <p>It is <b>not</b> a guarantee that every band reaches a key, and the
     * arithmetic says it cannot be: a board with six rows has one row per 0.2
     * of height, while {@code bandCount} bands average 1/{@code bandCount}
     * each. Asking for five or six bands necessarily asks for some of them to
     * be thinner than the row spacing, and one will occasionally fall between
     * two rows. That is survivable and looks like what it is — a thin seam you
     * would need a taller cliff to see — which is why the fix here is a bound
     * rather than snapping bands onto the row grid. Snapping would guarantee
     * it, at the cost of tying the pattern to keyboard geometry and making the
     * bands exactly as even as the rows are.
     */
    private int bandAt(double y) {
        double clamped = y < 0 ? 0 : Math.min(y, 0.999999);
        int band = (int) (warp(clamped) * bandCount);
        return Math.max(0, Math.min(band, bandCount - 1));
    }

    /**
     * Stretches and squeezes the height axis so equal cuts come out uneven.
     *
     * <p>{@link #BAND_WOBBLE} times {@link #BAND_WOBBLE_CYCLES} times tau must
     * stay below 1 or this stops being monotonic, bands start folding back
     * through each other and the cliff gets layers in the wrong order. At the
     * values shipped the product is 0.71, so the derivative stays between
     * 0.29 and 1.71 on eroded (full {@code bandVariation}) and inside
     * 0.40..1.60 on the other two. Averaged over a band, that leaves the
     * thickest at most about three and a half times the thinnest on eroded
     * and two and a half on the others, and none of them vanishes.
     */
    private double warp(double y) {
        double w = y + BAND_WOBBLE * bandVariation
                * Math.sin(TAU * BAND_WOBBLE_CYCLES * y + wobblePhase);
        return w < 0 ? 0 : Math.min(w, 0.999999);
    }

    /**
     * Whether the skyline has cut this point away — above a spire's top, or in
     * the gap beside it.
     *
     * <p>Gaps and tops are the same test because they are the same thing seen
     * twice: a hoodoo is what is left after the rock around it and above it has
     * gone.
     */
    private boolean cutAway(double x, double y) {
        double scaled = x * columnCount;
        int c = (int) Math.floor(scaled);
        if (c < 0) c = 0;
        if (c >= columnCount) c = columnCount - 1;
        double within = scaled - c;
        double halfGap = columnGap * 0.5;
        boolean inGap = (within < halfGap || within > 1.0 - halfGap) && y < columnBase[c];
        return inGap || y < columnTop[c];
    }

    /**
     * A point on the ladder, as a colour.
     *
     * <p>0 is the darkest stratum, 0.5 is the profile's own colour, 1 is the
     * palest. See {@link #LADDER_DARK} for why the two halves do not reach the
     * same distance.
     */
    private static RGBColor bandColor(RGBColor base, double level) {
        if (level < 0.5) {
            return base.scaled(LADDER_DARK + (1.0 - LADDER_DARK) * (level * 2.0));
        }
        return base.lightened(LADDER_LIGHT * ((level - 0.5) * 2.0));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** Deterministic 0..1 from an integer. Same idiom as the motes and the torch trail. */
    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / Math.max(1e-6, edge1 - edge0)));
        return v * v * (3 - 2 * v);
    }

    /** Same lattice hash the canopy, the dunes and the ridges use. */
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

    /** Nearest LED to a normalised point. Brute force; see DriftParticlePattern for why that is fine. */
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

    // ---------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------

    /**
     * Plain badlands: an unbroken banded wall.
     *
     * <p>The middle setting of the family and the one the other two are
     * described against. Five bands, the sand cap included: one short of the
     * six that six key rows can carry at most, which is the same ceiling the
     * windswept terraces ran into, and for the same reason.
     */
    public static StrataPattern badlands() {
        return new StrataPattern(5, 0.85, 4, 5.5, 0, 0, 0, 0, 0);
    }

    /**
     * Eroded badlands: the same wall, cut into hoodoos.
     *
     * <p>The biome file is byte-for-byte the plain badlands one — the entire
     * difference is in the surface rule, which carves the plateau into
     * free-standing spires. So the colour treatment here is unchanged and the
     * skyline does all the work: seven columns with gaps between them, about a
     * third of them left standing to the top of the board and the rest cut down
     * to different heights, which is what turns a flat top edge into a
     * silhouette.
     *
     * <p>More bands than the plain preset, because a spire is a narrow cut
     * through the same rock and shows its layers closer together.
     */
    public static StrataPattern erodedBadlands() {
        return new StrataPattern(6, 1.00, 4, 5.0, 7, 0.26, 0.30, 0, 5);
    }

    /**
     * Wooded badlands: the same wall with oak on the rim.
     *
     * <p>The one feature the biome file adds is {@code trees_badlands} (the
     * only other difference is that wolves spawn there), so that is the one
     * thing this preset adds.
     *
     * <p>The colour of it is worth being honest about. Badlands foliage is
     * tinted {@code #9E814D}, and oak leaves average {@code #909090}, so the
     * leaves actually render around {@code #59492B} — a dark khaki at hue 39,
     * which is within a few degrees of {@code yellow_terracotta} and darker
     * than every stratum but one. Drawn at those values on a keyboard the
     * canopy would be indistinguishable from a brown band. So
     * {@link ColorPalette#BADLANDS_CANOPY} is lifted and turned toward olive,
     * which is the same liberty the lush-caves greens take, and is defensible
     * beyond legibility: what you see from below is a rim of treetops in full
     * sun, in a biome with no cloud and no rain, not foliage in shade.
     *
     * <p>Fewer glints than the other two. The gold is in the exposed rock, and
     * on this variant a good deal of the rock has trees standing on it.
     */
    public static StrataPattern woodedBadlands() {
        return new StrataPattern(5, 0.75, 3, 7.0, 0, 0, 0, 0.40, 2);
    }
}
