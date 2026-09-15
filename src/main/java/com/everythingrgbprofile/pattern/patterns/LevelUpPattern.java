package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.List;
import java.util.Map;

/**
 * Levelling up, as a short animation rather than a flash: the experience bar
 * fills along the bottom of the board, and when it is full it bursts upward in
 * gold and leaves the board sparkling.
 *
 * <p>Three beats, each borrowed from what the game shows at the same moment:
 *
 * <ol>
 *   <li><b>The bar fills.</b> The bottom row sweeps left to right in the
 *       experience bar's green, with a bright leading edge, while a handful of
 *       orbs drop in from above and are swallowed at that edge — the orbs
 *       you picked up, arriving.</li>
 *   <li><b>Level up.</b> The full bar flashes gold and a gold wave climbs from
 *       it to the top of the board.</li>
 *   <li><b>Sparkles.</b> Keys twinkle gold and white across the board while
 *       everything fades out.</li>
 * </ol>
 *
 * <p>A flash replaces the whole board while it runs, so what is not lit here
 * is dark. A faint gold glow under the burst keeps the middle of the animation
 * from being a few lit shapes on black.
 *
 * <p>Base is the gold of the burst and the sparkles; accent is the bar.
 * Everything is a pure function of elapsed time and the key it is drawing, so
 * the pattern holds no state and one instance can be triggered any number of
 * times.
 */
public final class LevelUpPattern implements Pattern {

    /** How long the whole animation runs. The flash effect is triggered with this. */
    public static final long DURATION_MILLIS = 1600;

    /** Share of the animation spent filling the bar; the burst starts when it ends. */
    private static final double FILL_END = 0.38;
    /** Share it takes the wave to climb from the bar to the top of the board. */
    private static final double WAVE_LENGTH = 0.3;
    private static final double WAVE_HALF_THICKNESS = 0.22;
    /** How far up the board the bar reaches, as the top of the band in board heights. */
    private static final double BAR_TOP = 0.78;

    private static final int ORBS = 7;
    private static final double ORB_FLIGHT = 0.2;
    private static final double ORB_RADIUS_KEYS = 1.0;

    private static final int SPARKLES = 16;
    /** Share of the animation each sparkle lasts. */
    private static final double SPARKLE_LIFE = 0.16;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        double duration = Math.max(1, ctx.durationMillis() > 0 ? ctx.durationMillis() : DURATION_MILLIS);
        double t = Math.max(0, Math.min(1, elapsedMillis / duration));
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyX = Math.max(1e-4, grid.keyWidthNormalised());
        double keyY = keyX / aspect;

        RGBColor gold = ctx.baseColor();
        RGBColor green = ctx.resolvedAccentColor();
        LightBudget budget = new LightBudget();

        // --- the bar -------------------------------------------------------
        // Eased so it accelerates into full, which is what makes the burst
        // land as the payoff of the fill rather than as a separate event.
        double fillT = Math.min(1, t / FILL_END);
        double fill = fillT * fillT * (3 - 2 * fillT);
        // After the burst the bar turns gold with it and then fades.
        double afterBurst = t < FILL_END ? 0 : (t - FILL_END) / (1 - FILL_END);
        double barLevel = t < FILL_END ? 1 : Math.max(0, 1 - afterBurst * 2.2);
        RGBColor barColor = t < FILL_END ? green : green.lerp(gold, Math.min(1, afterBurst * 6));
        double burstFlash = t < FILL_END ? 0 : Math.max(0, 1 - afterBurst * 7);

        for (KeyGrid.LedPosition key : keys) {
            if (key.y() < BAR_TOP) continue;
            if (key.x() <= fill) {
                budget.add(key.ref(), barColor, 0.9 * barLevel);
                if (burstFlash > 0) budget.add(key.ref(), RGBColor.WHITE, burstFlash * 0.8);
            }
            // The leading edge, bright while the bar is still moving.
            double edge = Math.abs(key.x() - fill) / keyX;
            if (t < FILL_END && edge < 1.2) {
                budget.add(key.ref(), green.lightened(0.5), (1 - edge / 1.2) * 1.2);
            }
        }

        // --- orbs ----------------------------------------------------------
        // Each drops from its own spot above toward wherever the bar's edge
        // will be when it arrives, so they land on the fill rather than beside
        // it.
        for (int i = 0; i < ORBS; i++) {
            double start = FILL_END * 0.75 * i / ORBS + hash(i, 3) * 0.04;
            double flight = (t - start) / ORB_FLIGHT;
            if (flight <= 0 || flight >= 1) continue;
            double arrival = Math.min(1, (start + ORB_FLIGHT) / FILL_END);
            double arrivalFill = arrival * arrival * (3 - 2 * arrival);
            double fromX = 0.1 + 0.8 * hash(i, 1);
            double fromY = 0.05 + 0.3 * hash(i, 2);
            double toY = (BAR_TOP + 1) / 2;
            double ease = flight * flight;
            double ox = fromX + (arrivalFill - fromX) * ease;
            double oy = fromY + (toY - fromY) * ease;
            double twinkle = 0.8 + 0.2 * Math.sin(elapsedMillis / 40.0 + i);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - ox) / keyX, (key.y() - oy) / keyY);
                if (e >= ORB_RADIUS_KEYS) continue;
                // A rounder falloff, so an orb between two keys still lights
                // both of them rather than two dim keys that read as nothing.
                double f = Math.sqrt(1 - e / ORB_RADIUS_KEYS);
                budget.add(key.ref(), green.lerp(gold, 0.35), f * 1.8 * twinkle);
            }
        }

        if (t >= FILL_END) {
            // --- the wave --------------------------------------------------
            // Climbs from the bar to past the top of the board, fading as it
            // goes.
            double waveT = (t - FILL_END) / WAVE_LENGTH;
            if (waveT < 1.2) {
                double front = BAR_TOP - waveT * (BAR_TOP + WAVE_HALF_THICKNESS);
                double strength = Math.max(0, 1 - waveT / 1.2);
                for (KeyGrid.LedPosition key : keys) {
                    double off = Math.abs(key.y() - front);
                    if (off >= WAVE_HALF_THICKNESS) continue;
                    double f = 1 - off / WAVE_HALF_THICKNESS;
                    budget.add(key.ref(), gold, f * f * 1.4 * strength);
                    // A hot white core to the wave for its first half.
                    if (waveT < 0.5) budget.add(key.ref(), RGBColor.WHITE, f * f * f * 0.6 * (1 - waveT * 2));
                }
            }

            // --- the glow --------------------------------------------------
            double glow = Math.max(0, 1 - afterBurst * 1.4);
            for (KeyGrid.LedPosition key : keys) {
                budget.add(key.ref(), gold, 0.12 * glow);
            }

            // --- sparkles --------------------------------------------------
            // Scattered over the whole board and over most of what is left of
            // the animation, each one a quick rise and a slower fade.
            for (int i = 0; i < SPARKLES; i++) {
                double born = FILL_END + 0.05 + (0.95 - FILL_END - SPARKLE_LIFE) * hash(i, 5);
                double age = (t - born) / SPARKLE_LIFE;
                if (age <= 0 || age >= 1) continue;
                double level = age < 0.25 ? age / 0.25 : 1 - (age - 0.25) / 0.75;
                KeyGrid.LedPosition key = keys.get((int) (hash(i, 7) * keys.size()) % keys.size());
                RGBColor color = hash(i, 9) < 0.35 ? RGBColor.WHITE : gold.lightened(0.3);
                budget.add(key.ref(), color, level * 1.6);
            }
        }

        // The last tenth fades everything out together, so the board comes back
        // from a dim glow rather than from whatever frame the clock stopped on.
        double tail = t > 0.9 ? Math.max(0, (1 - t) / 0.1) : 1;
        Map<KeyGrid.LedRef, LayerPixel> out = budget.resolve();
        if (tail >= 1) return out;
        out.replaceAll((ref, pixel) -> pixel.scaledAlpha(tail));
        return out;
    }

    /** A fixed pseudo-random number in [0, 1) for an index and a salt, so every trigger plays the same way. */
    private static double hash(int index, int salt) {
        long h = (index * 0x9E3779B97F4A7C15L) ^ (salt * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 31);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }
}
