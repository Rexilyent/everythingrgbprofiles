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
 * The cold, open, treeless biomes: the peaks, the slopes, the ice spikes and
 * the snowy plains. Ground against sky, and something moving that isn't
 * snowfall.
 *
 * <p>Replaces two things. Most of these ran {@code snow-drift}, which is
 * snowfall, which is weather, and weather already has an overlay of its own
 * that switches on when it's actually snowing. A mountain that snows on you
 * permanently under a clear sky is a mountain with a bug. The other two (jagged
 * and stony peaks) ran a grey shimmer, which is a keyboard doing an impression
 * of concrete.
 *
 * <h2>The colour rule</h2>
 * Snow in shade is blue and snow in sun is warm. That's the whole trick, and
 * it's the reason none of this needs a single grey or white key. Shaded faces
 * run the profile's base colour, sunlit faces pick up the accent, and rock is
 * slate pushed toward violet. See the cold-biome section of
 * {@link ColorPalette} for the fixed colours and why each one is the colour
 * it is.
 *
 * <h2>The scenes</h2>
 * <ul>
 *   <li><b>Peaks.</b> A jagged skyline with sky above it. The faces split at
 *       each summit into a shaded west side and a lit east side, and the lit
 *       side carries alpenglow: a slow pink-orange wash, plus a broader warm
 *       band that drifts along the range. Snow plumes tear off the summits and
 *       stream downwind (spindrift), which is sideways, starts at a point, and
 *       so can't be mistaken for snowfall by anyone.</li>
 *   <li><b>Slope.</b> One long diagonal, high on the left, falling away to the
 *       right. Crystals sparkle on the surface, and every so often a little
 *       slide breaks loose and runs down it, leaving a darker scar behind that
 *       fills back in over a few seconds.</li>
 *   <li><b>Spikes.</b> Tall ice spikes standing up out of the snow, with light
 *       climbing each one and flaring at the tip.</li>
 *   <li><b>Plain.</b> Flat snow, open sky, and a ground blizzard: loose snow
 *       skating sideways along the surface in gusts. Wind doing the moving,
 *       not the sky.</li>
 * </ul>
 *
 * <h2>Coordinates</h2>
 * Everything is measured in keys across and rows <i>up</i> from just under the
 * bottom row, because this is all terrain and terrain is measured from the
 * ground. Top row centre sits at {@code lastRow + 0.5}.
 */
public final class AlpinePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    private enum Terrain { PEAKS, SLOPE, SPIKES, PLAIN }

    // --- presets -------------------------------------------------------

    /** Jagged peaks: tall, sharp, snowed most of the way down, with the strongest spindrift. */
    public static AlpinePattern jaggedPeaks() {
        return new AlpinePattern(Terrain.PEAKS, 4, 0.72, 1.08, 1.0, 0.30, 1.0, false, false, 3);
    }

    /** Frozen peaks: a little lower and rounder, packed ice instead of rock, and glints on the ice. */
    public static AlpinePattern frozenPeaks() {
        return new AlpinePattern(Terrain.PEAKS, 4, 0.62, 0.95, 1.25, 0.0, 0.6, true, false, 17);
    }

    /**
     * Stony peaks: lower, blunter, bare stone in bands with only the very tips
     * snowed, and no spindrift because there's barely any snow up there to
     * blow off.
     */
    public static AlpinePattern stonyPeaks() {
        return new AlpinePattern(Terrain.PEAKS, 4, 0.52, 0.86, 1.6, 0.78, 0.0, false, true, 23);
    }

    /** Snowy slopes: one long slope with sparkle and the occasional slide. */
    public static AlpinePattern snowySlopes() {
        return new AlpinePattern(Terrain.SLOPE, 0, 0, 0, 1, 0, 0, true, false, 31);
    }

    /** Ice spikes: spikes on snow with light climbing them. */
    public static AlpinePattern iceSpikes() {
        return new AlpinePattern(Terrain.SPIKES, 0, 0, 0, 1, 0, 0, true, false, 37);
    }

    /** Snowy plains: flat snow and a ground blizzard. */
    public static AlpinePattern snowyPlains() {
        return new AlpinePattern(Terrain.PLAIN, 0, 0, 0, 1, 0, 0, true, false, 41);
    }

    private final Terrain terrain;
    /** How many summits the skyline tries to fit. Peaks only. */
    private final int peakCount;
    /** Summit heights, as a share of the board's height. Above 1 gets cropped off the top, which is fine; real ranges don't fit in frame either. */
    private final double heightMin, heightMax;
    /** Flank curve: 1 is a straight-sided triangle, higher bows the flanks out into something blunter. */
    private final double flankPower;
    /** Where the snow starts, as a share of each summit's height. 0 is snow all the way down. */
    private final double snowLine;
    /** Spindrift strength. 0 is a windless day. */
    private final double spindrift;
    private final boolean sparkle;
    /** Stone is the surface and snow only caps it, rather than the other way round. */
    private final boolean stony;
    private final int seed;

    private AlpinePattern(Terrain terrain, int peakCount, double heightMin, double heightMax,
                          double flankPower, double snowLine, double spindrift,
                          boolean sparkle, boolean stony, int seed) {
        this.terrain = terrain;
        this.peakCount = peakCount;
        this.heightMin = heightMin;
        this.heightMax = heightMax;
        this.flankPower = flankPower;
        this.snowLine = snowLine;
        this.spindrift = spindrift;
        this.sparkle = sparkle;
        this.stony = stony;
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    private static final double SKY_LEVEL = 0.30;
    private static final double SHADE_LEVEL = 0.62;
    private static final double LIT_LEVEL = 0.80;
    /** How much warm light the lit faces get on top of their own colour. */
    private static final double GLOW_LEVEL = 0.55;
    /** The thin bright line along every ridge and summit, where the light grazes over the top. */
    private static final double RIM_LEVEL = 0.35;
    private static final double PLUME_LEVEL = 0.85;

    // --- alpenglow -----------------------------------------------------

    private static final double GLOW_PERIOD = 23.0;
    /** The warm band drifting along the range takes this long to cross. */
    private static final double GLOW_BAND_CROSSING = 19.0;
    private static final double GLOW_BAND_HALF_WIDTH = 4.0;

    // --- spindrift -----------------------------------------------------

    /** Wind speed, in keys per second. Fast on purpose: it's a gale up there, not a breeze. */
    private static final double WIND = 2.6;
    private static final double PLUME_MIN_LENGTH = 2.5, PLUME_MAX_LENGTH = 6.0;

    // --- sparkle -------------------------------------------------------

    private static final int SPARKLES = 6;
    private static final double SPARKLE_PERIOD = 1.4;
    private static final double SPARKLE_SECONDS = 0.30;
    private static final double SPARKLE_LEVEL = 0.95;

    // --- slides, slope only ---------------------------------------------
    // One slide per cycle, starting a couple of seconds in, so the scar has
    // filled back in completely before the next cycle starts over. A scar
    // that's still there when the cycle wraps vanishes in one frame, and a
    // whole patch of snow healing instantly looks exactly as wrong as it sounds.

    private static final double SLIDE_EVERY = 14.0;
    private static final double SLIDE_START = 2.0;
    private static final double SLIDE_SECONDS = 3.5;
    private static final double SCAR_SECONDS = 7.5;
    private static final double SCAR_DEPTH = 0.45;

    // --- ice spikes ----------------------------------------------------

    private static final double SPIKE_PULSE_PERIOD = 5.5;
    private static final double SPIKE_LEVEL = 0.70;
    private static final double PULSE_LEVEL = 0.65;

    // --- ground blizzard ------------------------------------------------

    /** Faster than the spindrift, because ground snow is light and right down where the wind has nothing in its way. */
    private static final double DRIFT_WIND = 3.4;
    /** How high above the snow the blizzard reaches, in rows. Ankle-to-knee height, scaled up to a keyboard. */
    private static final double DRIFT_HEIGHT = 1.7;
    private static final double DRIFT_LEVEL = 0.75;

    // Rolled per board, like everything else that's rolled.
    private KeyGrid layoutFor;
    private final List<double[]> peaks = new ArrayList<>();    // {x, height, halfWidth}
    private final List<double[]> spikes = new ArrayList<>();   // {x, halfWidth, height, phase}

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor face = ctx.baseColor();
        RGBColor light = ctx.resolvedAccentColor();

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

        // Alpenglow for this frame: an overall breath and where the warm band is.
        double glowBreath = 0.55 + 0.30 * Math.sin(TAU * t / GLOW_PERIOD);
        double bandX = ((t / GLOW_BAND_CROSSING) % 1.0) * (widthKeys + 4 * GLOW_BAND_HALF_WIDTH) - 2 * GLOW_BAND_HALF_WIDTH;

        // The sparkles, each pinned to one spot for its short life. Spots are
        // picked on the surface or just under it, since that's the snow the
        // sun can actually reach.
        double[] sparkX = new double[SPARKLES], sparkE = new double[SPARKLES], sparkAmt = new double[SPARKLES];
        if (sparkle) {
            for (int s = 0; s < SPARKLES; s++) {
                double phase = t / SPARKLE_PERIOD + (double) s / SPARKLES;
                int cycle = (int) Math.floor(phase);
                double age = (phase - cycle) * SPARKLE_PERIOD;
                if (age >= SPARKLE_SECONDS) continue;
                int roll = s * 977 + cycle;
                double x = hash(roll, 60) * widthKeys;
                if (terrain == Terrain.SPIKES && !spikes.isEmpty() && hash(roll, 63) < 0.5) {
                    // Half the sparkles land on the spikes instead of the snow.
                    double[] sp = spikes.get((int) (hash(roll, 64) * spikes.size()) % spikes.size());
                    sparkX[s] = sp[0] + (hash(roll, 65) - 0.5) * sp[1];
                    sparkE[s] = hash(roll, 66) * sp[2] * 0.8;
                } else {
                    sparkX[s] = x;
                    sparkE[s] = ground(x, widthKeys, boardHeight) - hash(roll, 61) * 1.6;
                }
                sparkAmt[s] = Math.sin(Math.PI * age / SPARKLE_SECONDS);
            }
        }

        // The slide for this cycle, slope only.
        double slideStart = 0, slideEnd = 0, slideSpeed = 1, slideAge = -1;
        if (terrain == Terrain.SLOPE) {
            int cycle = (int) Math.floor(t / SLIDE_EVERY);
            slideAge = t - cycle * SLIDE_EVERY - SLIDE_START;
            slideStart = hash(cycle, 70) * widthKeys * 0.45;
            double length = lerp(5, 9, hash(cycle, 71));
            slideEnd = slideStart + length;
            slideSpeed = length / SLIDE_SECONDS;
        }

        double driftGust = 0.55 + 0.45 * Math.sin(TAU * t / 9.0) * Math.sin(TAU * t / 4.1 + 0.8);

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - rowF;
            KeyGrid.LedRef ref = key.ref();

            // --- terrain at this column --------------------------------
            double surface;
            int owner = -1;
            if (terrain == Terrain.PEAKS) {
                surface = 0.8;   // the valley floor, if a column somehow misses every summit
                for (int p = 0; p < peaks.size(); p++) {
                    double[] pk = peaks.get(p);
                    double h = pk[1] * Math.pow(Math.max(0, 1 - Math.abs(col - pk[0]) / pk[2]), flankPower);
                    if (h > surface) {
                        surface = h;
                        owner = p;
                    }
                }
                // Broken up so no flank is a ruler-straight staircase.
                surface += 0.45 * (valueNoise(col * 0.9 + seed, 2.7) - 0.5);
            } else {
                surface = ground(col, widthKeys, boardHeight);
            }
            double depth = surface - elev;   // positive = inside the ground
            double solid = smoothstep(-0.35, 0.35, depth);

            // --- ice spikes --------------------------------------------
            double spikeCover = 0, spikePulse = 0;
            if (terrain == Terrain.SPIKES) {
                for (double[] sp : spikes) {
                    double e = elev - surface + 0.4;   // rooted a little under the snow
                    if (e < 0 || e > sp[2] + 0.4) continue;
                    double half = sp[1] * (1 - e / sp[2]);
                    double c = smoothstep(half + 0.35, half - 0.15, Math.abs(col - sp[0]));
                    if (c <= spikeCover) continue;
                    spikeCover = c;
                    // The pulse climbs past the tip and off into the sky, so
                    // the flare at the top gets its moment before the next one.
                    double pulseE = ((t / SPIKE_PULSE_PERIOD + sp[3]) % 1.0) * (sp[2] + 1.5);
                    double climb = Math.exp(-sq((e - pulseE) / 0.6));
                    double flare = Math.exp(-sq((pulseE - sp[2]) / 0.5)) * smoothstep(sp[2] - 1.2, sp[2], e);
                    spikePulse = Math.max(climb, flare);
                }
            }

            // --- the sky -----------------------------------------------
            double open = (1 - solid) * (1 - spikeCover);
            if (open > 0) {
                // Paler toward the horizon, deeper overhead. Same as the real one.
                double skyLevel = SKY_LEVEL * (0.75 + 0.35 * (1 - elev / boardHeight));
                budget.add(ref, ColorPalette.ALPINE_SKY, open * skyLevel);
            }

            // --- the ground --------------------------------------------
            double ground = solid * (1 - spikeCover);
            if (ground > 0) {
                if (terrain == Terrain.PEAKS) {
                    double[] pk = owner >= 0 ? peaks.get(owner) : new double[]{col, 1, 1};
                    // Which side of the summit, softened across the spine so
                    // the light change doesn't land as a hard vertical line.
                    double lit = smoothstep(-0.4, 0.4, col - pk[0]);
                    double snowFrom = pk[1] * snowLine + 0.5 * (valueNoise(col * 1.3 + seed, elev * 0.8) - 0.5);
                    double snow = snowLine <= 0 ? 1 : smoothstep(snowFrom - 0.35, snowFrom + 0.35, elev);
                    RGBColor rock = stony ? face : ColorPalette.ALPINE_ROCK;
                    RGBColor snowColour = stony ? ColorPalette.SNOW_SHADE : face;
                    RGBColor surfaceColour = rock.lerp(snowColour, snow);
                    double level = lerp(SHADE_LEVEL, LIT_LEVEL, lit);
                    if (stony) {
                        // Bands of stone, tilted slightly, because stony peaks
                        // are stone with seams of calcite running through it,
                        // and that's what gives them their stripes.
                        level *= 0.82 + 0.18 * Math.sin(TAU * (elev * 0.55 + col * 0.06) + 3 * valueNoise(col * 0.3, seed));
                    }
                    budget.add(ref, surfaceColour, ground * level);

                    double band = Math.exp(-sq((col - bandX) / GLOW_BAND_HALF_WIDTH));
                    double glow = GLOW_LEVEL * lit * (glowBreath + 0.45 * band) * (0.5 + 0.5 * snow);
                    budget.add(ref, light, ground * glow);
                    // Rim: the top half-row or so of every ridge line.
                    double rim = smoothstep(1.0, 0.1, depth);
                    if (rim > 0) budget.add(ref, light, ground * RIM_LEVEL * rim * (0.4 + 0.6 * lit));
                } else {
                    // Snow, getting bluer and darker the further under the
                    // surface you go, which is how deep snow holds light.
                    double under = Math.min(depth, 3.0) / 3.0;
                    double level = 0.78 - 0.30 * under;
                    if (terrain == Terrain.PLAIN) {
                        // Sastrugi: wind-carved ridges in the crust, lying
                        // along the wind. Pinned to the ground, not the wind.
                        level *= 0.88 + 0.12 * Math.sin(TAU * (col * 0.22 + elev * 0.9) + 4 * valueNoise(col * 0.25 + seed, elev));
                    }
                    double scar = 0, slideHead = 0;
                    if (terrain == Terrain.SLOPE && slideAge >= 0 && col >= slideStart && col <= slideEnd && depth < 1.5) {
                        double since = slideAge - (col - slideStart) / slideSpeed;
                        if (since >= 0) {
                            slideHead = smoothstep(0.9, 0.0, since) * smoothstep(1.5, 0.3, depth);
                            scar = smoothstep(0.2, 0.9, since) * smoothstep(SCAR_SECONDS, 0.5, since);
                        }
                    }
                    budget.add(ref, face, ground * level * (1 - SCAR_DEPTH * scar));
                    // The sunlit crust along the top of the snow.
                    double crust = smoothstep(1.2, 0.0, depth);
                    if (crust > 0) budget.add(ref, light, ground * 0.35 * crust * (1 - scar));
                    if (slideHead > 0) budget.add(ref, ColorPalette.SNOW_PLUME, ground * 0.9 * slideHead);
                }
            }

            // --- ice spike body ----------------------------------------
            if (spikeCover > 0) {
                budget.add(ref, face, spikeCover * SPIKE_LEVEL);
                if (spikePulse > 0) budget.add(ref, light, spikeCover * PULSE_LEVEL * spikePulse);
            }

            // --- the slide's powder cloud, which is airborne ------------
            if (terrain == Terrain.SLOPE && slideAge >= 0 && slideAge <= SLIDE_SECONDS + 0.8 && depth < 0 && depth > -1.2) {
                double headX = slideStart + Math.min(slideAge, SLIDE_SECONDS) * slideSpeed;
                double fade = smoothstep(SLIDE_SECONDS + 0.8, SLIDE_SECONDS, slideAge);
                double puff = Math.exp(-sq((col - headX) / 1.1)) * smoothstep(-1.2, -0.2, depth) * fade;
                if (puff > 0.01) budget.add(ref, ColorPalette.SNOW_PLUME, 0.7 * puff * open);
            }

            // --- spindrift ---------------------------------------------
            if (spindrift > 0 && depth < 0.4) {
                double plume = 0;
                for (int p = 0; p < peaks.size(); p++) {
                    double[] pk = peaks.get(p);
                    double gust = 0.5 + 0.5 * Math.sin(TAU * t / (6 + 3 * hash(p, 80)) + TAU * hash(p, 81));
                    double length = lerp(PLUME_MIN_LENGTH, PLUME_MAX_LENGTH, gust);
                    double dx = col - pk[0];
                    if (dx < -0.6 || dx > length) continue;
                    double along = Math.max(0, dx) / length;
                    // Streams off slightly upward, then sags and spreads.
                    double centre = pk[1] + 0.25 + 0.5 * along - 0.4 * along * along;
                    double spread = 0.35 + 0.9 * along;
                    double body = Math.pow(1 - along, 1.2) * Math.exp(-2 * sq((elev - centre) / spread));
                    double tear = smoothstep(0.30, 0.75, valueNoise((col - WIND * t) * 0.7 + p * 9.1, elev * 1.4 + seed));
                    plume = Math.max(plume, body * tear * (0.4 + 0.6 * gust));
                }
                if (plume > 0.01) budget.add(ref, ColorPalette.SNOW_PLUME, PLUME_LEVEL * spindrift * plume);
            }

            // --- ground blizzard ---------------------------------------
            if (terrain == Terrain.PLAIN) {
                double above = elev - surface;
                if (above > -0.6 && above < DRIFT_HEIGHT) {
                    double reach = smoothstep(DRIFT_HEIGHT, 0.0, above) * smoothstep(-0.6, 0.0, above);
                    // Stretched long along the wind, so it reads as streaks
                    // skating along rather than puffs.
                    double streak = smoothstep(0.42, 0.85, valueNoise((col - DRIFT_WIND * t) * 0.32 + seed, elev * 2.2));
                    double amount = reach * streak * driftGust;
                    if (amount > 0.01) budget.add(ref, ColorPalette.SNOW_PLUME, DRIFT_LEVEL * amount);
                }
            }

            // --- sparkle -----------------------------------------------
            if (sparkle && (ground > 0.5 || spikeCover > 0.5)) {
                double s = 0;
                for (int i = 0; i < SPARKLES; i++) {
                    if (sparkAmt[i] <= 0) continue;
                    double gx = col - sparkX[i], ge = elev - sparkE[i];
                    if (gx * gx + ge * ge < 0.36) s = Math.max(s, sparkAmt[i]);
                }
                if (s > 0) budget.add(ref, ColorPalette.SNOW_SPARKLE, SPARKLE_LEVEL * s);
            }
        }

        return budget.resolve();
    }

    /** Ground height at a column, for everything that isn't the peaks. */
    private double ground(double col, double widthKeys, double boardHeight) {
        double wobble = valueNoise(col * 0.35 + seed, 5.3) - 0.5;
        return switch (terrain) {
            // High on the left and off the top of the board, so the slope
            // runs the whole width instead of stopping at a corner.
            case SLOPE -> lerp(boardHeight * 1.05, boardHeight * 0.28, col / Math.max(1, widthKeys)) + 0.4 * wobble;
            case SPIKES -> 1.3 + 0.3 * wobble;
            case PLAIN -> 2.2 + 0.3 * wobble;
            case PEAKS -> 0.8;
        };
    }

    /**
     * Rolls the skyline or the spikes. Summits are spread out with a minimum
     * gap so the range doesn't clump into one corner, and alternate tall and
     * short-ish so it isn't a row of identical teeth.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double boardHeight) {
        peaks.clear();
        spikes.clear();
        if (terrain == Terrain.PEAKS) {
            double spacing = (widthKeys + 4) / Math.max(1, peakCount);
            for (int p = 0; p < peakCount; p++) {
                double x = -2 + spacing * (p + 0.2 + 0.6 * hash(p, 90));
                double height = boardHeight * lerp(heightMin, heightMax, (p % 2 == 0 ? 0.5 : 0) + 0.5 * hash(p, 91));
                double halfWidth = lerp(3.8, 5.6, hash(p, 92));
                peaks.add(new double[]{x, height, halfWidth});
            }
        } else if (terrain == Terrain.SPIKES) {
            double x = 0.8 + 1.5 * hash(0, 95);
            for (int i = 0; x < widthKeys + 0.5 && i < 32; i++) {
                double halfWidth = lerp(0.55, 1.15, hash(i, 96));
                double height = lerp(1.6, boardHeight - 1.0, hash(i, 97));
                spikes.add(new double[]{x, halfWidth, height, hash(i, 98)});
                x += halfWidth + lerp(1.6, 3.6, hash(i, 99));
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
