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
 * A sulfur cave: an acid pool along the bottom of the board that bubbles, pops
 * and vents, under a ceiling of sulfur spikes.
 *
 * <p>Two scenes use this. {@link #sulfurCaves()} is the biome layer for
 * {@code minecraft:sulfur_caves}, and {@link #menu()} is the title-screen
 * theme, because from Minecraft 26.2 the vanilla panorama <i>is</i> a sulfur
 * cave — an acid pool with sulfur clumps floating in it, spikes hanging off
 * the ceiling, cinnabar showing through the walls. Same engine, different
 * pacing; see the two factory methods for which way each one leans.
 *
 * <h2>The pop is the point</h2>
 * Every other ambient biome in the mod is continuous: shimmer breathes, motes
 * drift, petals fall, embers rise. Nothing in them ever happens. That is
 * correct for a forest and wrong for this place, which in the world is
 * audibly and visibly <i>active</i> — bubbles surface in the pools and burst.
 *
 * <p>So each bubble here has a life with an event in the middle of it. It
 * climbs through the acid, swells when it reaches the surface, bursts in one
 * bright frame of pale sulfur, and leaves a puff of gas that rises up the
 * board and thins out. The burst is the only hard transient in any Tier 1
 * ambient pattern, and it is what makes this biome recognisable from across
 * the room without reading the colour at all.
 *
 * <p>That also settles what this is <b>not</b>. A shimmer in sulfur yellow
 * would have been ten minutes' work and would have said nothing: half the
 * biome table already shimmers, so it would have made the sulfur caves the
 * same place as the savanna in a different tint. The rule the mod follows is
 * that a biome worth adding is worth giving its own motion.
 *
 * <h2>Where the colours come from</h2>
 * The biome's own definition, and the block textures it is built out of:
 *
 * <ul>
 *   <li>Acid pool — the biome sets {@code water_color} to {@code #34BF89} and
 *       {@code water_fog_color} to {@code #17543C}. The pool's near colour is
 *       that water pushed to something an LED can actually show
 *       ({@link ColorPalette#SULFUR_ACID_POOL}), and the depth gradient is
 *       <i>derived</i> from it rather than configured separately: scaling by
 *       {@link #DEEP_POOL_SCALE} lands on {@code #0A5B3B}, within a few points
 *       of the fog colour the game uses. Recolouring the pool therefore keeps
 *       its depth in the same family instead of leaving a stale teal floor
 *       under a new surface.</li>
 *   <li>Gas — the biome's {@code fog_color}, {@code #8CB831}. A yellow-green,
 *       which is the whole reason the vent reads as something escaping rather
 *       than as more of the wall: the rock is at hue 51 and the gas at 75, far
 *       enough apart to separate and close enough to belong together.</li>
 *   <li>Rock, spikes, cinnabar — sampled from {@code sulfur.png}
 *       ({@code #BDAF65}), {@code sulfur_spike_up_tip.png} ({@code #D5CC6D})
 *       and {@code cinnabar.png} ({@code #97524E}). See
 *       {@link ColorPalette} for what was moved and why.</li>
 * </ul>
 *
 * <h2>No state</h2>
 * Everything is a closed-form function of elapsed time and a slot index —
 * bubbles, spikes, cinnabar, the surface wave. Nothing is spawned, recycled or
 * remembered, so nothing needs reseeding when the effect clock restarts, which
 * matters most for the menu: that clock goes back to zero every time somebody
 * quits to the title screen. The particle engines carry a reseed guard for
 * exactly this; this pattern has no state to guard.
 */
public final class SulfurCavePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // ---------------------------------------------------------------
    // Preset knobs
    // ---------------------------------------------------------------

    /** Where the acid surface sits, as a fraction down the board. */
    private final double poolLine;
    /** Bubble slots in flight at once. Each one is independently phased. */
    private final int bubbleCount;
    /** Seconds for one slot to climb, burst and vent. */
    private final double bubbleLifeSeconds;
    /** Spikes hanging off the ceiling. */
    private final int spikeCount;
    /** Cinnabar blotches showing through the rock. */
    private final int cinnabarCount;
    /** Ambient rock brightness. Dim on purpose — see the legibility note below. */
    private final double washFloor;
    /** How much light a vent puts on the upper board. */
    private final double gasStrength;

    private SulfurCavePattern(double poolLine, int bubbleCount, double bubbleLifeSeconds,
                              int spikeCount, int cinnabarCount,
                              double washFloor, double gasStrength) {
        this.poolLine = poolLine;
        this.bubbleCount = Math.max(1, bubbleCount);
        this.bubbleLifeSeconds = Math.max(1.0, bubbleLifeSeconds);
        this.spikeCount = Math.max(0, spikeCount);
        this.cinnabarCount = Math.max(0, cinnabarCount);
        this.washFloor = washFloor;
        this.gasStrength = gasStrength;
    }

    // ---------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------

    /**
     * How far the surface rides up and down, in board heights.
     *
     * <p>Two sines rather than one, at unrelated frequencies and travelling in
     * opposite directions, which is the same trick the dunes and the canopy
     * use. A single sine gives a surface that visibly repeats on a clock, and
     * on a menu somebody leaves running for an hour that repetition is the
     * thing they end up noticing.
     */
    private static final double SURFACE_WAVE = 0.024;

    /** Fraction of the pool depth that counts as the lit surface film. */
    private static final double SURFACE_RIM = 0.16;
    /** Extra light along that film. The waterline is the scene's one hard edge. */
    private static final double SURFACE_RIM_GAIN = 0.34;
    /** Base brightness of the acid, before depth shading. */
    private static final double POOL_FLOOR = 0.42;
    /**
     * How much of that is lost by the bottom edge of the board.
     *
     * <p>Deliberately small, because the colour gradient is already doing this
     * job. An earlier version dimmed by 0.42 on top of the lerp toward the deep
     * colour, and dimming a colour that is already being dimmed costs twice
     * what it looks like on paper: the bottom two rows of the board arrived
     * near black and the scene simply ended early. Depth is carried by the hue
     * here and only nudged by the brightness.
     */
    private static final double POOL_DEPTH_FALLOFF = 0.20;

    /**
     * How far the pool colour is dimmed at full depth.
     *
     * <p>Chosen to reproduce the biome's declared {@code water_fog_color}
     * rather than picked by eye; see the colour notes on the class.
     */
    private static final double DEEP_POOL_SCALE = 0.42;

    private static final double BUBBLE_RADIUS_KEYS = 1.25;
    private static final double BUBBLE_SWAY = 0.035;
    /** Light on a bubble while it is still under the surface. */
    private static final double BUBBLE_RISE_LIGHT = 0.55;
    /** Light in the frame it bursts. The one transient in the pattern. */
    private static final double POP_LIGHT = 1.0;

    /** Slot timeline: climb, swell at the surface, burst, vent. */
    private static final double RISE_END = 0.58;
    private static final double SWELL_END = 0.68;
    private static final double POP_END = 0.74;

    /** How far a vent climbs above the waterline, and how wide it spreads. */
    private static final double GAS_RISE = 0.62;
    private static final double GAS_RADIUS_KEYS = 3.0;
    private static final double GAS_SPREAD = 2.1;
    /** How far clear of the water a vent starts, so it is not born half-clipped. */
    private static final double GAS_LIFTOFF = 0.03;

    /**
     * Spike length range, as a fraction of board height.
     *
     * <p>The longest reach the second row of keys rather than stopping inside
     * the first. A full-size board normalises its six rows to 0, 0.27, 0.45,
     * 0.64, 0.82 and 1, so a ceiling that ends at 0.22 can only ever light the
     * function row — which is not a ceiling, it is a row of keys that happen to
     * be yellow. Reaching past 0.27 is what gives the longest spikes somewhere
     * to hang into.
     */
    private static final double SPIKE_MIN = 0.10;
    private static final double SPIKE_MAX = 0.40;
    private static final double SPIKE_WIDTH_KEYS = 1.1;
    private static final double SPIKE_LIGHT = 0.42;

    private static final double CINNABAR_RADIUS_KEYS = 2.9;
    private static final double CINNABAR_LIGHT = 0.34;

    /** Which side of the waterline a blob is allowed to light. */
    private enum Band {
        /** Anywhere. */
        ANY,
        /** Below the surface only — for bubbles still in the acid. */
        POOL,
        /** Above it only — for gas that has already escaped. */
        AIR
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();

        RGBColor rock = ctx.baseColor();
        RGBColor pool = ctx.resolvedAccentColor();
        RGBColor deep = pool.scaled(DEEP_POOL_SCALE);

        LightBudget budget = new LightBudget();

        renderRock(grid, t, rock, budget);
        renderCinnabar(grid, t, aspect, keyWidth, budget);
        renderSpikes(grid, t, keyWidth, budget);
        renderPool(grid, t, pool, deep, budget);
        renderBubbles(grid, t, aspect, keyWidth, pool, budget);

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // The cave
    // ---------------------------------------------------------------

    /**
     * Dim, uneven rock above the waterline.
     *
     * <p>Keys under the surface are skipped rather than washed and then
     * overpainted. {@link LightBudget} <b>adds</b> light, so lighting a key as
     * rock and again as acid would not hide the rock, it would mix the two
     * into a khaki-teal that is neither — and the waterline, which the whole
     * composition hangs off, would go soft.
     *
     * <h2>Keeping it legible</h2>
     * Same rule the motes and the canopy arrived at: the wash sits near the
     * bottom of the LED's range and the events run near the top, with as
     * little as possible in between. A burst is meant to be a shock against a
     * dark cave. If this wash creeps up toward it, the board flattens into one
     * murky yellow — so if the scene ever needs more punch, take light away
     * from here rather than adding it to the bubbles, which are already at the
     * ceiling of what the hardware can do.
     */
    private void renderRock(KeyGrid grid, double t, RGBColor rock, LightBudget budget) {
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (key.y() > surfaceAt(key.x(), t)) continue;
            double phase = hash(key.hashCode()) * TAU;
            double breath = 0.84 + 0.16 * Math.sin(TAU * 0.045 * t + phase);
            budget.add(key.ref(), rock, washFloor * breath);
        }
    }

    /**
     * Cinnabar showing through the wall: a few dull red blotches, each on its
     * own slow pulse.
     *
     * <p>Cinnabar is the other ore the 26.2 caves introduced, and in the
     * panorama it is most of what stops the scene being monochrome — whole
     * faces of the far wall are red. On a keyboard it earns its place for a
     * different reason: sulfur yellow, acid green and pale sulfur are all
     * within about thirty degrees of hue on the warm-to-green side, and
     * without something on the other side of the wheel the board has no
     * anchor. So this stays dim and slow, and never does anything, and the
     * scene reads as a cave rather than as a colour.
     */
    private void renderCinnabar(KeyGrid grid, double t, double aspect, double keyWidth, LightBudget budget) {
        double radius = CINNABAR_RADIUS_KEYS * keyWidth;
        for (int i = 0; i < cinnabarCount; i++) {
            // Stratified across the width and jittered inside its band, for the
            // reason the glow berries are: with three or four anchors a uniform
            // roll reliably clumps two of them together and leaves a third of
            // the board with nothing on it.
            double cx = (i + 0.2 + 0.6 * hash(i * 5 + 1)) / cinnabarCount;
            // Held above the waterline. A vein under the acid would be light
            // arriving from inside the pool, which is not a thing the scene has.
            double cy = 0.08 + 0.34 * hash(i * 9 + 2);
            double pulse = 0.70 + 0.30 * Math.sin(TAU * (0.030 + 0.018 * hash(i * 13 + 3)) * t
                    + hash(i * 17 + 4) * TAU);
            double scale = 0.75 + 0.5 * hash(i * 19 + 5);
            blob(grid, budget, t, aspect, cx, cy, radius * scale,
                    ColorPalette.SULFUR_CINNABAR, CINNABAR_LIGHT * pulse, Band.AIR);
        }
    }

    /**
     * Sulfur spikes hanging off the ceiling.
     *
     * <p>Ceiling only, although the block comes in both orientations and the
     * world grows them from the floor as well. The floor of this board is the
     * pool, and a spike standing in it would be competing with the bubbles for
     * the same two rows — the reading that would be lost is worth more than the
     * one that would be gained.
     *
     * <p>Each spike is brightest where it meets the rock and keeps a dim point
     * at the tip. That gradient is doing structural work rather than
     * decorative: it gives the top row of the board an edge to be, so the
     * scene has a ceiling instead of simply running out of keys.
     */
    private void renderSpikes(KeyGrid grid, double t, double keyWidth, LightBudget budget) {
        for (int i = 0; i < spikeCount; i++) {
            double sx = (i + 0.2 + 0.6 * hash(i * 23 + 7)) / spikeCount;
            double length = SPIKE_MIN + (SPIKE_MAX - SPIKE_MIN) * hash(i * 29 + 11);
            double glow = 0.55 + 0.45 * Math.sin(TAU * (0.042 + 0.030 * hash(i * 31 + 13)) * t
                    + hash(i * 37 + 3) * TAU);
            double baseWidth = SPIKE_WIDTH_KEYS * keyWidth;
            for (KeyGrid.LedPosition key : grid.allKeys()) {
                if (key.y() > length) continue;
                double along = 1.0 - key.y() / length;   // 1 at the ceiling, 0 at the tip
                double width = baseWidth * (0.25 + 0.75 * along);
                double dx = Math.abs(key.x() - sx);
                if (dx >= width) continue;
                double across = 1.0 - dx / width;
                budget.add(key.ref(), ColorPalette.SULFUR_SPIKE,
                        across * across * (0.35 + 0.65 * along) * SPIKE_LIGHT * glow);
            }
        }
    }

    /**
     * The acid itself: everything below the surface, shading from the water
     * colour at the top to the fog colour at the bottom.
     *
     * <p>The film along the waterline is lit harder than anything else in the
     * ambient layers. A pool rendered as a flat band of green is a coloured
     * area; a pool with a lit edge is a <i>surface</i>, and once the board has
     * a surface the bubbles have somewhere to arrive.
     */
    private void renderPool(KeyGrid grid, double t, RGBColor pool, RGBColor deep, LightBudget budget) {
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double surface = surfaceAt(key.x(), t);
            if (key.y() <= surface) continue;
            double span = Math.max(0.05, 1.0 - surface);
            double depth = Math.min(1.0, (key.y() - surface) / span);
            double rim = depth < SURFACE_RIM ? 1.0 - depth / SURFACE_RIM : 0.0;
            double light = POOL_FLOOR * (1.0 - POOL_DEPTH_FALLOFF * depth)
                    + SURFACE_RIM_GAIN * rim * rim;
            budget.add(key.ref(), pool.lerp(deep, depth), light);
        }
    }

    /**
     * One slot's whole life: climb, swell, burst, vent.
     *
     * <p>Written as four segments of a single normalised progress rather than
     * as a state machine with a spawn queue, which is what makes the pattern
     * stateless. The segment boundaries are the only tuning that matters here:
     * the climb takes well over half the cycle because that is the part that
     * has to look unhurried, the swell and the burst together take about an
     * eighth because a burst that is legible as an event has to be brief, and
     * the vent gets the rest because gas thinning out is the part that lingers.
     */
    private void renderBubbles(KeyGrid grid, double t, double aspect, double keyWidth,
                               RGBColor pool, LightBudget budget) {
        double bubbleRadius = BUBBLE_RADIUS_KEYS * keyWidth;
        double gasRadius = GAS_RADIUS_KEYS * keyWidth;
        // A bubble in the acid is the acid, lit from within, rather than a
        // fourth colour: it is a pocket of gas with the pool's light around it.
        RGBColor bubbleColor = pool.lightened(0.45);

        for (int i = 0; i < bubbleCount; i++) {
            double lane = (i + 0.15 + 0.70 * hash(i * 41 + 2)) / bubbleCount;
            double phase = hash(i * 47 + 9);
            double speed = 0.75 + 0.5 * hash(i * 43 + 5);
            double progress = frac(t / (bubbleLifeSeconds / speed) + phase);
            double surface = surfaceAt(lane, t);

            if (progress < RISE_END) {
                double climb = progress / RISE_END;
                // Enters just below the bottom edge so it is never seen
                // appearing out of nothing mid-pool.
                double by = 1.04 - (1.04 - surface) * climb;
                double bx = lane + BUBBLE_SWAY * climb
                        * Math.sin(TAU * (0.40 + 0.30 * hash(i * 53 + 1)) * t + phase * TAU);
                // Gas expands as the pressure above it drops, which is a real
                // thing a rising bubble does and also happens to make the
                // approach to the surface readable.
                blob(grid, budget, t, aspect, bx, by, bubbleRadius * (0.55 + 0.45 * climb),
                        bubbleColor, BUBBLE_RISE_LIGHT * (0.35 + 0.65 * climb), Band.POOL);

            } else if (progress < SWELL_END) {
                // Held at the waterline, growing and brightening. Without this
                // beat the burst has no upbeat and lands as a random flash.
                double f = (progress - RISE_END) / (SWELL_END - RISE_END);
                blob(grid, budget, t, aspect, lane, surface, bubbleRadius * (1.0 + 0.8 * f),
                        bubbleColor.lerp(ColorPalette.SULFUR_POP, f * 0.5),
                        BUBBLE_RISE_LIGHT + (0.35 - BUBBLE_RISE_LIGHT) * f, Band.ANY);

            } else if (progress < POP_END) {
                // The burst. Blows outward and dies on a square, so the peak is
                // effectively one or two frames however fast the board is being
                // driven — the thing that reads as a pop rather than a pulse.
                double f = (progress - SWELL_END) / (POP_END - SWELL_END);
                double fade = (1.0 - f) * (1.0 - f);
                KeyGrid.LedPosition core = nearestKey(grid, lane, surface);
                if (core != null) {
                    budget.add(core.ref(), ColorPalette.SULFUR_POP, POP_LIGHT * fade);
                }
                blob(grid, budget, t, aspect, lane, surface, bubbleRadius * (1.8 + 2.4 * f),
                        ColorPalette.SULFUR_POP, POP_LIGHT * 0.55 * fade, Band.ANY);

            } else {
                // What the burst let out. Climbs, spreads and thins.
                double f = (progress - POP_END) / (1.0 - POP_END);
                double gy = surface - GAS_LIFTOFF - GAS_RISE * f;
                // In quickly so it looks let out rather than switched on, then
                // a long even fade, because gas dispersing is the slow half.
                double envelope = Math.min(1.0, f / 0.10) * (1.0 - f);
                blob(grid, budget, t, aspect, lane, gy, gasRadius * (0.6 + GAS_SPREAD * f),
                        ColorPalette.SULFUR_GAS, gasStrength * envelope, Band.AIR);
            }
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Where the acid surface is at a given column, this instant.
     *
     * <p>A function of x rather than a constant, so the waterline is a moving
     * edge instead of a row of keys that happen to be green. Every layer that
     * cares which side of the water it is on asks this, which is why the wave
     * never desynchronises from the pool it belongs to.
     */
    private double surfaceAt(double x, double t) {
        return poolLine + SURFACE_WAVE
                * (0.62 * Math.sin(TAU * 0.055 * t + x * 5.1)
                 + 0.38 * Math.sin(TAU * 0.037 * t - x * 8.3 + 1.9));
    }

    /**
     * Soft round light at a normalised point, optionally clipped to one side of
     * the waterline.
     *
     * <p>The clip is what keeps the surface crisp. A rising bubble's halo
     * spilling into the rock above it reads as the wall glowing, and a vent
     * reaching back down into the acid reads as nothing at all; both blur the
     * one edge the composition is built on.
     */
    private void blob(KeyGrid grid, LightBudget budget, double t, double aspect,
                      double cx, double cy, double radius, RGBColor color,
                      double strength, Band band) {
        if (strength <= 0.001 || radius <= 0) return;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (band != Band.ANY) {
                boolean submerged = key.y() > surfaceAt(key.x(), t);
                if (submerged != (band == Band.POOL)) continue;
            }
            double d = Math.hypot(key.x() - cx, (key.y() - cy) * aspect);
            if (d >= radius) continue;
            double falloff = 1.0 - d / radius;
            budget.add(key.ref(), color, falloff * falloff * strength);
        }
    }

    /**
     * Nearest LED to a normalised point.
     *
     * <p>Used only by the burst, and for the reason the motes light their core
     * key outright: the event is smaller than the gap between LEDs, so a purely
     * distance-based falloff hands it whatever brightness its alignment happens
     * to allow, and a badly-placed burst comes out dim for no reason expressed
     * anywhere in the code. Brute force over the key list, which is what every
     * other pattern here does; see DriftParticlePattern for why that is fine.
     */
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

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** Deterministic 0..1 from an integer. Same idiom as the motes and the torch trail. */
    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    // ---------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------

    /**
     * The biome layer for {@code minecraft:sulfur_caves}.
     *
     * <p>Busier than the menu, and the pool is higher up the board, because
     * this plays while somebody is standing in the place rather than looking at
     * a picture of it. Nine slots on a six-and-a-half-second cycle works out at
     * roughly one burst a second somewhere on the board — often enough that the
     * cave is audibly alive, rare enough per lane that each burst is still an
     * event rather than a texture.
     */
    public static SulfurCavePattern sulfurCaves() {
        return new SulfurCavePattern(0.56, 9, 6.5, 5, 2, 0.20, 0.55);
    }

    /**
     * The title-screen theme, matched to Minecraft 26.2's own panorama.
     *
     * <p>Everything is slower and there is more of the cave and less of the
     * water, which is how the panorama is framed: the pool is a feature of the
     * shot, not the subject. Six slots on a nine-second cycle is about one
     * burst every two seconds.
     *
     * <p>The restraint is the design, the same as the default theme's. A menu
     * runs for as long as somebody leaves the game sitting there, which can be
     * hours, so this has to be pleasant in peripheral vision and completely
     * ignorable — a harder brief than looking impressive. The burst is the one
     * thing here that is allowed to be sharp, and it is kept to roughly one
     * key's worth of board for a fraction of a second precisely so that it can
     * be.
     */
    public static SulfurCavePattern menu() {
        return new SulfurCavePattern(0.62, 6, 9.0, 7, 3, 0.17, 0.46);
    }
}
