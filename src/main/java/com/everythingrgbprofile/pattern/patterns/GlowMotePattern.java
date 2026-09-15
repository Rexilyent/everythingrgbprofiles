package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * Motes drifting through the dark, with warm glow pools underneath them.
 *
 * <p>Two biomes use this: the enchanted tangle, where the motes rise, and lush
 * caves, where they fall. Same engine, different palette and a sign flip -
 * which is the whole reason it is parameterised rather than copied.
 *
 * <p>Direction carries more meaning here than it looks like it should. The
 * enchanted tangle's motes rise, and that is most of what makes the place read
 * as enchanted rather than merely wooded: things that fall are obeying physics,
 * things that rise are not. Lush caves gets the opposite, because its particles
 * are {@code falling_spore_blossom} shed from blossoms on the ceiling — they
 * drift down, and having the two biomes move in opposite directions keeps them
 * from reading as the same place in two colours.
 *
 * <h2>Where each palette comes from</h2>
 * The mote palette is lifted straight out of the particle this biome actually
 * spawns. Biomes We've Gone declares {@code borealis_glint} on
 * {@code enchanted_tangle}, and that texture is a three-frame animation:
 *
 * <ul>
 *   <li>mint green {@code #82E6C1}</li>
 *   <li>cyan {@code #83DBE9}</li>
 *   <li>violet {@code #9082E8}</li>
 * </ul>
 *
 * <p>Its {@code .mcmeta} runs those at fifteen ticks a frame with
 * interpolation on, so one full turn of the colour wheel takes 2.25 seconds,
 * which is the period that preset uses. Every mote sits at a different point in
 * the cycle, so the board shows all three at once while each one still visibly
 * shifts as it travels.
 *
 * <p><b>Lush caves is grounded differently, and it is worth knowing why.</b>
 * Its particles use the plain {@code drip_fall} texture and are tinted in code,
 * so there is no texture to sample the way the glint could be. The palette
 * comes from the biome's own foliage instead — moss, azalea and dripleaf all
 * sample between hue 66 and 80, a distinctly <i>yellow</i>-green. That is worth
 * stating plainly because the profile used to be {@code #4FBF6B} at hue 136,
 * which is a blue-green and simply not the colour the biome is.
 *
 * <p>Both biomes get the same warm layer, and both have earned it: the
 * enchanted tangle's green trees carry {@code glow_berry_decorator}, and lush
 * caves is where glow berries come from in the first place. The berry texture
 * samples closer to yellow-gold than orange, but the block emits light at level
 * fourteen and what you see in the world is a warm amber pool, so that is what
 * this draws.
 *
 * <h2>Keeping it legible</h2>
 * Same rule the blood and the canopy learned the hard way: the background sits
 * near the bottom of the LED's range and the motes run near the top, with as
 * little as possible in between. A mote is meant to be a bright spark against
 * a dark tangle. If the ambient wash creeps up toward the motes, the whole
 * thing collapses into a lilac smear — so if this ever needs more punch, take
 * light away from the wash rather than adding it to the motes, which are
 * already close to the ceiling.
 *
 * <h2>No state</h2>
 * Motes and berry anchors are pure functions of elapsed time and an index.
 * Nothing is spawned, recycled or remembered, so there is nothing to reseed
 * when the effect clock restarts — the same approach the bats and the menu
 * drill use, and for the same reason.
 */
public final class GlowMotePattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /**
     * The colours a mote cycles through, in order, interpolated.
     *
     * <p>For the enchanted tangle these are sampled values from the biome's own
     * particle texture. Do not "tidy" them toward a neater ramp: being the real
     * ones is the entire reason the board matches the world.
     */
    private final RGBColor[] palette;
    /** Seconds for one full turn of {@link #palette}. */
    private final double cycleSeconds;
    /** +1 for motes that rise, -1 for motes that fall. */
    private final int direction;

    private final int moteCount;
    private final int berryCount;
    /** Seconds for a mote to travel from below the board to above it. */
    private final double moteLifeSeconds;
    /** Ambient wash brightness. Dim on purpose — see the legibility note. */
    private final double washFloor;

    private GlowMotePattern(RGBColor[] palette, double cycleSeconds, int direction,
                            int moteCount, int berryCount,
                            double moteLifeSeconds, double washFloor) {
        this.palette = palette;
        this.cycleSeconds = cycleSeconds;
        this.direction = direction >= 0 ? 1 : -1;
        this.moteCount = moteCount;
        this.berryCount = berryCount;
        this.moteLifeSeconds = moteLifeSeconds;
        this.washFloor = washFloor;
    }

    /** Mote size in key widths, and how far behind it the tail reaches. */
    private static final double MOTE_RADIUS_KEYS = 1.45;
    private static final int MOTE_TAIL = 3;
    private static final double MOTE_TAIL_STEP = 0.045;

    /** How far a mote wanders sideways as it climbs, and how fast it weaves. */
    private static final double MOTE_DRIFT = 0.16;
    private static final double MOTE_SWAY = 0.035;

    /** Glow-berry pool size in key widths. */
    private static final double BERRY_RADIUS_KEYS = 2.8;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        RGBColor tangle = ctx.baseColor();
        RGBColor berry = ctx.resolvedAccentColor();

        LightBudget budget = new LightBudget();

        // --- the tangle itself ------------------------------------------
        // Dim, uneven, slow. This is the dark you see the motes against, so it
        // stays well below them; the per-key phase keeps it from reading as one
        // flat backlight.
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double phase = hash(key.hashCode()) * TAU;
            double breath = 0.82 + 0.18 * Math.sin(TAU * 0.05 * t + phase);
            budget.add(key.ref(), tangle, washFloor * breath);
        }

        // --- glow berries -----------------------------------------------
        double berryRadius = BERRY_RADIUS_KEYS * keyWidth;
        for (int i = 0; i < berryCount; i++) {
            // One berry per band across the width, jittered inside its band.
            // Hashing the position outright clumped two of the three together
            // and left a third of the board with no warm light at all — with
            // this few anchors, a uniform roll is not uniform in practice.
            double bx = (i + 0.2 + 0.6 * hash(i * 7 + 1)) / berryCount;
            // Held to the lower half: these hang off vines, and putting them
            // along the bottom rows also keeps them clear of the motes, which
            // spend most of their lives higher up.
            double by = 0.45 + 0.45 * hash(i * 13 + 5);
            double pulse = 0.72 + 0.28 * Math.sin(TAU * (0.07 + 0.05 * hash(i * 3 + 2)) * t
                    + hash(i * 11 + 4) * TAU);
            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double d = Math.hypot(key.x() - bx, (key.y() - by) * aspect);
                if (d >= berryRadius) continue;
                double falloff = 1.0 - d / berryRadius;
                budget.add(key.ref(), berry, falloff * falloff * pulse * 0.55);
            }
        }

        // --- motes --------------------------------------------------------
        double moteRadius = MOTE_RADIUS_KEYS * keyWidth;
        for (int i = 0; i < moteCount; i++) {
            // Stratified the same way, and for the same reason: hashed lanes
            // left the leftmost mote at a fifth of the way across, so the first
            // few columns never saw one. This gives each mote its own band of
            // the board and jitters it within that band, which stays irregular
            // without leaving holes.
            double lane = (i + 0.15 + 0.70 * hash(i * 17 + 3)) / moteCount;
            double phase = hash(i * 23 + 9);
            double speedScale = 0.75 + 0.5 * hash(i * 29 + 6);
            double life = moteLifeSeconds / speedScale;

            // One closed-form loop: no spawning, no recycling, no state.
            double progress = frac(t / life + phase);
            // Enters just off one edge and leaves off the other, whichever way
            // round this preset travels.
            double entry = direction > 0 ? 1.10 : -0.10;
            double my = entry - direction * progress * 1.25;
            double drift = (hash(i * 31 + 7) - 0.5) * 2 * MOTE_DRIFT;
            double mx = lane + drift * progress
                    + MOTE_SWAY * Math.sin(TAU * (0.35 + 0.4 * hash(i * 37 + 8)) * t + phase * TAU);

            // In quickly, out slowly, so a mote appears rather than pops and
            // dissolves near the top rather than vanishing mid-air.
            double envelope = progress < 0.12
                    ? progress / 0.12
                    : Math.min(1.0, (1.0 - progress) / 0.28);
            if (envelope <= 0.02) continue;

            RGBColor color = moteColorAt(t + phase * cycleSeconds * 3);

            for (int tail = 0; tail < MOTE_TAIL; tail++) {
                // The tail hangs below the head, because that is where the mote
                // has been. Converted through the aspect so it is the same
                // physical length whatever shape the board is.
                // The tail lags behind, which is the opposite way to travel.
                double ty = my + direction * tail * MOTE_TAIL_STEP / aspect;
                double strength = envelope * (1.0 - tail / (double) MOTE_TAIL);

                // Nearest key at full strength, then a soft halo around it.
                //
                // The halo alone is not enough, and this is worth understanding
                // before shrinking anything: a mote is smaller than the gap
                // between LEDs, so a purely distance-based falloff hands it
                // whatever brightness its alignment happens to allow. Measured,
                // that cost a badly-placed mote about three quarters of its
                // light, and most of the board's motes came out dim for no
                // reason the code expressed anywhere. Lighting the nearest key
                // outright guarantees every mote a core, and the halo then only
                // has to soften the edges rather than carry the whole spark.
                KeyGrid.LedPosition core = nearestKey(grid, mx, ty);
                if (core != null) budget.add(core.ref(), color, strength);

                for (KeyGrid.LedPosition key : grid.allKeys()) {
                    double d = Math.hypot(key.x() - mx, (key.y() - ty) * aspect);
                    if (d >= moteRadius) continue;
                    double falloff = 1.0 - d / moteRadius;
                    budget.add(key.ref(), color, falloff * falloff * strength * 0.5);
                }
            }
        }

        return budget.resolve();
    }

    /**
     * The mote colour at a given moment, interpolated between palette stops.
     *
     * <p>Interpolated rather than stepped because the glint texture's own
     * {@code .mcmeta} sets {@code interpolate: true} — stepping would be three
     * colours taking turns, which is a visibly different and worse thing.
     */
    private RGBColor moteColorAt(double seconds) {
        double pos = frac(seconds / cycleSeconds) * palette.length;
        int index = (int) pos;
        double f = pos - index;
        return palette[index % palette.length].lerp(palette[(index + 1) % palette.length], f);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
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

    /** Deterministic 0..1 from an integer. Same idiom as the menu's torch trail. */
    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    /** Borealis glint frames, sampled from the biome's own particle texture. */
    private static final RGBColor[] BOREALIS = {
            RGBColor.fromHex("#82E6C1"),
            RGBColor.fromHex("#83DBE9"),
            RGBColor.fromHex("#9082E8"),
    };
    /** 3 frames x 15 ticks, straight off that texture's .mcmeta. */
    private static final double BOREALIS_CYCLE = 3 * 15 / 20.0;

    /**
     * Lush caves greens.
     *
     * <p>All three sit within fifteen degrees of each other on purpose. There
     * is no colour cycle to copy here the way there is for the glint, so this
     * shimmers within one green rather than travelling between hues — a cave
     * lit through moss does not change colour, it changes shade.
     *
     * <p>One deliberate liberty: the biome's foliage actually samples between
     * hue 66 and 80, which is a chartreuse. Rendered honestly at that hue,
     * beside amber glow berries at hue 35, the two sit close enough to muddy
     * each other and the "green" reads as yellow. These are nudged into the
     * mid-eighties and nineties, far enough to stay unmistakably green next to
     * the warm layer while still being the biome's own family — and nothing
     * like the {@code #4FBF6B} this profile used to carry, which was hue 136
     * and a blue-green.
     */
    private static final RGBColor[] LUSH = {
            RGBColor.fromHex("#73D115"),
            RGBColor.fromHex("#6AFF14"),
            RGBColor.fromHex("#7ABD17"),
    };

    /**
     * The enchanted tangle: motes rising, cycling blue-green-violet.
     *
     * <p>Ten motes is the number where the board reads as busy with something
     * without any single mote becoming hard to follow. The wash floor is low
     * for the reason given in the class notes, and is the first thing to reach
     * for if this ever needs to look deeper.
     */
    public static GlowMotePattern enchantedTangle() {
        return new GlowMotePattern(BOREALIS, BOREALIS_CYCLE, +1, 10, 3, 7.5, 0.17);
    }

    /**
     * Lush caves: spores falling, shimmering through greens.
     *
     * <p>Slower and slightly sparser than the tangle. Spore blossoms shed a
     * thin, unhurried drift rather than a swarm, and the longer life gives each
     * mote time to visibly change shade on the way down — which is the only
     * thing carrying the colour here, since the palette stays inside one hue.
     */
    public static GlowMotePattern lushCaves() {
        return new GlowMotePattern(LUSH, 4.5, -1, 8, 3, 9.0, 0.18);
    }
}
