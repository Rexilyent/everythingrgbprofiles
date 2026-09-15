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
 * <p>Used by the Death Flash, the progression flashes, and — with its
 * parameters pinned at max escalation — inside Warden emergence via
 * ring-contract's arrival flash.
 *
 * <h2>Why the asymmetry</h2>
 * Because that's what light does. Real flashes — muzzle flare, lightning, a
 * camera strobe — rise faster than they fall, and your visual system is
 * extremely well calibrated to that. A symmetric fade-up-fade-down doesn't
 * read as a flash, it reads as something politely turning on and off. The
 * 15/85 split is what makes this feel like an impulse rather than an
 * animation.
 */
public final class FlashOncePattern implements Pattern {

    /**
     * Where the decay settles instead of going fully dark.
     *
     * <p>Non-zero when the flash is the opening beat of a longer effect and
     * needs to hand off to a sustained glow rather than blink out and leave a
     * hole. Zero for a plain one-shot.
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
