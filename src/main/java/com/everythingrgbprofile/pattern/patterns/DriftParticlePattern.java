package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The generic {@code drift-particle} engine: little things moving across your
 * keyboard. One class, eight presets, zero subclasses.
 *
 * <p>Petal-drift, snow-drift, sand-drift, bubble-rise, ember-rise,
 * spore-drift, drip-fall, and the Rain Cascade overlay's rain-drift are all
 * <b>this class with different numbers</b>. Falling cherry petals and rising
 * Nether embers differ by a direction angle and a lifespan. Every preset works
 * purely through parameters with zero additional code — which is nice, because
 * that goal is usually aspirational.
 *
 * <p>Lifecycle: a particle spawns just off the upstream edge, drifts across at
 * its own randomised speed, drags a short fading trail, and respawns when it
 * either leaves the board or outlives {@code lifespanMillis}.
 */
public final class DriftParticlePattern implements Pattern {

    /** One in-flight particle. Mutable, recycled in place, never escapes this class. */
    private static final class Particle {
        double x, y;         // normalised grid space, 0..1
        double spawnTimeMillis;
        double speed;        // grid-units per second
        /** Draw this one in the accent instead of the base. See the two-tone note on render. */
        boolean accent;
    }

    /**
     * The angle you want to SEE on the board. 0 = right, 90 = down; screen
     * convention, so +y is downward.
     *
     * <p>Aspect-corrected before use — see {@link #resolveDirection}. It used
     * to be applied raw, which quietly flattened every diagonal.
     */
    private final double directionDegrees;
    private final int particleCount;
    private final int trailLength;
    private final double lifespanMillis;
    /** Fraction of a particle's life spent at full brightness before the age-fade begins. */
    private final double fadeStartFraction;
    private final Random random = new Random();
    private List<Particle> particles;
    private boolean initialized = false;

    /**
     * Travel direction in normalised space, aspect-corrected from
     * {@link #directionDegrees}. Resolved on the first render, because the
     * board's real shape is not known until a context arrives.
     */
    private double dirX = 1, dirY = 0;
    private boolean directionResolved = false;

    /** Fades across the whole life, which is what every drift preset but rain wants. */
    public DriftParticlePattern(double directionDegrees, int particleCount, int trailLength, double lifespanMillis) {
        this(directionDegrees, particleCount, trailLength, lifespanMillis, 0.0);
    }

    /**
     * @param fadeStartFraction how far into its life a particle stays at full
     *                          brightness before it starts fading. 0 means
     *                          "fade from the moment it spawns", which is the
     *                          right shape for something dissipating as it
     *                          travels — a petal, a spore, a bubble.
     *                          <p>It is the wrong shape for rain. A raindrop
     *                          does not get fainter on the way down, and with
     *                          the fade spread across the whole journey the
     *                          only brightly-lit drops are the ones that have
     *                          just spawned — which is to say, the ones still
     *                          off the top edge of the board. What reaches the
     *                          middle rows is always dim, and what reaches the
     *                          bottom rows is nothing at all.
     */
    public DriftParticlePattern(double directionDegrees, int particleCount, int trailLength,
                                double lifespanMillis, double fadeStartFraction) {
        this.directionDegrees = directionDegrees;
        this.particleCount = Math.max(1, particleCount);
        this.trailLength = Math.max(1, trailLength);
        this.lifespanMillis = Math.max(50, lifespanMillis);
        // Capped below 1.0 so the divisor in render() can never be zero.
        this.fadeStartFraction = Math.max(0.0, Math.min(0.95, fadeStartFraction));
    }

    /**
     * Turns the authored visual angle into a normalised-space direction.
     *
     * <h2>The flattening this fixes</h2>
     * Normalised coordinates span 0..1 on both axes whatever the board's real
     * shape, so on a full-size keyboard one unit of y is only about 0.30 units
     * of x in physical terms. Feeding {@code (cos, sin)} straight in therefore
     * squashes every diagonal toward horizontal: petal-drift is authored at
     * 160 degrees — down and to the left — and rendered at 173.8, which is
     * "sideways" with a rounding error of downward. It read as leaves blowing
     * flat across the board rather than drifting down, because that is
     * precisely what it was doing.
     *
     * <h2>Why it renormalises afterwards</h2>
     * Dividing the y term by the aspect makes the angle right but the vector
     * long — a straight-down direction would come out 3.4x its old magnitude,
     * and since particle speed is expressed in these units, every vertical
     * preset would suddenly move 3.4x faster and rain's carefully-fitted
     * lifespan would stop reaching the bottom of the board. Scaling back to
     * unit length keeps speed in the units every preset was tuned in.
     *
     * <p>Consequence worth knowing: the pure-axis presets (0, 90, 270) are
     * mathematically unchanged by all of this — they normalise straight back
     * to what they already were. Only the diagonals move, and they move to
     * where they always claimed to be pointing.
     */
    private void resolveDirection(PatternContext ctx) {
        if (directionResolved) return;
        double rad = Math.toRadians(directionDegrees);
        double aspect = Math.max(0.05, ctx.grid().aspectRatio());
        double vx = Math.cos(rad);
        double vy = Math.sin(rad) / aspect;
        double length = Math.hypot(vx, vy);
        if (length > 1e-9) {
            dirX = vx / length;
            dirY = vy / length;
        }
        directionResolved = true;
    }

    private void initIfNeeded(PatternContext ctx) {
        if (initialized) return;
        resolveDirection(ctx);
        particles = new ArrayList<>(particleCount);
        for (int i = 0; i < particleCount; i++) {
            // Negative spawn times again — same trick as the twinkle engine.
            // Spawn them all at 0 and you get one tidy rank of particles
            // marching across in formation, which looks like a screensaver
            // from 1997. Staggering backwards means the effect opens with
            // particles already spread across the board at every stage of
            // their journey.
            // Alternating rather than random: with only five or six particles
            // a coin flip per slot quite happily deals you six of one colour,
            // and a two-tone biome that renders single-tone half the time is
            // worse than one that never tried.
            particles.add(spawnParticle(-random.nextDouble() * lifespanMillis, i % 2 == 1));
        }
        initialized = true;
    }

    private Particle spawnParticle(double spawnTimeMillis, boolean accent) {
        Particle particle = new Particle();
        particle.accent = accent;
        double dx = dirX, dy = dirY;
        // Start from the board centre, back off 0.6 units AGAINST the travel
        // direction (so it's off-board upstream regardless of which way this
        // preset drifts), then slide a random amount along the perpendicular
        // (-dy, dx) so particles enter spread out instead of single file.
        //
        // Doing it with vectors rather than four if-branches for
        // up/down/left/right is why an arbitrary angle like 160° works without
        // any special-casing — which is the whole reason petal-drift can fall
        // diagonally.
        double perpOffset = random.nextDouble();
        particle.x = 0.5 - dx * 0.6 + (-dy) * (perpOffset - 0.5);
        particle.y = 0.5 - dy * 0.6 + dx * (perpOffset - 0.5);
        particle.spawnTimeMillis = spawnTimeMillis;
        // Per-particle speed variation. Without it every particle moves in
        // lockstep and the whole field slides like one texture instead of
        // behaving like independent objects.
        particle.speed = 0.4 + random.nextDouble() * 0.3;
        return particle;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        initIfNeeded(ctx);
        double dx = dirX, dy = dirY;
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();

        // Two-tone, but only if the profile actually asked for it.
        //
        // Deliberately ctx.accentColor() and NOT ctx.resolvedAccentColor():
        // the resolved form invents an accent by lightening the base when
        // none was configured, which would quietly turn all two dozen
        // single-colour drift biomes into base-plus-a-paler-base without
        // anyone asking. A null accent here means one colour, full stop.
        //
        // Magnolia Woodland is what this is for: it grows pink and white
        // magnolias side by side, so drawing it in one flat pink would be
        // both duller and less accurate than the biome deserves.
        RGBColor baseTint = ctx.baseColor();
        RGBColor accentTint = ctx.accentColor() != null ? ctx.accentColor() : baseTint;

        // Indexed loop because we set() into the list mid-iteration. See the
        // twinkle engine for the same note.
        for (int i = 0; i < particles.size(); i++) {
            Particle particle = particles.get(i);
            double age = elapsedMillis - particle.spawnTimeMillis;
            if (age > lifespanMillis || age < 0) {
                // age < 0 shouldn't normally fire (that's the pre-seeded
                // stagger, handled by the >= 0 path), but respawning on it is
                // the safe response if elapsed ever goes backwards.
                // Carry the colour across the recycle, so the mix stays even
                // for the whole life of the effect rather than drifting.
                particles.set(i, spawnParticle(elapsedMillis, particle.accent));
                continue;
            }
            // Position is recomputed from spawn time every frame rather than
            // integrated step-by-step. Stateless in the frame rate: drop to
            // 10fps and particles are in exactly the same places, just sampled
            // less often. Integrating would make speed depend on frame timing.
            double t = age / 1000.0;
            double headX = particle.x + dx * particle.speed * t;
            double headY = particle.y + dy * particle.speed * t;

            for (int trail = 0; trail < trailLength; trail++) {
                double back = trail * 0.03; // 0.03 grid units per trail step
                double px = headX - dx * back;
                double py = headY - dy * back;
                // Slightly generous bounds (-0.05..1.05) so a particle at the
                // very edge still lights its nearest key instead of popping
                // out one step early.
                if (px < -0.05 || px > 1.05 || py < -0.05 || py > 1.05) continue;

                KeyGrid.LedPosition nearest = nearestKey(ctx.grid(), px, py);
                if (nearest == null) continue;

                // Two independent fades multiplied: the particle dims over its
                // whole life, AND each trail segment is dimmer than the one
                // ahead. So a particle fades out as a unit while always
                // reading head-bright-to-tail-dim.
                // Full brightness up to fadeStartFraction, then a linear ramp
                // to nothing across whatever life is left. At the default
                // fadeStartFraction of 0 this collapses to the plain
                // 1 - age/lifespan it has always been, so every preset except
                // rain renders byte-identically to before.
                double lifeFraction = age / lifespanMillis;
                double fadeByAge = lifeFraction <= fadeStartFraction
                        ? 1.0
                        : 1.0 - (lifeFraction - fadeStartFraction) / (1.0 - fadeStartFraction);
                double fadeByTrail = 1.0 - trail / (double) trailLength;
                double brightness = Math.max(0, fadeByAge) * fadeByTrail;
                // merge with ADDITIVE alpha (clamped), unlike the spiral's
                // max(). Particles are individual glowing motes: two crossing
                // in the same spot should be brighter than one. Max() would
                // make them merely not-dimmer, which loses the density cue
                // that makes heavy snow read as heavy.
                out.merge(nearest.ref(), new LayerPixel(particle.accent ? accentTint : baseTint, brightness),
                        (a, b) -> new LayerPixel(a.color(), Math.min(1.0, a.alpha() + b.alpha())));
            }
        }
        return out;
    }

    /**
     * Nearest LED to a normalised point, by brute-force scan.
     *
     * <p>Yes, it's O(keys) per lookup and it runs per trail segment per
     * particle per frame. On a ~110-key board with ~8 particles and ~3 trail
     * segments that's about 2,600 distance checks a frame, which is nothing —
     * a spatial index would be more code, more state, and unmeasurably faster.
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

    // --- Named presets ------------------------------------------------
    // Every one of these is (direction, count, trail, lifespan). No preset has
    // any code of its own. The angles carry the meaning, and they follow the
    // convention declared on directionDegrees: 0 = right, 90 = DOWN, 180 =
    // left, 270 = up.
    //
    // These are VISUAL angles now — what you see on the board, not what the
    // raw vector says. See resolveDirection.
    //
    //   90      = straight down (drips, rain — gravity, no messing about)
    //   100     = snow, near-vertical with a slight lean
    //   140     = down and to the left at a good diagonal (petals — carried
    //             sideways by wind while they fall, which is the whole
    //             character of cherry blossom. Was 160, which the aspect
    //             flattening rendered as 173.8 — sideways.)
    //   200     = up and to the left (spores — buoyant, drifting off a
    //             mushroom rather than falling from a cloud)
    //   0       = horizontal (sand, blown sideways)
    //   270/280 = UP (bubbles, embers — buoyant, the sign flip that turns
    //             falling into rising)
    public static DriftParticlePattern petalDrift() { return new DriftParticlePattern(140, 6, 3, 3500); }
    public static DriftParticlePattern snowDrift() { return new DriftParticlePattern(100, 8, 3, 4000); }
    public static DriftParticlePattern sandDrift() { return new DriftParticlePattern(0, 6, 4, 3000); }
    public static DriftParticlePattern bubbleRise() { return new DriftParticlePattern(270, 6, 3, 2800); }
    public static DriftParticlePattern emberRise() { return new DriftParticlePattern(280, 7, 3, 2200); }
    public static DriftParticlePattern sporeDrift() { return new DriftParticlePattern(200, 5, 3, 4000); }
    public static DriftParticlePattern dripFall() { return new DriftParticlePattern(90, 4, 2, 1800); }

    /** Rain at its built-in defaults. See {@link #rainDrift(int, int)}. */
    public static DriftParticlePattern rainDrift() {
        return rainDrift(DEFAULT_RAIN_COUNT, DEFAULT_RAIN_LIFESPAN_MILLIS);
    }

    /** Rain particle count and lifespan both come from config — see RGBProfileConfig. */
    public static final int DEFAULT_RAIN_COUNT = 12;
    public static final int DEFAULT_RAIN_LIFESPAN_MILLIS = 2800;

    /**
     * The Rain Cascade overlay.
     *
     * <h2>Direction: 90, not 180</h2>
     * It was 180. Per the convention above that is dead horizontal, travelling
     * LEFT — so drops entered at the right edge of the board and blew
     * sideways across it, which is precisely what it looked like. The comment
     * that used to sit here filed 180 alongside 90 as "straight down", and
     * that mistaken grouping is the whole bug: 90 is down, 180 is left, and
     * only dripFall was actually using the right one.
     *
     * <h2>Lifespan: long enough to actually arrive</h2>
     * Particles spawn 0.6 units upstream of centre (y = -0.1, just off the top
     * edge) and have to reach y > 1.05 to leave the far side — 1.15 units of
     * travel, at the engine's 0.4-0.7 units/sec. The slowest drop therefore
     * needs about 2.9 seconds to cross the board. At the old 1200ms lifespan
     * it managed roughly 0.6 units before being recycled, so drops died around
     * the middle of the keyboard and the bottom two rows never saw rain at
     * all. 2800ms gets even a slow drop to the bottom edge.
     *
     * <p>A consequence worth knowing when tuning: fast drops now finish early
     * and idle off-board until their lifespan expires, so a given count puts
     * fewer drops on the board than it used to. The default count is up from 7
     * to compensate.
     *
     * <h2>fadeStartFraction 0.8</h2>
     * Rain holds full brightness for the first 80% of the fall and only fades
     * over the last stretch, which reads as the drop landing. See the
     * constructor for why the default fade-from-birth shape is wrong here.
     */
    public static DriftParticlePattern rainDrift(int particleCount, int lifespanMillis) {
        return new DriftParticlePattern(90, particleCount, 2, lifespanMillis, 0.8);
    }
}
