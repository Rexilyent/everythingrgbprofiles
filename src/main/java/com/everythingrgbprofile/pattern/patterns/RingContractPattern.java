package com.everythingrgbprofile.pattern.patterns;

import java.util.HashMap;
import java.util.Map;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * {@code ring-contract} — the Shrieker Alert, and Warden Emergence with the
 * parameters cranked. {@link RingExpandPattern} run in reverse, and it is
 * genuinely unsettling on purpose.
 *
 * <pre>
 * eased      = p^2                       // ease-IN: accelerates the whole way
 * radius(p)  = maxRadius * (1 - eased)   // edge, closing to nothing
 * brightness = eased                     // gets brighter as it closes
 * </pre>
 *
 * <h2>Why this feels bad (complimentary)</h2>
 * Ring-expand eases OUT and dims — energy leaving, dissipating, calm. This
 * eases IN and brightens — accelerating inward, getting more intense, arriving.
 * That's the difference between "a sensor pinged" and "something has located
 * you and is closing". Same geometry, inverted derivatives, completely
 * inverted emotional read. Cheapest horror in the codebase.
 *
 * <h2>Escalation lives elsewhere</h2>
 * Repeated shrieks deepen the colour, close faster, and flash harder — but
 * none of that is tracked here. {@code SculkAlertEffect} just constructs a new
 * instance with escalated parameters each time. This class stays a pure
 * single-shot renderer with no memory, which makes it trivially testable and
 * means the escalation logic lives in one place instead of two.
 */
public final class RingContractPattern implements Pattern {

    private final double ringThicknessKeys;

    public RingContractPattern(double ringThicknessKeys) {
        this.ringThicknessKeys = ringThicknessKeys;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double duration = Math.max(1, ctx.durationMillis());
        double p = Math.min(1.0, elapsedMillis / duration);
        double eased = p * p; // ease-in quadratic. Starts slow, ends fast. Dread curve.
        double maxRadius = ctx.grid().maxRadius();
        double radius = maxRadius * (1.0 - eased);
        double brightness = eased;

        // Same key-width approximation as ring-expand; see that class for why
        // maxRadius/12 is a perfectly good eyeball figure.
        double thickness = maxRadius / 12.0 * ringThicknessKeys;

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double d = ctx.grid().distanceFromCenter(key);
            if (Math.abs(d - radius) <= thickness / 2.0) {
                out.put(key.ref(), new LayerPixel(ctx.baseColor(), brightness));
            }
        }

        // The impact. In the last 8% of the duration the radius has collapsed
        // to nearly nothing, and without this the ring would simply... stop
        // existing. Anticlimactic. An inward-rushing thing has to LAND.
        //
        // So the centre keys flash, ramping 0->1 across that final 8%, and the
        // ring's own band is still drawing over the top of it. Arrival, not
        // disappearance.
        if (p > 0.92) {
            double flashBrightness = (p - 0.92) / 0.08;
            for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
                if (ctx.grid().distanceFromCenter(key) <= thickness) {
                    // merge() rather than put(): the collapsing ring is
                    // occupying these same centre keys right now. Overwriting
                    // would let the flash's early dim values stomp on the
                    // ring's brightness and produce a visible dip exactly at
                    // the climax. Taking max(alpha) means the two layers can
                    // only ever add light, never subtract it.
                    out.merge(key.ref(), new LayerPixel(ctx.baseColor(), flashBrightness),
                            (a, b) -> new LayerPixel(a.color(), Math.max(a.alpha(), b.alpha())));
                }
            }
        }
        return out;
    }

    @Override
    public boolean isFinished(long elapsedMillis) {
        return false;
    }
}
