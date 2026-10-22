package com.everythingrgbprofile.priority;

import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;

import java.util.Map;

/**
 * The contract every lighting effect implements. If you're adding a new
 * effect, this is the interface you came here for.
 *
 * <h2>The one rule</h2>
 * <b>Every method in here runs on the SDK worker thread. Only the SDK worker
 * thread. No exceptions, no "just this once".</b>
 *
 * <p>Event handlers on the client thread do not call these. Not ever. They
 * call {@code SdkWorkerThread.enqueue(...)} with a tiny job that flips a
 * field, and the worker picks it up on its next pass. This is not ceremony:
 * the worker owns a native SDK connection, and calling into one of those from
 * two threads at once is how you earn a JVM crash with no stack trace at 2am.
 *
 * <p>Effects are cheap, long-lived singletons owned by {@code EffectManager}.
 * They get created once at startup and then sit there being inactive most of
 * the time, which costs one boolean check per frame. Do not allocate one per
 * event.
 */
public interface EffectController {

    /** Stable identifier, used in diagnostics so the logs can name names. */
    String id();

    /** Which compositing rules apply to this effect. See {@link EffectTier}. */
    EffectTier tier();

    /**
     * Higher wins, but only in the tiers where winning is a concept at all.
     *
     * <p>Means something in Tier 1 (exactly one base survives) and in Tier 3
     * (the louder interrupt preempts the quieter one). <b>Completely ignored
     * in Tier 2</b>, where every active overlay draws at the same time and
     * there is nothing to arbitrate. Setting a priority on a Tier 2 effect is
     * harmless and accomplishes absolutely nothing, so please don't lose an
     * afternoon tuning one.
     */
    default int priority() {
        return 0;
    }

    /**
     * A whole-layer opacity multiplier, applied on top of whatever alpha each
     * pixel already has. Handy for fading an entire effect in or out without
     * every single pattern needing to know how to do that itself.
     *
     * <p>Tier 2 overlays are the obvious customers, since that translucency is
     * the thing the tier was always supposed to have.
     *
     * <p>Tier 1 uses it for something completely different: <b>dissolving
     * between bases</b>. Tier 1 normally picks one winner and draws it over
     * black, so an effect that simply stops being active cuts straight to
     * whatever was underneath. Return less than 1.0 here and the compositor
     * draws the next base down first, so the fade lands on the biome instead
     * of on black. That's how a boss layer bows out gracefully when the boss
     * wanders off, rather than vanishing mid-fight like it was never there.
     *
     * @param nowMillis so the value can change over time, because a fade is a
     *                  function of the clock rather than a constant
     */
    default double layerOpacity(long nowMillis) {
        return 1.0;
    }

    /**
     * While this effect is active, Tier 3 flashes below this priority get
     * suppressed instead of blanking the board. Returning 0 (the default)
     * suppresses nothing and preserves original Tier 3 semantics exactly.
     *
     * <h2>Why this exists, a genuine debugging horror story</h2>
     * Tier 3 means "opaque full-board interrupt". Excellent default for a
     * 200ms lightning flash. Absolutely catastrophic against a sustained,
     * player-initiated effect that owns the board for several seconds.
     *
     * <p>The portal dwell is what exposed it. In a 600-mod pack the incidental
     * flashes (advancement, level up, sculk ping) go off constantly, and every
     * single one of them blanked the multi-second portal effect for its own
     * duration. From the outside this looked precisely like "the portal effect
     * is broken and never fires", so it got debugged as a timing problem for a
     * while, because that is exactly what it looked like.
     *
     * <p>It had been firing perfectly the entire time. It was just getting
     * repeatedly bulldozed by a confetti flash for picking up a diamond.
     *
     * <p>So now an effect that owns the board can say "hold my calls below
     * priority N". It is deliberately a threshold rather than a mute switch,
     * because death (100) has to ALWAYS get through. Nobody's immersion has
     * ever been improved by missing the part where they died.
     *
     * @param nowMillis so the floor can move over an effect's lifetime, e.g.
     *                  raised during the loud part and dropped during the tail
     */
    default int tier3SuppressionFloor(long nowMillis) {
        return 0;
    }

    /**
     * While this effect owns Tier 1, nothing in Tier 2 draws at all.
     *
     * <p>Almost nothing should return true here. The tiers exist so that
     * layers compose, and an effect switching the compositing off entirely is
     * declaring that the board has stopped showing the world and is now
     * showing one specific fact about your situation instead.
     *
     * <p>Death is that case. "Blood running down the keyboard until you
     * respawn" is not a base layer for rain and the moon to sit on top of. A
     * moon calmly tracking across a death screen is genuinely absurd. Winning
     * Tier 1 on priority alone cannot express this, because Tier 2 has no
     * priority contest at all: every active overlay draws regardless of what
     * the base is doing.
     *
     * <p>Gating each Tier 2 poll individually would work today and rot
     * tomorrow, because every Tier 2 effect written after this one would have
     * to remember to opt out of a state it has never heard of. One flag in the
     * compositor cannot be forgotten by someone who doesn't know it exists.
     */
    default boolean exclusive() {
        return false;
    }

    /**
     * Tier 2 only: is this atmosphere, or is it something you actually need to
     * know?
     *
     * <p>The test is whether ignoring it for a minute costs you anything. A
     * low-health pulse, a drowning meter, a portal charging up: all of that is
     * information you are actively making decisions on and none of it should
     * ever be hidden. The moon telling you how much night is left is lovely
     * and entirely skippable while something is trying to kill you.
     *
     * <p>Only matters at all when a Tier 1 base says
     * {@link #suppressesAmbientOverlays}. Otherwise every overlay draws as
     * normal.
     */
    default boolean ambient() {
        return false;
    }

    /**
     * Tier 1 only: while this base owns the board, ambient Tier 2 overlays
     * stand down.
     *
     * <p>This is for bases that are a whole picture in their own right (a
     * dragon, a serpent, a boss room) where a second unrelated thing drawn
     * over the top reads as a bug rather than as a layer. A moon calmly
     * tracking sunset across the Ender Dragon is the specific sight this
     * exists to prevent.
     *
     * <p>Deliberately narrower than {@link #exclusive()}, which stops Tier 2
     * dead in its tracks. Health and drowning warnings still draw during a
     * boss fight, because a boss fight is the single best time to be told
     * about them.
     */
    default boolean suppressesAmbientOverlays(long nowMillis) {
        return false;
    }

    /**
     * Is this effect currently doing anything at all? Called every frame for
     * every registered effect, so keep it to arithmetic on a couple of fields.
     * No allocation, no locking, and absolutely no I/O.
     */
    boolean isActive(long nowMillis);

    /**
     * This effect's contribution to the current frame. Return an empty map
     * when inactive; {@code Map.of()} allocates nothing at all.
     *
     * <p>Only include LEDs you actually want to touch. The compositor blends
     * whatever you return over what's underneath, and keys you leave out keep
     * the layer below, which is exactly how a Tier 2 overlay stays in its
     * lane.
     */
    Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis);
}
