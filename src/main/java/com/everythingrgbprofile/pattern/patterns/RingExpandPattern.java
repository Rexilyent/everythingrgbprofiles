package com.everythingrgbprofile.pattern.patterns;

import java.util.HashMap;
import java.util.Map;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * {@code ring-expand}, which drives the Sculk Sensor Ping: a ring grows
 * outward from the middle of the keyboard and fades as it goes. Sonar ping,
 * basically.
 *
 * <pre>
 * eased      = 1 - (1-p)^2      // ease-out: fast expansion, gentle settle
 * radius(p)  = maxRadius * eased
 * brightness = 1 - p            // linear fade
 * </pre>
 *
 * <h2>Why it reads as calm rather than alarming</h2>
 * The ease-out is doing the emotional work. The ring bolts outward
 * immediately then decelerates, which is what a wave dissipating looks like.
 * Ease-IN would have it accelerate outward — that reads as something rushing
 * at you, which is the wrong feeling entirely for "a sensor noticed you
 * exist". Escalating that into actual dread is the shrieker's job, and it uses
 * ring-CONTRACT for exactly that reason: inward motion is threatening,
 * outward motion is informative.
 */
public final class RingExpandPattern implements Pattern {

    private final double ringThicknessKeys;

    public RingExpandPattern(double ringThicknessKeys) {
        this.ringThicknessKeys = ringThicknessKeys;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double duration = Math.max(1, ctx.durationMillis());
        double p = Math.min(1.0, elapsedMillis / duration);
        double eased = 1 - Math.pow(1 - p, 2);
        double maxRadius = ctx.grid().maxRadius();
        double radius = maxRadius * eased;
        double brightness = 1.0 - p;

        // Thickness is authored in "key widths" because that's the unit a
        // human tuning a config can reason about. The grid is normalised
        // rather than physically measured in millimetres, so we approximate
        // one key width as maxRadius/12 — a full-size board is roughly 12
        // keys from centre to corner. It's an eyeball figure and it does not
        // need to be better than that: the output is a band of LEDs either
        // lit or not, and being 15% off just makes the ring slightly chunkier.
        double thickness = maxRadius / 12.0 * ringThicknessKeys;

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double d = ctx.grid().distanceFromCenter(key);
            // Keys within half a thickness either side of the current radius
            // are in the band. Everything else is omitted entirely — sparse
            // output, so the biome layer shows through around the ring
            // instead of being covered by a board of alpha-0 pixels.
            if (Math.abs(d - radius) <= thickness / 2.0) {
                out.put(key.ref(), new LayerPixel(ctx.baseColor(), brightness));
            }
        }
        return out;
    }

    /** Owning effect tracks the duration. See FlashOncePattern for the rant. */
    @Override
    public boolean isFinished(long elapsedMillis) {
        return false;
    }
}
