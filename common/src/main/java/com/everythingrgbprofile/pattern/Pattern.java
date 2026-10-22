package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.Map;

/**
 * One animated lighting pattern. It started as a small shared vocabulary
 * (shimmer, pulse, flash-once, sweep, spiral-in, ring-expand, ring-contract,
 * fade, drift-particle, twinkle-particle) and those are still here, alongside
 * the whole-scene patterns built since for biomes and bosses.
 *
 * <p>Patterns are the "how it moves" half of the system, and
 * {@link com.everythingrgbprofile.priority.EffectController} is the "when and
 * why" half. Same spiral, completely different triggers.
 *
 * <h2>Why this package exists at all, when a solid colour would have done</h2>
 * Because a solid colour across the whole board is what every RGB integration
 * already does, and it has never once been interesting to look at. "You are in
 * a swamp, therefore the keyboard is green" is a lookup table wearing a
 * costume. It carries exactly one bit of information and then sits there.
 *
 * <p>A keyboard is not a lamp. It is a hundred-odd individually addressable
 * pixels arranged in a known physical layout, with real width, real height and
 * a real aspect ratio, and every one of those properties is thrown in the bin
 * the moment you decide the answer is "make it all one colour".
 *
 * <p>So everything in this package is <b>geometry</b> rather than colour
 * selection. Rain falls downward because down is a direction that exists on
 * your desk. The dragon has a wingspan because the board is wider than it is
 * tall. The moon climbs out of the left edge and sets on the right because
 * that is where it goes. The portal's pillar field is anchored on the T key
 * because that is where the reference animation put it, measured rather than
 * guessed.
 *
 * <p>None of that is harder than a solid fill by very much. It is just a
 * decision somebody has to actually make, and this package is the collected
 * result of making it about forty times.
 *
 * <h2>These are stateful. Do not share them.</h2>
 * One instance per active effect. Not static, not cached, not pooled, not
 * "reused because allocating is scary".
 *
 * <p>This matters concretely rather than theoretically. Shimmer rolls a
 * per-key phase offset, and the particle engines roll spawn positions and
 * velocities. All of that gets rolled <b>once, at pattern start</b>, and then
 * persists frame to frame.
 *
 * <p>Re-randomising per frame does not give you a nicer shimmer. It gives you
 * white noise, because every key picking a fresh random brightness sixty times
 * a second is, definitionally, static. Sharing one instance between two
 * effects makes them fight over that state and arrives at the same mess more
 * slowly.
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
     * @return a <b>sparse</b> map of LED to {@link LayerPixel}. Keys left out
     *         are untouched by this layer, and that is the entire mechanism
     *         letting a Tier 2 overlay light three keys while the biome base
     *         shows through everywhere else. Returning every key with alpha 0
     *         also "works", and allocates a whole board's worth of objects in
     *         order to say nothing at all.
     */
    Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis);

    /**
     * Has this pattern run its course?
     *
     * <p>Nothing calls this today: every effect decides for itself when its
     * pattern stops (a flash by its duration, a sustained overlay when it is
     * switched off). It would only ever mean anything for one-shots like
     * flash-once, ring-expand and fade. The continuous patterns (shimmer,
     * pulse, sweep) never finish on their own and default to false forever,
     * because they get switched off from outside when the underlying condition
     * ends.
     *
     * <p>A shimmer does not decide to stop being a swamp. The swamp does that.
     */
    default boolean isFinished(long elapsedMillis) {
        return false;
    }
}
