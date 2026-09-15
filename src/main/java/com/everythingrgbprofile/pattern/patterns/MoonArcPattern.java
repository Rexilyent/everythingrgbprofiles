package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * A moon crossing the board, tracking how much night is left.
 *
 * <p>Rises out of the left edge, arcs across the top rows, and sets down the
 * right — the same shape Terraria's RGB uses for its night events, and the
 * same shape the actual sky does. Where it is IS the information: a moon still
 * climbing on the left means most of the night is ahead of you, a moon dropping
 * off the right edge means dawn.
 *
 * <h2>The arc is a real circle, not a guess</h2>
 * Traced from the reference capture frame by frame, the moon's path fits
 *
 * <pre>
 *   x(p) = (1 - cos(pi * p)) / 2
 *   y(p) = BASE - AMPLITUDE * sin(pi * p)
 * </pre>
 *
 * to within about 3% horizontally and 2% vertically across the whole run. That
 * is a circle seen edge-on, which is exactly what a body crossing the sky looks
 * like from the ground: near the horizon it climbs almost vertically while
 * barely moving sideways, and near the zenith it slides sideways while barely
 * changing height.
 *
 * <p>That non-linearity is the entire reason this does not just interpolate
 * left-to-right. A moon moving at constant horizontal speed reads as a
 * progress bar with a dot on it; a moon that lingers low at the edges and
 * hurries across the top reads as the sky.
 */
public final class MoonArcPattern implements Pattern {

    /** Height at moonrise and moonset, in board heights from the top. */
    private static final double ARC_BASE_Y = 0.52;
    /** How far above that it gets at the top of its arc. */
    private static final double ARC_AMPLITUDE = 0.33;

    /** Disc radius, in key widths. About three keys across. */
    private static final double MOON_RADIUS_KEYS = 1.6;
    /** Soft glow around it, so it sits in the sky rather than being pasted on. */
    private static final double HALO_RADIUS_KEYS = 3.4;

    private volatile double progress = 0;

    /** @param progress 0 = moonrise, 1 = moonset. */
    public void setProgress(double progress) {
        this.progress = Math.max(0, Math.min(1, progress));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        double p = progress;
        double moonX = (1 - Math.cos(Math.PI * p)) / 2.0;
        double moonY = ARC_BASE_Y - ARC_AMPLITUDE * Math.sin(Math.PI * p);

        // Aspect correction so the moon is round on the physical board rather
        // than a wide smear — normalised space is about 3:1, so a circle drawn
        // in it is a letterbox.
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        double moonRadius = MOON_RADIUS_KEYS * keyWidth;
        double haloRadius = HALO_RADIUS_KEYS * keyWidth;

        RGBColor glow = ctx.baseColor();
        RGBColor core = ctx.resolvedAccentColor();

        LightBudget budget = new LightBudget();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double dx = key.x() - moonX;
            double dy = (key.y() - moonY) * aspect;
            double d = Math.hypot(dx, dy);
            if (d > haloRadius) continue;

            if (d <= moonRadius) {
                // Nearly flat across the disc with a soft rim, so it reads as a
                // solid body and not as a blob of light.
                double edge = 1.0 - Math.pow(d / moonRadius, 3.0);
                budget.add(key.ref(), core, 0.55 + 0.45 * edge);
            } else {
                double f = 1.0 - (d - moonRadius) / (haloRadius - moonRadius);
                budget.add(key.ref(), glow, f * f * 0.35);
            }
        }
        return budget.resolve();
    }
}
