package com.everythingrgbprofile.priority;

import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;

import java.util.Map;

/**
 * The contract every lighting effect implements. If you're adding a new
 * effect, this is the interface you're here for.
 *
 * <h2>The one rule</h2>
 * <b>Every method here runs on the SDK worker thread. Only the SDK worker
 * thread. No exceptions.</b>
 *
 * <p>Event handlers on the client thread do not call these. Ever. They call
 * {@code SdkWorkerThread.enqueue(...)} with a tiny job that flips a field, and
 * the worker picks it up on its next pass. This isn't ceremony — the
 * worker owns a native SDK connection, and calling into that from two threads
 * is how you get a JVM crash with no stack trace at 2am.
 *
 * <p>Effects themselves are cheap, long-lived singletons owned by
 * {@code EffectManager}. They're created once at startup and then just sit
 * there being inactive most of the time, which costs one boolean check per
 * frame. Don't allocate one per event.
 */
public interface EffectController {

    /** Stable identifier, used in diagnostics so logs name names. */
    String id();

    /** Which compositing rules apply to this effect. See {@link EffectTier}. */
    EffectTier tier();

    /**
     * Higher wins — but only where winning is a concept.
     *
     * <p>Meaningful in Tier 1 (exactly one base survives) and Tier 3 (louder
     * interrupt preempts quieter one). <b>Completely ignored in Tier 2</b>,
     * where every active overlay draws simultaneously and there's nothing to
     * arbitrate. Setting a priority on a Tier 2 effect is harmless and does
     * absolutely nothing, so don't spend an afternoon tuning one.
     */
    default int priority() {
        return 0;
    }

    /**
     * A whole-layer opacity multiplier, applied on top of each pixel's own
     * alpha. Handy for fading an entire effect in or out without every pattern
     * needing to know how.
     *
     * <p>Tier 2 overlays are the obvious users — that is the translucency the
     * tier was always meant to have.
     *
     * <p>Tier 1 uses it for something else: <b>dissolving between bases</b>.
     * Tier 1 normally picks one winner and draws it over black, so an effect
     * that simply stops being active cuts straight to whatever is underneath.
     * Return less than 1.0 here and the compositor draws the next base down
     * first, so the fade lands on the biome rather than on black. That is how
     * a boss layer bows out gracefully when the boss wanders off instead of
     * snapping away mid-fight.
     *
     * @param nowMillis so the value can vary over time — a fade is a function
     *                  of the clock, not a constant
     */
    default double layerOpacity(long nowMillis) {
        return 1.0;
    }

    /**
     * While this effect is active, Tier 3 flashes below this priority get
     * suppressed instead of blanking the board. Returning 0 (the default)
     * suppresses nothing and preserves original Tier 3 semantics exactly.
     *
     * <h2>Why this exists — a genuine debugging horror story</h2>
     * Tier 3 is "opaque full-board interrupt". Great default for a 200ms
     * lightning flash. Catastrophic against a sustained, player-initiated
     * effect that owns the board for several seconds.
     *
     * <p>The portal dwell was the case that exposed it. In a 600-mod pack the
     * incidental flashes — advancement, level up, sculk ping
     * — fire constantly. Every single one of them blanked the multi-second
     * portal effect for its own duration. From the outside this looked exactly
     * like "the portal effect is broken and never fires", and it got debugged
     * as a timing problem for a while, because that's what it looked like.
     *
     * <p>It was firing perfectly. It was just being repeatedly bulldozed by a
     * confetti flash for picking up a diamond.
     *
     * <p>So: an effect that owns the board can now say "hold my calls below
     * priority N". The floor is deliberately a threshold and not a mute
     * switch, because death (100) must ALWAYS get through. Nobody's immersion is improved by missing the fact that they
     * died.
     *
     * @param nowMillis so the floor can vary over an effect's lifetime — e.g.
     *                  raised during the loud part, dropped during the tail.
     */
    default int tier3SuppressionFloor(long nowMillis) {
        return 0;
    }

    /**
     * While this effect owns Tier 1, nothing in Tier 2 draws at all.
     *
     * <p>Almost nothing should return true. The tiers exist precisely so that
     * layers compose, and an effect that switches the compositing off is
     * declaring that the board is no longer showing the world — it is showing
     * one thing about your situation instead.
     *
     * <p>Death is that case. "Blood running down the keyboard until you
     * respawn" is not a base layer for rain and the moon to sit on top of; a
     * moon calmly tracking across a death screen is absurd.
     * Winning Tier 1 on priority alone is not enough to express that, because
     * Tier 2 has no priority contest — every active overlay draws regardless
     * of what the base is doing.
     *
     * <p>Gating the individual polls instead would work today and rot
     * tomorrow: every Tier 2 effect added later would have to remember to opt
     * out of a state it has never heard of. One flag in the compositor cannot
     * be forgotten.
     */
    default boolean exclusive() {
        return false;
    }

    /**
     * Tier 2 only: is this atmosphere rather than something you need to know?
     *
     * <p>The distinction is whether ignoring it for a minute costs you
     * anything. A low-health pulse, a drowning meter, a portal charging — all
     * information you are acting on, and none of it should ever be hidden. The
     * moon telling you how much night is left is lovely and completely
     * skippable while something is trying to kill you.
     *
     * <p>Only matters when a Tier 1 base says
     * {@link #suppressesAmbientOverlays}; otherwise every overlay draws as
     * usual.
     */
    default boolean ambient() {
        return false;
    }

    /**
     * Tier 1 only: while this base owns the board, ambient Tier 2 overlays
     * stand down.
     *
     * <p>For bases that are themselves a whole picture — a dragon, a serpent,
     * a boss — where a second unrelated thing drawn over the top reads as a
     * bug rather than as a layer. A moon calmly tracking sunset across the
     * Ender Dragon is the case this exists for.
     *
     * <p>Deliberately narrower than {@link #exclusive()}, which stops Tier 2
     * dead. Health and drowning warnings still draw during a boss fight,
     * because a boss fight is exactly when you want them.
     */
    default boolean suppressesAmbientOverlays(long nowMillis) {
        return false;
    }

    /**
     * Is this effect currently doing anything? Called every frame for every
     * registered effect, so keep it to arithmetic on a couple of fields — no
     * allocation, no locking, no I/O.
     */
    boolean isActive(long nowMillis);

    /**
     * This effect's contribution to the current frame. Return an empty map
     * when inactive — {@code Map.of()} allocates nothing.
     *
     * <p>Only include LEDs you actually want to affect. The compositor blends
     * what you return over whatever's underneath; keys you omit keep the layer
     * below, which is exactly how a Tier 2 overlay stays in its lane.
     */
    Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis);
}
