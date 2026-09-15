package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Water rising up the board, for the drowning alert. A Terraria breath meter
 * laid on its side and made out of keys.
 *
 * <p>The board floods from the bottom row upward as air runs out, and drains
 * back down when you surface. Keys above the waterline are left out of the
 * output entirely, so the biome underneath keeps showing through — the same
 * way rain leaves the board visible between drops.
 *
 * <h2>The waterline is the whole effect</h2>
 * A flat boundary between "blue" and "not blue" reads as a progress bar. What
 * makes it read as water is that the line is not flat: it carries a slow sine
 * along the board, and it catches the light — keys near the surface are
 * brighter and paler than the ones below them. Depth then does the rest, with
 * the blue growing denser further down until it hides the biome completely.
 *
 * <h2>Why the level is eased here rather than at the poll</h2>
 * Air supply is an integer that ticks down once per tick, so a poll can only
 * ever hand over steps. Easing the displayed level toward the target inside
 * the render loop means the water moves continuously at the SDK's frame rate
 * regardless of how coarsely it is told what the target is — and it means a
 * sudden jump (surfacing, or a chunk of air restored) reads as a surge rather
 * than a teleport.
 */
public final class WaterRisePattern implements Pattern {

    /** Roughly how long the surface takes to catch up to a new target. */
    private static final double EASE_TAU_SECONDS = 0.16;

    private static final double WAVE_AMPLITUDE = 0.035;
    private static final double WAVE_COUNT = 1.7;
    private static final double WAVE_SPEED_HZ = 0.42;

    /** How far below the line the water reaches full density, in board heights. */
    private static final double DEPTH_SCALE = 0.55;
    private static final double SURFACE_ALPHA = 0.45;
    private static final double DEEP_ALPHA = 0.94;
    /** Distance from the line that still counts as "the surface", for the highlight. */
    private static final double SURFACE_BAND = 0.09;

    private final double panicThreshold;

    private volatile double targetLevel = 0;
    private double displayedLevel = 0;
    private double lastElapsedMillis = 0;
    private boolean initialised = false;

    public WaterRisePattern(double panicThreshold) {
        this.panicThreshold = Math.max(0.0, Math.min(1.0, panicThreshold));
    }

    /** 0 = board dry, 1 = fully under. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /**
     * Whether there is still anything to draw. The owning effect asks this so
     * the water can finish draining after the player surfaces instead of
     * blinking out the moment air starts refilling.
     */
    public boolean visible() {
        return targetLevel > 0.001 || displayedLevel > 0.005;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        // Clock restart (a second drowning in one session) or first frame:
        // snap rather than easing up from wherever the last one ended.
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            displayedLevel = targetLevel;
            lastElapsedMillis = elapsedMillis;
            initialised = true;
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;

        // Exponential approach: frame-rate independent, and it decelerates into
        // the target the way a body of water settles.
        if (dt > 0) {
            displayedLevel += (targetLevel - displayedLevel) * (1 - Math.exp(-dt / EASE_TAU_SECONDS));
        }
        double level = displayedLevel;
        if (level <= 0.005) return Map.of();

        double seconds = elapsedMillis / 1000.0;
        RGBColor deep = ctx.baseColor();
        RGBColor surface = ctx.resolvedAccentColor();

        // The wave dies away at both ends of the range: an empty board has no
        // surface to ripple, and a fully submerged one has no surface at all.
        double waveFade = Math.min(1.0, level * 5) * Math.min(1.0, (1 - level) * 5);
        double amplitude = WAVE_AMPLITUDE * waveFade;

        // Panic: once air is nearly gone the whole body of water pulses. This
        // is the part that stops it reading as a tidy gauge and starts it
        // reading as a problem.
        double panic = panicThreshold >= 1.0 ? 0.0
                : Math.max(0.0, (level - panicThreshold) / (1.0 - panicThreshold));
        double panicPulse = panic <= 0 ? 1.0
                : 1.0 + panic * 0.28 * Math.sin(2 * Math.PI * 2.4 * seconds);

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            // y is 0 at the top row and 1 at the bottom, so the line sits at
            // 1 - level and the water is everything below it.
            double line = (1.0 - level)
                    + amplitude * Math.sin(2 * Math.PI * (key.x() * WAVE_COUNT + seconds * WAVE_SPEED_HZ));
            double depth = key.y() - line;
            if (depth < -SURFACE_BAND) continue; // dry, and the biome keeps this key

            if (depth < 0) {
                // Just above the line: the meniscus catching light. Fades out
                // over the band so there is no hard edge at the top of it.
                double t = 1.0 + depth / SURFACE_BAND;
                out.put(key.ref(), new LayerPixel(surface, clamp(t * SURFACE_ALPHA * 0.8 * panicPulse)));
                continue;
            }

            double sink = Math.min(1.0, depth / DEPTH_SCALE);
            RGBColor color = surface.lerp(deep, Math.min(1.0, depth / SURFACE_BAND));
            double alpha = SURFACE_ALPHA + (DEEP_ALPHA - SURFACE_ALPHA) * sink;
            out.put(key.ref(), new LayerPixel(color, clamp(alpha * panicPulse)));
        }
        return out;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
