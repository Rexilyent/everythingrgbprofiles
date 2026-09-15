package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * A dim shimmering wash in the base colour, with bright sparse twinkles in the
 * accent colour scattered over it. Two existing engines stacked, not a third
 * one written from scratch.
 *
 * <h2>The gap this fills</h2>
 * {@link ShimmerPattern} covers every key but never produces a highlight.
 * {@link TwinkleParticlePattern} produces highlights but writes <b>only</b>
 * the keys currently twinkling — every other key is absent from its output, so
 * the compositor's black prefill shows through untouched. Either is fine when
 * the biome colour is bright: The End's cream {@code #D8CB9A} twinkling on
 * black reads as a starfield, which is the intent.
 *
 * <p>It falls apart when the biome colour is itself nearly black. The Deep
 * Dark was {@code twinkle-particle} at {@code #10182B} — luminance 24, barely
 * off black — with four twinkle slots, so roughly two keys were lit at any
 * moment, in a colour almost indistinguishable from the unlit board around
 * them. The effect was working exactly as written and looked like the mod had
 * switched off.
 *
 * <p>Splitting the two roles across two colours is what fixes it: the base
 * colour becomes a dark wash that occupies the whole board so it never reads
 * as empty, and the accent colour carries the glints, free to be as bright as
 * it needs to be without lightening the biome overall.
 *
 * <p>Layers are summed through a {@link LightBudget} rather than overwriting
 * each other, so a twinkle fading out sinks smoothly back into the wash
 * instead of cutting a hole through it on its dimmest frames.
 */
public final class ShimmerTwinklePattern implements Pattern {

    private final ShimmerPattern wash;
    private final TwinkleParticlePattern glints;
    private final double washBrightness;
    private final double glintBrightness;

    /**
     * @param washFrequencyHz  how fast the base breathes. Slow — this is
     *                         wallpaper, not a signal.
     * @param washFloor        how far the wash dims at its lowest, before
     *                         washBrightness scales it.
     * @param washBrightness   ceiling on the wash, so the base stays a dark
     *                         presence rather than competing with the glints.
     *                         This is the number that decides whether the
     *                         biome reads as "dark" or merely "dim".
     * @param glintCount       twinkle slots. The headline "how alive is this".
     * @param glintIntervalMillis average gap between one slot's glints.
     */
    public ShimmerTwinklePattern(double washFrequencyHz, double washFloor, double washBrightness,
                                 int glintCount, double glintIntervalMillis, double glintBrightness) {
        this.wash = new ShimmerPattern(washFrequencyHz, washFloor);
        this.glints = new TwinkleParticlePattern(glintCount, glintIntervalMillis);
        this.washBrightness = washBrightness;
        this.glintBrightness = glintBrightness;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        LightBudget budget = new LightBudget();

        // The wash renders in ctx.baseColor() already, so its own pixels carry
        // the right colour and only need scaling down.
        budget.addLayer(wash.render(ctx, elapsedMillis), washBrightness);

        // The twinkle engine also paints in whatever ctx.baseColor() is, so it
        // gets a context whose base IS the accent. resolvedAccentColor()
        // falls back to a lightened base when a profile supplies no accent,
        // which keeps this usable on any biome without one.
        PatternContext glintCtx = ctx.withColor(ctx.resolvedAccentColor());
        budget.addLayer(glints.render(glintCtx, elapsedMillis), glintBrightness);

        return budget.resolve();
    }

    // --- Named presets ------------------------------------------------

    /**
     * The Deep Dark. A cold near-black teal wash with bright cyan glints
     * scattered across it — sculk veins catching the light.
     *
     * <p>Twelve slots rather than sculk-shimmer's four. That sounds busy and
     * isn't: about six keys are lit at any moment out of a hundred-odd, and
     * the wash underneath means the board no longer depends on them to look
     * like anything. Sparse works as an aesthetic when there is something to
     * be sparse against; against pure black it just reads as off.
     */
    public static ShimmerTwinklePattern sculkVeins() {
        return new ShimmerTwinklePattern(0.11, 0.35, 0.55, 12, 900, 1.0);
    }

    /**
     * A Warden is nearby. Deliberately quieter and slower than
     * {@link #sculkVeins}: a dimmer wash breathing at half the rate, with
     * fewer glints that sit brighter when they do arrive.
     *
     * <p>It has to be distinguishable from the Deep Dark biome layer it
     * replaces — this is Tier 1 priority 11 taking the board off the biome at
     * priority 0 — while still reading as the same place. Darker and slower
     * does that; going louder would have made a Warden's arrival feel like a
     * celebration.
     *
     * @param glintCount    from config, so the escalation-minded can tune it
     * @param glintInterval likewise
     */
    public static ShimmerTwinklePattern wardenPresence(int glintCount, double glintInterval) {
        return new ShimmerTwinklePattern(0.06, 0.28, 0.42, glintCount, glintInterval, 1.0);
    }

    /** Generic fallback: slow wash, a few accent glints. */
    public static ShimmerTwinklePattern gentle() {
        return new ShimmerTwinklePattern(0.16, 0.40, 0.70, 6, 1200, 0.85);
    }
}
