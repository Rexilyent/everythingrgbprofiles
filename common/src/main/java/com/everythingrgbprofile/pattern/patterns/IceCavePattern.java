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
 * An ice cave: packed ice walls, icicles hanging off the ceiling, calcite
 * underfoot with ice crystals glowing in it. Every so often one of the
 * icicles lets go and shatters on the floor.
 *
 * <p>Two scenes use this. {@link #iceCaves()} is the biome layer for
 * {@code minecraft:ice_caves}, which Minecraft 26.4 added, and {@link #menu()}
 * is the title-screen theme built from the same cave. Same engine, different
 * pacing, same arrangement as the sulfur cave.
 *
 * <h2>Why the icicles fall</h2>
 * The obvious version of this biome is a blue shimmer, and the mod already
 * owns about four of those. Frozen ocean, ice spikes, frozen peaks: cold and
 * blue is very much covered. An ice cave that just shimmers would be the
 * frozen ocean with the lights off, so it needs a thing that only happens
 * here.
 *
 * <p>The game hands us one. Icicles grow down off packed ice while it's dark,
 * and drop the moment the light around them climbs past 4. So each falling
 * icicle on the board runs the whole life: grows down from the ceiling,
 * hangs there for a while minding its own business, shivers, drops, shatters
 * on the floor in one pale flash, and starts growing back. Most of the
 * icicles never fall at all, because a ceiling that's permanently half grown
 * back looks like a cave with a demolition crew in it.
 *
 * <p>The dripstone caves also have things hanging off a ceiling, and that's
 * fine, they do different jobs. Dripstone is a steady drip on every tip, a
 * texture. This is one event every several seconds somewhere on the board,
 * which is closer to the sulfur cave's pop: a thing that <i>happens</i>.
 *
 * <h2>Where the colours come from</h2>
 * The biome file and the block textures, sampled from the 26.4 snapshots:
 *
 * <ul>
 *   <li>Walls - {@code packed_ice.png} averages {@code #8DB4FA}, which on an
 *       LED is mostly white light with opinions. The base colour is that hue
 *       pushed hard ({@link ColorPalette#ICE_CAVE_PACKED_ICE}), and the deep
 *       ice is <i>derived</i> from it: scaling by {@link #DEEP_ICE_SCALE}
 *       lands within a point of the biome's {@code fog_color},
 *       {@code #14559B}. Recolour the walls and the depth follows, the same
 *       trick the sulfur pool uses.</li>
 *   <li>Icicles - {@code icicle_tip.png}'s lightest common pixels are
 *       {@code #D8E6FF} and {@code #BCD4FF}. See
 *       {@link ColorPalette#ICE_CAVE_ICICLE}.</li>
 *   <li>Floor - calcite and snow, both basically white in the textures. See
 *       {@link ColorPalette#ICE_CAVE_CALCITE} for why it isn't white here.</li>
 *   <li>Crystals - the accent colour, from {@code ice_crystal.png}
 *       ({@code #BCD4FF}, highlights {@code #D8E6FF}) nudged toward cyan so
 *       the glow separates from the walls it's sitting against.</li>
 * </ul>
 *
 * <h2>No state</h2>
 * Same as the sulfur cave: everything is a closed-form function of elapsed
 * time and a slot index. Nothing spawns, nothing gets remembered, so the menu
 * clock snapping back to zero every time somebody quits to the title screen
 * is a complete non-event.
 */
public final class IceCavePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // ---------------------------------------------------------------
    // Preset knobs
    // ---------------------------------------------------------------

    /** Where the floor starts, as a fraction down the board. Lumpy around this. */
    private final double floorLine;
    /** Icicles along the ceiling. */
    private final int icicleCount;
    /** Roughly what share of them fall and regrow. The rest just hang there. */
    private final double fallerShare;
    /** Seconds for one falling icicle's whole life: grow, hang, fall, shatter. */
    private final double icicleCycleSeconds;
    /** Ice crystals glowing on the floor. */
    private final int crystalCount;
    /** Ambient wall brightness. Low, so the shatter has something to be bright against. */
    private final double washFloor;
    /** Seconds for the light sliding through the walls to cross the board once. */
    private final double sheenSeconds;
    /** How much light that sheen adds at its peak. */
    private final double sheenStrength;

    private IceCavePattern(double floorLine, int icicleCount, double fallerShare,
                           double icicleCycleSeconds, int crystalCount,
                           double washFloor, double sheenSeconds, double sheenStrength) {
        this.floorLine = floorLine;
        this.icicleCount = Math.max(1, icicleCount);
        this.fallerShare = Math.max(0, Math.min(1, fallerShare));
        // Has to fit the shiver, the fall, the shatter and a regrow. Anything
        // shorter and the icicle is back on the ceiling before it's hit the floor.
        this.icicleCycleSeconds = Math.max(12.0, icicleCycleSeconds);
        this.crystalCount = Math.max(0, crystalCount);
        this.washFloor = washFloor;
        this.sheenSeconds = Math.max(2.0, sheenSeconds);
        this.sheenStrength = sheenStrength;
    }

    // ---------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------

    /**
     * How far the deep ice is dimmed from the base. Picked to land on the
     * biome's fog colour, not by eye: {@code #2289FA} times 0.62 is
     * {@code #15559B} against the game's {@code #14559B}.
     */
    private static final double DEEP_ICE_SCALE = 0.62;

    /**
     * How much darker the very top of the board is than the middle. Gives the
     * icicles a ceiling to hang out of, so their roots fade into something
     * instead of starting at a full-brightness wall like they were stapled on.
     */
    private static final double CEILING_SHADE = 0.35;

    /** How far the floor edge wanders above and below {@link #floorLine}. */
    private static final double FLOOR_LUMP = 0.06;
    private static final double FLOOR_LIGHT = 0.26;

    /** Width of the sheen band, in board widths. Wide and soft, it's light through ice, not a laser. */
    private static final double SHEEN_WIDTH = 0.13;

    /**
     * Icicle length, as a fraction of board height.
     *
     * <p>Same reasoning as the sulfur spikes, only stricter: rows on a
     * full-size board sit at about 0, 0.27, 0.45, 0.64, 0.82 and 1, so an
     * icicle shorter than 0.27 only ever lights the function row, and one
     * key of pale blue in a row of darker blue is not an icicle, it's a
     * typo. An earlier version went down to 0.12 and most of the ceiling
     * vanished into the wall. So every icicle reaches the number row, and
     * the long ones reach the next row down, because ice cave icicles get
     * up to five blocks long and it would be rude not to.
     */
    private static final double ICICLE_MIN = 0.30;
    private static final double ICICLE_MAX = 0.52;
    private static final double ICICLE_WIDTH_KEYS = 1.7;
    private static final double ICICLE_LIGHT = 0.95;

    /** The flash on a tip when it catches the light. Brief and rare per icicle. */
    private static final double GLINT_LIGHT = 0.75;

    /** The falling half of an icicle's life, in seconds rather than as a share of the cycle. */
    private static final double SHIVER_SECONDS = 1.1;
    private static final double FALL_SECONDS = 0.6;
    private static final double SHATTER_SECONDS = 0.9;
    /** Share of the cycle spent growing back. The rest is just hanging around. */
    private static final double REGROW_SHARE = 0.45;
    /** How much of the icicle's length falls as one piece. The root snaps off and stays behind. */
    private static final double FALLING_PIECE = 0.7;

    private static final double SHATTER_LIGHT = 1.0;
    private static final double SHATTER_RADIUS_KEYS = 1.4;
    /** How flat the shatter spreads. Ice breaking on a floor goes sideways, not up. */
    private static final double SHATTER_SQUASH = 2.4;
    private static final int SHARD_COUNT = 4;

    private static final double CRYSTAL_POOL_KEYS = 1.8;
    private static final double CRYSTAL_POOL_LIGHT = 0.30;
    private static final double CRYSTAL_CORE_LIGHT = 0.34;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();

        RGBColor ice = ctx.baseColor();
        RGBColor crystal = ctx.resolvedAccentColor();

        LightBudget budget = new LightBudget();

        renderWalls(grid, t, ice, budget);
        renderFloor(grid, budget);
        renderCrystals(grid, t, aspect, keyWidth, crystal, budget);
        renderIcicles(grid, t, aspect, keyWidth, ice, budget);

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // The cave
    // ---------------------------------------------------------------

    /**
     * Packed ice above the floor, with light sliding through it.
     *
     * <p>Two things going on. The ice itself wanders between the base colour
     * and the deep fog blue, on two slow crossed sines, so the wall has
     * thicker and thinner patches instead of being a flat blue sheet. And a
     * soft diagonal band of paler light drifts across the whole wall now and
     * then, which is what ice does when there's any light in the room at all.
     * Both are slow on purpose: the walls are the background, and the
     * background's job is to make the icicles and the shatter look good.
     *
     * <p>Floor keys are skipped, for the reason the sulfur cave skips the
     * pool: {@link LightBudget} adds, so a key lit as wall and then as floor
     * comes out a muddy mix of the two.
     */
    private void renderWalls(KeyGrid grid, double t, RGBColor ice, LightBudget budget) {
        RGBColor deep = ice.scaled(DEEP_ICE_SCALE);
        RGBColor sheenColor = ice.lightened(0.55);
        // Runs from off one edge to off the other, so the band is never seen
        // popping into existence on the board.
        double sheenAt = -0.35 + 1.7 * frac(t / sheenSeconds);

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (key.y() >= floorAt(key.x())) continue;
            double x = key.x(), y = key.y();

            double thick = 0.5 + 0.5 * Math.sin(x * 6.1 + y * 3.7 + 0.031 * t)
                    * Math.sin(x * 2.3 - y * 5.9 + 1.7 - 0.019 * t);
            double ceiling = 1.0 - CEILING_SHADE * (1.0 - smoothstep(0.0, 0.3, y));
            budget.add(key.ref(), ice.lerp(deep, thick),
                    washFloor * (0.8 + 0.2 * (1.0 - thick)) * ceiling);

            // Leaning diagonal, so it reads as light coming in from somewhere
            // rather than a progress bar.
            double along = x * 0.85 + y * 0.35;
            double d = (along - sheenAt) / SHEEN_WIDTH;
            double sheen = Math.exp(-d * d);
            if (sheen > 0.02) budget.add(key.ref(), sheenColor, sheenStrength * sheen * ceiling);
        }
    }

    /**
     * Calcite and snow along the bottom of the board. Dim, and per-key uneven
     * so it reads as a floor with stuff on it instead of a lit strip.
     */
    private void renderFloor(KeyGrid grid, LightBudget budget) {
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (key.y() < floorAt(key.x())) continue;
            double grain = 0.8 + 0.2 * hash(key.hashCode());
            budget.add(key.ref(), ColorPalette.ICE_CAVE_CALCITE, FLOOR_LIGHT * grain);
        }
    }

    /**
     * Ice crystals sitting on the floor, each throwing a little pool of light
     * around itself.
     *
     * <p>Steady, with only a slow breath. Ice crystals are a light source in
     * the game, and light sources don't twinkle. Also the icicle tips already
     * do the sparkly thing, and two layers both sparkling is how a cave turns
     * into a disco.
     */
    private void renderCrystals(KeyGrid grid, double t, double aspect, double keyWidth,
                                RGBColor crystal, LightBudget budget) {
        double radius = CRYSTAL_POOL_KEYS * keyWidth;
        RGBColor core = crystal.lightened(0.3);
        for (int i = 0; i < crystalCount; i++) {
            // Stratified and jittered, so three crystals don't end up in a
            // huddle at one end of the board.
            double cx = (i + 0.2 + 0.6 * hash(i * 7 + 3)) / crystalCount;
            // Pushed down into the floor rather than on its edge, or the core
            // lands on the row above and lights a whole Shift key like a lamp.
            double cy = Math.min(1.0, floorAt(cx) + 0.09);
            double breath = 0.85 + 0.15 * Math.sin(TAU * (0.05 + 0.03 * hash(i * 11 + 5)) * t
                    + hash(i * 13 + 1) * TAU);
            blob(grid, budget, aspect, cx, cy, radius, 1.0, crystal, CRYSTAL_POOL_LIGHT * breath);
            KeyGrid.LedPosition key = nearestKey(grid, cx, cy);
            if (key != null) budget.add(key.ref(), core, CRYSTAL_CORE_LIGHT * breath);
        }
    }

    /**
     * Icicles along the ceiling, some of which fall.
     *
     * <p>A faller's cycle, timed in seconds back from the end of the cycle so
     * the fast part takes the same real time on every preset:
     *
     * <ol>
     *   <li>Regrow - from nothing to full length over the first
     *       {@link #REGROW_SHARE} of the cycle. Eased, so it slows as it gets
     *       there, like it's thinking about it.</li>
     *   <li>Hang - full length, glinting like the others.</li>
     *   <li>Shiver - a second or so of flicker. Without a warning beat the
     *       drop looks like the key glitched.</li>
     *   <li>Fall - the lower part of the icicle drops, speeding up. The root
     *       stays on the ceiling, which is why the regrow doesn't start from a
     *       bare patch.</li>
     *   <li>Shatter - one hard frame of pale light where it hits, spreading
     *       flat along the floor, with a few shards kicked up.</li>
     * </ol>
     */
    private void renderIcicles(KeyGrid grid, double t, double aspect, double keyWidth,
                               RGBColor ice, LightBudget budget) {
        double baseWidth = ICICLE_WIDTH_KEYS * keyWidth;
        int middle = icicleCount / 2;

        for (int i = 0; i < icicleCount; i++) {
            double sx = (i + 0.2 + 0.6 * hash(i * 23 + 7)) / icicleCount;
            double full = ICICLE_MIN + (ICICLE_MAX - ICICLE_MIN) * hash(i * 29 + 11);
            // The middle one always falls, so a small board or an unlucky
            // roll still gets at least one. A cave where nothing ever falls
            // is just the frozen ocean again.
            boolean faller = i == middle || hash(i * 61 + 3) < fallerShare;
            double glint = glint(i, t);

            if (!faller) {
                drawIcicle(grid, budget, ice, sx, full, baseWidth, 1.0);
                tipGlint(grid, budget, sx, full, glint);
                continue;
            }

            double period = icicleCycleSeconds * (0.8 + 0.4 * hash(i * 43 + 5));
            double age = frac(t / period + hash(i * 47 + 9)) * period;
            double shatterStart = period - SHATTER_SECONDS;
            double fallStart = shatterStart - FALL_SECONDS;
            double shiverStart = fallStart - SHIVER_SECONDS;
            double regrowEnd = period * REGROW_SHARE;
            double stub = full * (1.0 - FALLING_PIECE);
            double floorY = floorAt(sx);

            if (age < regrowEnd) {
                double grown = smoothstep(0.0, 1.0, age / regrowEnd);
                double length = stub + (full - stub) * grown;
                drawIcicle(grid, budget, ice, sx, length, baseWidth, 1.0);
                // No glint until it's mostly there. A sparkle on a stub looks
                // like a stuck pixel.
                if (grown > 0.8) tipGlint(grid, budget, sx, length, glint);

            } else if (age < shiverStart) {
                drawIcicle(grid, budget, ice, sx, full, baseWidth, 1.0);
                tipGlint(grid, budget, sx, full, glint);

            } else if (age < fallStart) {
                double f = (age - shiverStart) / SHIVER_SECONDS;
                // Fast flicker that gets worse toward the drop. Brightness
                // only: actually moving it sideways is sub-key at this scale
                // and would just look like noise.
                double shake = Math.abs(Math.sin(TAU * 9.0 * t + i)) * f;
                drawIcicle(grid, budget, ice, sx, full, baseWidth, 1.0 + 0.7 * shake);

            } else if (age < shatterStart) {
                drawIcicle(grid, budget, ice, sx, stub, baseWidth, 1.0);
                double f = (age - fallStart) / FALL_SECONDS;
                // Squared, so it leaves slowly and arrives fast. Gravity, more
                // or less, at a scale where real gravity would be over before
                // anybody saw it.
                double tip = full + (floorY - full) * f * f;
                double top = Math.max(stub, tip - (full - stub));
                drawFalling(grid, budget, sx, top, tip, baseWidth);

            } else {
                drawIcicle(grid, budget, ice, sx, stub, baseWidth, 1.0);
                double f = (age - shatterStart) / SHATTER_SECONDS;
                drawShatter(grid, budget, aspect, keyWidth, i, sx, floorY, f);
            }
        }
    }

    /**
     * One icicle hanging from the ceiling, root at the top edge of the board.
     *
     * <p>Brightest at the tip, which is the opposite of the sulfur spikes.
     * That's what the textures do (the tip is the palest part) and it's also
     * what makes it read as ice. A thin end catching the light is ice; a
     * thick end glowing is a stalactite with a lamp in it.
     */
    private static void drawIcicle(KeyGrid grid, LightBudget budget, RGBColor ice,
                                   double sx, double length, double baseWidth, double gain) {
        if (length < 0.03) return;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (key.y() > length) continue;
            double along = key.y() / length;   // 0 at the ceiling, 1 at the tip
            double width = baseWidth * (1.0 - 0.55 * along);
            double dx = Math.abs(key.x() - sx);
            if (dx >= width) continue;
            double across = 1.0 - dx / width;
            // Already halfway to pale at the root. With only two or three rows
            // to work with, a root in the wall's own colour is just more wall.
            budget.add(key.ref(), ice.lerp(ColorPalette.ICE_CAVE_ICICLE, 0.45 + 0.55 * along),
                    across * across * (0.55 + 0.45 * along) * ICICLE_LIGHT * gain);
        }
    }

    /** The falling piece: a short pale spike between two heights, pointed end down. */
    private static void drawFalling(KeyGrid grid, LightBudget budget,
                                    double sx, double top, double tip, double baseWidth) {
        double span = Math.max(0.02, tip - top);
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (key.y() < top || key.y() > tip) continue;
            double along = (key.y() - top) / span;
            double width = baseWidth * 0.85 * (1.0 - 0.6 * along);
            double dx = Math.abs(key.x() - sx);
            if (dx >= width) continue;
            double across = 1.0 - dx / width;
            budget.add(key.ref(), ColorPalette.ICE_CAVE_ICICLE,
                    across * across * (0.5 + 0.5 * along) * ICICLE_LIGHT * 1.1);
        }
    }

    /**
     * Ice hitting the floor.
     *
     * <p>The core key gets the full flash, outright, for the reason the
     * sulfur pop does: the impact is smaller than the gap between LEDs, and a
     * pure distance falloff hands it whatever brightness the alignment
     * happens to allow. Around it, a squashed glow spreads along the floor,
     * and a few shards fly off sideways on little arcs and fade. All of it
     * dies on a square, so the peak is a frame or two at any refresh rate.
     */
    private static void drawShatter(KeyGrid grid, LightBudget budget, double aspect, double keyWidth,
                                    int slot, double sx, double floorY, double f) {
        double fade = (1.0 - f) * (1.0 - f);
        KeyGrid.LedPosition core = nearestKey(grid, sx, floorY);
        if (core != null) budget.add(core.ref(), ColorPalette.ICE_CAVE_SHATTER, SHATTER_LIGHT * fade);
        blob(grid, budget, aspect, sx, floorY, SHATTER_RADIUS_KEYS * keyWidth * (1.0 + 2.5 * f),
                SHATTER_SQUASH, ColorPalette.ICE_CAVE_SHATTER, SHATTER_LIGHT * 0.5 * fade);

        for (int s = 0; s < SHARD_COUNT; s++) {
            double dir = (s % 2 == 0) ? -1 : 1;
            double reach = 0.05 + 0.07 * hash(slot * 71 + s * 13 + 1);
            double height = 0.10 + 0.14 * hash(slot * 73 + s * 17 + 2);
            double x = sx + dir * reach * f;
            double y = floorY - height * Math.sin(Math.PI * f);
            KeyGrid.LedPosition key = nearestKey(grid, x, y);
            if (key != null) budget.add(key.ref(), ColorPalette.ICE_CAVE_ICICLE, 0.55 * (1.0 - f));
        }
    }

    /**
     * A tip catching the light. One key, a sharp spike of brightness, then
     * nothing for several seconds. Each icicle on its own clock, so the
     * ceiling sparkles at random instead of in a chorus line.
     */
    private static void tipGlint(KeyGrid grid, LightBudget budget, double sx, double tipY, double glint) {
        if (glint <= 0.01) return;
        KeyGrid.LedPosition key = nearestKey(grid, sx, tipY);
        if (key != null) budget.add(key.ref(), ColorPalette.ICE_CAVE_SHATTER, GLINT_LIGHT * glint);
    }

    /**
     * 0..1, sitting at zero nearly all the time. A sine raised to a high power
     * is a cheap way to get a short spike once per period with no state, and
     * the per-icicle period keeps them from ever lining up for long.
     */
    private static double glint(int slot, double t) {
        double period = 5.0 + 6.0 * hash(slot * 31 + 13);
        double s = Math.sin(TAU * t / period + hash(slot * 37 + 3) * TAU);
        return s <= 0 ? 0 : Math.pow(s, 40);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Where the floor starts in a given column. Fixed, not animated: floors
     * that move are for the sulfur pool, and calcite isn't known for it.
     */
    private double floorAt(double x) {
        return floorLine + FLOOR_LUMP * (0.6 * Math.sin(x * 7.3 + 1.1) + 0.4 * Math.sin(x * 13.7 + 2.9));
    }

    /**
     * Soft round light at a normalised point. {@code squash} above 1 flattens
     * it vertically, which the shatter uses to spread along the floor.
     */
    private static void blob(KeyGrid grid, LightBudget budget, double aspect,
                             double cx, double cy, double radius, double squash,
                             RGBColor color, double strength) {
        if (strength <= 0.001 || radius <= 0) return;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - cx, (key.y() - cy) * aspect * squash);
            if (d >= radius) continue;
            double falloff = 1.0 - d / radius;
            budget.add(key.ref(), color, falloff * falloff * strength);
        }
    }

    /** Nearest LED to a normalised point. Brute force, like everywhere else in here. */
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

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return v * v * (3 - 2 * v);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** Deterministic 0..1 from an integer. Same idiom as the sulfur cave and the motes. */
    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    // ---------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------

    /**
     * The biome layer for {@code minecraft:ice_caves}.
     *
     * <p>Seven icicles, about half of them fallers, on a cycle averaging 22
     * seconds: one icicle comes down somewhere every seven seconds or so.
     * Often enough that standing in the cave you'll see it happen, rare
     * enough that it's still an event and not weather.
     */
    public static IceCavePattern iceCaves() {
        return new IceCavePattern(0.86, 7, 0.45, 22.0, 4, 0.38, 13.0, 0.22);
    }

    /**
     * The title-screen theme.
     *
     * <p>Slower everywhere, more ceiling, fewer fallers. About one drop every
     * fifteen seconds, and the sheen takes twenty to cross. Same rule as every
     * other menu theme: somebody can leave the game on the title screen for
     * hours, so this has to be easy to ignore. The shatter is the one sharp
     * thing allowed, and it gets away with it by being one key for a moment.
     */
    public static IceCavePattern menu() {
        return new IceCavePattern(0.88, 8, 0.3, 38.0, 3, 0.32, 21.0, 0.18);
    }
}
