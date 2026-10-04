package com.everythingrgbprofile.pattern.patterns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * The generic {@code twinkle-particle} engine, with three presets:
 * firefly-glow, sculk-shimmer and starfield-twinkle.
 *
 * <p>No bundled profile names any of them any more. Lush caves moved to
 * {@code glow-motes}, the deep dark to {@code shimmer-twinkle}, and the End to
 * {@link EndAuroraPattern} (see there for why pale dots on a dark board were
 * the wrong read of that sky). The presets stay because configs out there name
 * them, and an unknown pattern name silently degrades to a plain shimmer. The
 * engine itself still does real work as the glints inside
 * {@link ShimmerTwinklePattern}, which is how the deep dark and the Warden's
 * presence layer get theirs.
 *
 * <p>Not to be confused with {@link ShimmerPattern}, which it superficially
 * resembles and is doing something completely different. Shimmer is a
 * continuous sine wave running on <i>every</i> key. This is a fixed budget of
 * independent point lights that blink into existence, fade out, and reappear
 * somewhere else entirely.
 *
 * <p>Shimmer is a surface breathing. This is individual things glinting in the
 * dark. Those are not the same thought.
 *
 * <p>Same "parameterise, don't subclass" approach as drift-particle. Three
 * presets, one engine, differing only in count and interval.
 *
 * <h2>The slot model</h2>
 * There are exactly {@code twinkleCount} twinkles. Forever. When one finishes
 * it does not get destroyed: its slot is immediately recycled with a new key,
 * a new duration and a jittered future start time.
 *
 * <p>Which bounds the work per frame to a known constant with zero allocation
 * churn, and turns "how busy is this effect" into one number somebody can
 * tune without reading any code.
 */
public final class TwinkleParticlePattern implements Pattern {

    /** One point light. Mutable and package-private-ish on purpose: these get recycled in place. */
    private static final class Twinkle {
        KeyGrid.LedRef ledId;
        double triggerTimeMillis;
        double durationMillis;
    }

    private final int twinkleCount;
    /** Average gap between one slot's twinkles. Bigger = sparser and calmer. */
    private final double twinkleIntervalMillis;
    private final Random random = new Random();
    private List<Twinkle> twinkles;
    private boolean initialized = false;
    /** Last elapsed value seen, purely to notice the clock going backwards. */
    private double lastElapsedMillis = 0;

    public TwinkleParticlePattern(int twinkleCount, double twinkleIntervalMillis) {
        this.twinkleCount = Math.max(1, twinkleCount);
        // 50ms floor: below that you're flashing faster than the frame rate
        // can resolve and it stops reading as twinkling and starts reading as
        // a strobe. Nobody wants that on a title screen.
        this.twinkleIntervalMillis = Math.max(50, twinkleIntervalMillis);
    }

    private void initIfNeeded(PatternContext ctx, long elapsedMillis) {
        if (initialized) return;
        twinkles = new ArrayList<>(twinkleCount);
        for (int i = 0; i < twinkleCount; i++) {
            twinkles.add(null);
        }
        reseed(ctx, elapsedMillis);
        lastElapsedMillis = elapsedMillis;
        initialized = true;
    }

    /**
     * Stagger every slot backwards across one interval, anchored to the clock
     * value handed in.
     *
     * <p>The NEGATIVE start times are the whole trick: seed them all at the
     * current time and every twinkle fires in unison on the next frame, which
     * looks like a bug. Spreading the start times backwards means the layer
     * opens already mid-rhythm, with twinkles at every stage of their life.
     */
    private void reseed(PatternContext ctx, double fromMillis) {
        List<KeyGrid.LedPosition> keys = ctx.grid().allKeys();
        for (int i = 0; i < twinkles.size(); i++) {
            twinkles.set(i, newTwinkle(keys, fromMillis - random.nextDouble() * twinkleIntervalMillis));
        }
    }

    private Twinkle newTwinkle(List<KeyGrid.LedPosition> keys, double triggerTimeMillis) {
        Twinkle twinkle = new Twinkle();
        // Uniform random key. No de-duplication — two twinkles landing on the
        // same key occasionally is fine and actually helps it look organic
        // rather than evenly distributed.
        twinkle.ledId = keys.get(random.nextInt(keys.size())).ref();
        twinkle.triggerTimeMillis = triggerTimeMillis;
        // 30-70% of the interval, so twinkles are visibly shorter than the
        // gaps between them. Push this toward 1.0 and they overlap into a
        // continuous glow, losing the point.
        twinkle.durationMillis = twinkleIntervalMillis * (0.3 + random.nextDouble() * 0.4);
        return twinkle;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        initIfNeeded(ctx, elapsedMillis);

        // The owning effect's clock restarts at zero on every off->on edge
        // (SustainedOverlayEffect stamps activatedAtMillis on each activation),
        // but the slots below hold ABSOLUTE trigger times from the previous
        // activation. A slot last scheduled at t=48s then computes
        // age = 0 - 48000 and takes the "scheduled, hasn't started yet" branch
        // — and so does every other slot, all at once. The layer goes
        // completely silent for exactly as long as it ran the time before.
        //
        // The same defect, in the main menu's own copy of this slot logic
        // (MenuAmbientPattern carries one, with the same fix), is what made the
        // menu come back with its stone base but no embers and no arcane motes
        // at all after a world had been loaded and quit: the shimmer underneath is a pure function of elapsed time and
        // does not care, whereas everything scheduled cares enormously. Same
        // defect would silence the Warden's presence layer on a second
        // encounter.
        //
        // Drift-particle never had this bug because its age < 0 branch
        // respawns rather than skipping. Identical situation, opposite
        // default, and only one of them was right.
        if (elapsedMillis < lastElapsedMillis) {
            reseed(ctx, elapsedMillis);
        }
        lastElapsedMillis = elapsedMillis;

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();

        // Indexed loop, not enhanced-for: we call twinkles.set(i, ...) inside,
        // and mutating a list while iterating it with a for-each is a
        // ConcurrentModificationException waiting to be someone's afternoon.
        for (int i = 0; i < twinkles.size(); i++) {
            Twinkle twinkle = twinkles.get(i);
            double age = elapsedMillis - twinkle.triggerTimeMillis;
            if (age < 0) continue; // scheduled, hasn't started yet
            if (age > twinkle.durationMillis) {
                // Dead. Recycle the slot at a jittered future time so the
                // rhythm never locks into a metronome.
                double next = elapsedMillis + random.nextDouble() * twinkleIntervalMillis;
                twinkles.set(i, newTwinkle(ctx.grid().allKeys(), next));
                continue;
            }
            double p = age / twinkle.durationMillis;
            // 30% attack, 70% decay. Same asymmetry as flash-once and for the
            // same reason: quick in, slow out reads as a glint. Symmetric
            // reads as a slow blink, which is a different and much less
            // interesting thing.
            double brightness = p < 0.3 ? p / 0.3 : 1.0 - (p - 0.3) / 0.7;
            out.put(twinkle.ledId, new LayerPixel(ctx.baseColor(), Math.max(0, brightness)));
        }
        return out;
    }

    // --- Named presets ------------------------------------------------
    // Count and interval together set the "busyness". Fireflies: several,
    // frequent. Sculk: fewer and slower, so the deep dark stays tense and
    // sparse. Stars: many, because it's a sky.
    public static TwinkleParticlePattern fireflyGlow() { return new TwinkleParticlePattern(6, 900); }
    public static TwinkleParticlePattern sculkShimmer() { return new TwinkleParticlePattern(4, 1400); }
    public static TwinkleParticlePattern starfieldTwinkle() { return new TwinkleParticlePattern(10, 1100); }

}
