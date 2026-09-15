package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Dragon's breath: purple fire thrown at the board, spreading along the bottom
 * and licking upward.
 *
 * <p>Replaces a cone drawn out from the dragon's head, which was geometrically
 * honest and read as a triangle. What the attack actually does in game is put
 * a pool of burning purple on the floor — so the board gets the floor, and the
 * fire runs along it.
 *
 * <h2>What makes it fire and not a purple bar</h2>
 * <ul>
 *   <li><b>It spreads.</b> The burn starts under the dragon and runs outward
 *       in both directions, so the attack has a direction and an arrival
 *       rather than simply switching on.</li>
 *   <li><b>Per-column flame height.</b> Every column burns to its own height,
 *       wandering on its own clock, so the top edge is ragged. A flat top edge
 *       is the single thing that would give it away as a meter.</li>
 *   <li><b>It cools upward.</b> Pale at the base, deep purple at the tips,
 *       because that is the direction real flame loses energy — and it is what
 *       makes the ragged edge read as tongues rather than noise.</li>
 *   <li><b>It lingers.</b> Dragon's breath does not stop when the dragon does;
 *       the fire dies down over about a second after the attack ends.</li>
 * </ul>
 */
public final class DragonBreathFirePattern implements Pattern {

    /** Furthest the burn reaches from its origin, as a fraction of board width. */
    private static final double MAX_REACH = 0.85;
    /** Tallest a flame tongue gets, in board heights. */
    private static final double MAX_FLAME_HEIGHT = 0.62;

    private static final double SPREAD_PER_SECOND = 1.8;
    private static final double IGNITE_PER_SECOND = 3.2;
    private static final double DIE_DOWN_PER_SECOND = 0.85;

    private volatile double originX = 0.5;
    private volatile boolean burning = false;
    /** 0..1 cap on how far the burn runs, from the real cloud's radius. */
    private volatile double reachScale = 1.0;

    private double spread = 0;
    private double intensity = 0;
    private double lastElapsedMillis = 0;
    private boolean initialised = false;
    private Map<KeyGrid.LedRef, Double> columnPhase;

    /**
     * @param originX    where the breath lands, 0..1 across the board
     * @param reachScale how wide the pool gets, from the cloud's own radius, so
     *                   a fireball's small initial splash and a long-grown pool
     *                   do not look the same
     */
    public void setBurning(boolean burning, double originX, double reachScale) {
        this.burning = burning;
        this.originX = Math.max(0, Math.min(1, originX));
        this.reachScale = Math.max(0.15, Math.min(1.0, reachScale));
    }

    /** Still worth drawing? False once the fire has fully died down. */
    public boolean visible() {
        return burning || intensity > 0.01;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            columnPhase = new HashMap<>();
            for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
                // Phase from the key's x, so a whole column of keys shares a
                // flame rather than each key flickering independently — which
                // would be static, not fire.
                columnPhase.put(key.ref(), key.x() * 37.0);
            }
            lastElapsedMillis = elapsedMillis;
            initialised = true;
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;

        if (burning) {
            spread = Math.min(1.0, spread + dt * SPREAD_PER_SECOND);
            intensity = Math.min(1.0, intensity + dt * IGNITE_PER_SECOND);
        } else {
            intensity = Math.max(0.0, intensity - dt * DIE_DOWN_PER_SECOND);
            // The burn does not retreat as it dies, it just gets weaker — so
            // spread only resets once the fire is actually out.
            if (intensity <= 0.01) spread = 0;
        }
        if (intensity <= 0.01) return Map.of();

        double seconds = elapsedMillis / 1000.0;
        RGBColor cool = ctx.baseColor();
        RGBColor hot = ctx.resolvedAccentColor();
        double reach = Math.max(0.001, spread * MAX_REACH * reachScale);

        LightBudget budget = new LightBudget();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double fromOrigin = Math.abs(key.x() - originX);
            if (fromOrigin > reach) continue;

            // Thins toward the leading edge so the burn has a front rather
            // than a hard vertical cut.
            double edge = 1.0 - Math.pow(fromOrigin / reach, 2.2);

            double phase = columnPhase.getOrDefault(key.ref(), 0.0);
            double flicker = flame(seconds, phase);
            double height = MAX_FLAME_HEIGHT * intensity * edge * (0.55 + 0.45 * flicker);
            if (height <= 0.02) continue;

            // y is 0 at the top row and 1 at the bottom, so height above the
            // floor is 1 - y.
            double above = 1.0 - key.y();
            if (above > height) continue;

            double up = above / height;               // 0 at the base, 1 at the tip
            double brightness = (1.0 - Math.pow(up, 1.6)) * intensity;
            // Cools as it rises. This is what turns a ragged edge into tongues.
            RGBColor color = hot.lerp(cool, Math.min(1.0, up * 1.15));
            budget.add(key.ref(), color, brightness);
        }
        return budget.resolve();
    }

    /**
     * Three incommensurate sines, kept below about 6Hz so they still resolve
     * at the SDK's ~29fps instead of aliasing into a slow phantom beat.
     */
    private static double flame(double seconds, double phase) {
        double n = 0.55 * Math.sin(2 * Math.PI * 2.7 * seconds + phase)
                 + 0.30 * Math.sin(2 * Math.PI * 4.3 * seconds + phase * 1.7)
                 + 0.15 * Math.sin(2 * Math.PI * 6.1 * seconds + phase * 2.9);
        return 0.5 + 0.5 * n;
    }
}
