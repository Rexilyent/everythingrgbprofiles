package com.everythingrgbprofile.pattern.patterns;

import java.util.HashMap;
import java.util.Map;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * {@code rainbow-wave}: the whole hue wheel scrolling left to right across the
 * board, forever. Yes, this is the exact effect every RGB keyboard ships with
 * out of the box. The {@link Pattern} class doc spends several paragraphs
 * being smug about not doing this. Anyway, here it is.
 *
 * <h2>Hidden</h2>
 * No default profile uses it, and the config comments don't mention it. It
 * runs when somebody types {@code "pattern": "rainbow-wave"} into a biome
 * profile, or sets {@code menuTheme.style} to {@code rainbow-wave}, and not
 * otherwise. See {@code PatternFactory.hiddenPattern} for the full list.
 *
 * <p>Ignores the profile's colours completely, because a rainbow with a base
 * colour is just a worse rainbow.
 */
public final class RainbowWavePattern implements Pattern {

    /** Trips round the hue wheel visible across the board at once. One means every colour is on screen exactly once. */
    private static final double WHEELS_ACROSS = 1.0;

    /** Seconds for a colour to travel the full width. Four is brisk without being a strobe. */
    private static final double SECONDS_PER_CROSSING = 4.0;

    /**
     * Hue offset from the top row to the bottom. Without it the bands are
     * dead-vertical columns, which reads as a scrolling bar chart rather than
     * a wave. A little lean and it suddenly has somewhere to be.
     */
    private static final double LEAN = 0.15;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        double drift = elapsedMillis / 1000.0 / SECONDS_PER_CROSSING;
        for (KeyGrid.LedPosition p : ctx.effectiveTargetPositions()) {
            // Minus the drift so a given hue moves toward larger x, i.e.
            // rightward. Plus would scroll it left, which is also fine, just
            // not the direction anyone's keyboard software defaults to.
            double hue = p.x() * WHEELS_ACROSS + p.y() * LEAN - drift;
            hue -= Math.floor(hue); // wrap into [0,1); HSBtoRGB would cope, but negative hues are a vibe nobody needs
            int argb = java.awt.Color.HSBtoRGB((float) hue, 1f, 1f);
            out.put(p.ref(), LayerPixel.opaque(
                    new RGBColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)));
        }
        return out;
    }
}
