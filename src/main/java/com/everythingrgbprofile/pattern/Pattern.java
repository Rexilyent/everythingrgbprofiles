package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.Map;

/**
 * One animated lighting pattern. The shared vocabulary the whole mod draws
 * from — shimmer, pulse, flash-once, sweep, spiral-in, ring-expand,
 * ring-contract, fade, drift-particle, twinkle-particle.
 *
 * <p>Patterns are the "how it moves" half of the system;
 * {@link com.everythingrgbprofile.priority.EffectController} is the "when and
 * why" half. Same spiral, different triggers.
 *
 * <h2>These are stateful. Do not share them.</h2>
 * One instance per active effect. Not static, not cached, not pooled, not
 * "reused because allocating is scary".
 *
 * <p>This matters concretely: shimmer rolls a per-key phase offset, and the
 * particle engines roll spawn positions and velocities. Those get rolled
 * <b>once, at pattern start</b>, and then persist frame to frame.
 * Re-randomising per frame doesn't give you a nicer shimmer, it gives you
 * white noise — every key picking a fresh random brightness 60 times a second
 * is, definitionally, static. Sharing one instance between two effects makes
 * them fight over that state and produces the same mess more slowly.
 *
 * <p>Everything here runs on the SDK worker thread, at
 * {@code animationFrameRateHz}, completely decoupled from how often the game
 * state is polled. The animation does not stutter because the
 * client is having a moment — which is exactly why the portal effect keeps
 * spinning smoothly through a multi-second chunk-loading freeze.
 */
public interface Pattern {

    /**
     * Renders this pattern's current frame.
     *
     * @param ctx           key grid, target keys, base/accent colour, params
     * @param elapsedMillis milliseconds since THIS INSTANCE started — not wall
     *                      clock, not game time. Patterns are pure functions of
     *                      this value plus their start-time state, which is what
     *                      makes them trivially restartable and testable.
     * @return a <b>sparse</b> map of LED to {@link LayerPixel}. Keys you leave
     *         out are untouched by this layer — that's the mechanism that lets a
     *         Tier 2 overlay light three keys while the biome base shows through
     *         everywhere else. Returning every key with alpha 0 "works" too but
     *         allocates a whole board's worth of objects to say nothing.
     */
    Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis);

    /**
     * Has this pattern run its course?
     *
     * <p>Only meaningful for one-shots (flash-once, ring-expand, fade).
     * Continuous patterns — shimmer, pulse, sweep — never finish on their own
     * and default to false forever; they get switched off from outside when
     * the underlying condition ends. A shimmer doesn't decide to stop being a
     * swamp; the swamp does.
     */
    default boolean isFinished(long elapsedMillis) {
        return false;
    }
}
