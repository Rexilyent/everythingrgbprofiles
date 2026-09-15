package com.everythingrgbprofile.pattern.patterns;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * {@code sweep}: a lit head travelling along an ordered run of keys with a
 * comet tail behind it. Written for the Night Indicator, where it rode the
 * physical top row and turned the F-key row into a progress bar for how much
 * night was left.
 *
 * <h2>The one weird thing: progress is pushed in, not computed</h2>
 * Every other pattern derives its animation from {@code elapsedMillis}. This
 * one ignores it completely and reads a {@code progress} field that
 * {@code NightIndicatorEffect} sets from outside.
 *
 * <p>That's because the quantity being drawn isn't "how long has this
 * animation been running", it's "how far through the night is the world" —
 * external game state. Deriving it from elapsed time would mean the bar
 * desyncs from the actual sky the moment anything sleeps, lags, or changes
 * the day length, and you'd get a progress bar reporting a time
 * of day that isn't happening.
 *
 * <p>The field is {@code volatile} because it's written from the client
 * thread's polling and read on the SDK worker thread. That's the decoupling
 * the whole mod is built on: state updates arrive at whatever cadence the game
 * provides, rendering runs at its own frame rate, neither blocks the other.
 *
 * <h2>Currently unused</h2>
 * The night indicator was its only caller, and that now
 * draws a moon on an arc instead of sweeping a bar along a row —
 * see {@link MoonArcPattern}.
 *
 * <p>Kept rather than deleted because "fill a row up to a pushed-in progress
 * value" is a generic thing worth having on the shelf, and nothing else in the
 * codebase does it. Note it is not reachable through {@code PatternFactory}: a
 * profile cannot name it, because it renders from a value someone has to push
 * in and a biome profile has nothing to push.
 */
public final class SweepPattern implements Pattern {

    /**
     * The comet tail: head at full, then 60%, 30%, 10%.
     *
     * <p>Roughly halving each step, which is what makes it read as a trail
     * rather than four separate lit keys. Four entries is the sweet spot — two
     * looks like a rendering glitch, eight eats a third of the row and stops
     * reading as motion.
     */
    private static final double[] TRAIL_BRIGHTNESS = {1.0, 0.6, 0.3, 0.10};

    /** Written from the client thread, read on the worker thread. Hence volatile. */
    private volatile double progress = 0.0;

    public void setProgress(double progress) {
        this.progress = Math.max(0.0, Math.min(1.0, progress));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        List<KeyGrid.LedRef> order = ctx.grid().topRow();
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        // Some devices have no identifiable top row (a mousepad, say). Nothing
        // to sweep along, so contribute nothing and let the layer below show.
        if (order.isEmpty()) return out;

        int headIndex = (int) Math.round(progress * (order.size() - 1));
        for (int i = 0; i < TRAIL_BRIGHTNESS.length; i++) {
            int idx = headIndex - i; // tail trails BEHIND the head, hence minus
            // Deliberately no wraparound: at the start of the night the tail
            // should hang off the left edge, not teleport to the right and
            // imply the sweep is nearly done. Clip it.
            if (idx < 0 || idx >= order.size()) continue;
            out.put(order.get(idx), new LayerPixel(ctx.baseColor(), TRAIL_BRIGHTNESS[i]));
        }
        return out;
    }
}
