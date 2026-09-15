package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code fade}: the whole zone smoothly ramps up or down. The simplest
 * pattern in the mod, and quietly one of the most important, because
 * {@link #easeInOutCubic} is the shared easing curve half the codebase
 * borrows — spiral-in's radius easing and the Tier 1 biome-border crossfades
 * both call straight into it.
 *
 * <p>Used directly by Sleep/Wake, which wants a calm fade and nothing else.
 *
 * <h2>Why eased and not linear</h2>
 * A linear brightness ramp reads as mechanical — your eye catches the exact
 * moment it starts and stops, because the velocity jumps from 0 to constant
 * instantly. Ease-in-out cubic starts and ends at zero velocity, so the fade
 * appears to "arrive" rather than "cut". Same duration, completely different
 * feel, one line of maths.
 */
public final class FadePattern implements Pattern {

    public enum Direction { IN, OUT }

    private final Direction direction;

    public FadePattern(Direction direction) {
        this.direction = direction;
    }

    /**
     * Ease-in-out cubic, the standard formulation. Accelerates out of 0,
     * decelerates into 1, symmetric around the midpoint.
     *
     * <p>Public and static because everything else eases with it too. If you
     * ever change this curve, you are changing the feel of the biome
     * crossfades and the portal spiral at the same time. Choose violence
     * knowingly.
     */
    public static double easeInOutCubic(double p) {
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double duration = Math.max(1, ctx.durationMillis()); // max(1,...) so a 0 duration divides instead of exploding
        double p = Math.min(1.0, elapsedMillis / duration);
        double eased = easeInOutCubic(p);
        double brightness = direction == Direction.IN ? eased : (1.0 - eased);

        // One LayerPixel, reused for every key. It's an immutable record and
        // every key gets the identical value, so allocating N of them would be
        // pure waste.
        LayerPixel pixel = new LayerPixel(ctx.baseColor(), brightness);
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedRef key : ctx.effectiveTargetKeys()) {
            out.put(key, pixel);
        }
        return out;
    }
}
