package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code flash-once}: a flash with an actual envelope, not a light
 * switch. Fast attack (0 → full over the first 15% of the duration), then an
 * eased decay down to a baseline over the remaining 85%.
 *
 * <p>Used by the death flash, the lightning flash and the advancement flash.
 * (The level-up has its own animation, {@code LevelUpPattern}.)
 *
 * <h2>Why the asymmetry</h2>
 * Because that is what light actually does. Real flashes — muzzle flare,
 * lightning, a camera strobe — rise far faster than they fall, and human
 * visual systems are extremely well calibrated to that fact.
 *
 * <p>A symmetric fade-up-fade-down does not read as a flash at all. It reads
 * as something politely turning itself on and then politely turning itself
 * off. The 15/85 split is the whole reason this lands as an impulse rather
 * than as an animation.
 */
public final class FlashOncePattern implements Pattern {

    /**
     * Where the decay settles instead of going fully dark.
     *
     * <p>Non-zero when the flash is the opening beat of something longer and
     * has to hand off into a sustained glow, rather than blinking out and
     * leaving a hole where the effect used to be. Zero for a plain one-shot,
     * which is every caller there currently is.
     */
    private final double baselineBrightness;

    public FlashOncePattern() {
        this(0.0);
    }

    public FlashOncePattern(double baselineBrightness) {
        this.baselineBrightness = baselineBrightness;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double duration = Math.max(1, ctx.durationMillis());
        double t = elapsedMillis;
        double attackEnd = duration * 0.15;

        double brightness;
        if (t <= attackEnd) {
            // Attack: plain linear. It's 15% of a short duration — nobody has
            // ever perceived the easing curve of a 60ms ramp, so easing it
            // would be maths for its own sake.
            brightness = t / attackEnd;
        } else {
            double p = Math.min(1.0, (t - attackEnd) / (duration - attackEnd));
            // Ease-OUT cubic (not in-out): fast drop off the peak, long lazy
            // tail. This is the shape of an afterglow. Ease-in-out here would
            // hover at full for a beat first and look like a hold, not a decay.
            double easedOut = 1 - Math.pow(1 - p, 3);
            // Remap 1→0 onto 1→baseline, so a non-zero baseline shortens the
            // travel rather than clipping the curve.
            brightness = 1.0 - easedOut * (1.0 - baselineBrightness);
        }

        LayerPixel pixel = new LayerPixel(ctx.baseColor(), brightness);
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedRef key : ctx.effectiveTargetKeys()) {
            out.put(key, pixel);
        }
        return out;
    }

    /**
     * Always false — the owning effect decides when this is over, not the
     * pattern.
     *
     * <p>Looks wrong for a one-shot, is deliberate: the effect already tracks
     * a trigger time and duration for {@code isActive}, and having the pattern
     * keep a second opinion on the same question is how you end up with two
     * clocks disagreeing about whether something is finished.
     */
    @Override
    public boolean isFinished(long elapsedMillis) {
        return false;
    }
}
