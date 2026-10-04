package com.everythingrgbprofile.priority;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes every effect that wants a say and produces the one frame that
 * actually reaches the keyboard. This is the referee.
 *
 * <p>Order of operations, top to bottom: pick the Tier 1 base (usually one
 * layer, two while a base is dissolving into the one underneath it), blend
 * every active Tier 2 overlay on top, then let any qualifying Tier 3 flash
 * blank the entire thing and take the board.
 *
 * <h2>The bug that got this class rewritten</h2>
 * An earlier version did all three tiers with
 * {@code frame.putAll(effect.render(...))}, which is opaque replacement in a
 * compositing costume. It broke in two directions at once:
 *
 * <ul>
 *   <li>Tier 2 overlays clobbered each other. Whichever one happened to sit
 *       last in the list just won, so the order things got registered in was
 *       quietly deciding what you saw.</li>
 *   <li>The whole-board Rain Cascade is a Tier 2 overlay and therefore
 *       supposedly translucent, and it was not layering over the biome, it
 *       was <b>deleting</b> it. Rain in a jungle got you a keyboard of dim
 *       grey-blue with no jungle anywhere on it.</li>
 * </ul>
 *
 * <p>Now every layer hands back {@link LayerPixel}s carrying alpha and each
 * one is composited source-over the accumulated frame. At the time of the
 * rewrite Tier 1 and Tier 3 rendered fully opaque, so their output came out
 * byte-identical and nothing regressed on the way through. Most Tier 1
 * patterns have since started using alpha as brightness, which works because
 * they draw over the black prefill below. Tier 2 genuinely blends, so rain
 * over jungle reads as lightened green, which is what it had been claiming to
 * do the whole time.
 */
public final class Compositor {

    public static Map<KeyGrid.LedRef, RGBColor> composite(
            List<EffectController> tier1,
            List<EffectController> tier2,
            List<EffectController> tier3,
            KeyGrid grid,
            long nowMillis
    ) {
        Map<KeyGrid.LedRef, RGBColor> frame = new HashMap<>();
        if (grid.isEmpty()) return frame; // no keyboard, no frame, no problem

        // Prefill every key black instead of leaving the map sparse, for two
        // separate reasons that both matter.
        //
        // One: alpha-over-black works out arithmetically identical to the
        // brightness scaling the ambient layers used to do by hand, so moving
        // to real compositing changed exactly nothing about how they look.
        //
        // Two: blend() uses "is this key even in the map" as its test for
        // whether an LED is on the primary surface, and that only works if
        // the map is fully populated before anybody draws.
        for (KeyGrid.LedPosition p : grid.allKeys()) {
            frame.put(p.ref(), RGBColor.BLACK);
        }

        // --- Tier 1: exactly one survivor, except mid-dissolve -------
        EffectController base = highestPriorityActive(tier1, nowMillis);
        boolean exclusive = base != null && base.exclusive();
        if (base != null) {
            // A Tier 1 effect that is mid-fade needs something to fade INTO.
            // Drawn over the black prefill it dissolves to black and THEN cuts
            // to the biome, which is worse than not fading at all: you still
            // get the hard transition, you just get a dip to nothing before it
            // as a bonus.
            //
            // So when the winner isn't fully opaque, the next base down gets
            // drawn underneath it. Two layers for the length of the fade, one
            // layer the rest of the time.
            if (base.layerOpacity(nowMillis) < 1.0) {
                EffectController beneath = highestPriorityActiveExcluding(tier1, nowMillis, base);
                if (beneath != null) {
                    blend(frame, beneath, grid, nowMillis);
                }
            }
            blend(frame, base, grid, nowMillis);
        }

        // --- Tier 2: everybody draws, nobody argues -------------------
        // No priority sort, and that is the entire point of the tier. Each
        // overlay blends over whatever is already there, in registration
        // order, so they compose rather than compete.
        //
        // Unless the base has claimed exclusivity, in which case nothing
        // composes and the base simply IS the frame. See
        // EffectController#exclusive.
        if (!exclusive) {
            // A base that is a whole picture in its own right gets to ask the
            // atmospheric overlays to sit this one out. Warnings still draw;
            // see EffectController#ambient for exactly where that line is.
            boolean quietAmbient = base != null && base.suppressesAmbientOverlays(nowMillis);
            for (EffectController overlay : tier2) {
                if (!overlay.isActive(nowMillis)) continue;
                if (quietAmbient && overlay.ambient()) continue;
                blend(frame, overlay, grid, nowMillis);
            }
        }

        // --- Tier 3: the interrupt ------------------------------------
        // Tier 3 is BY DEFINITION an opaque full-board interrupt. It takes the
        // whole keyboard for its short duration no matter what Tiers 1 and 2
        // are up to, which means blanking the composited result before it
        // draws a single pixel.
        //
        // That blank is not optional and the reason is concrete: the portal's
        // old spiral-in arrival, back when it was a Tier 3 flash, was a
        // deliberately SPARSE pattern, a handful of comet keys with most of
        // the board sitting dark or very dim. Skip the blank and the Tier 1
        // biome layer keeps happily rendering underneath. What you get is a
        // full board of biome with a few spiral keys lost somewhere inside
        // it. Not a dimmer spiral. Not a spiral at all.
        //
        // Nothing needs restoring afterwards, before anybody panics. Tiers 1
        // and 2 get recomposited from scratch every single frame, so the
        // instant the flash reports inactive, the previous state is just there
        // again like nothing happened.

        // First though, ask the sustained layers whether any of them is
        // currently holding the board against interrupts. See
        // EffectController#tier3SuppressionFloor for the full story. Short
        // version: advancement popups were repeatedly wiping the portal effect
        // and making it look completely broken.
        //
        // MAX across everything active, so the most protective effect wins.
        // Tier 3 does not get a vote on this, because flashes suppressing
        // other flashes is a rabbit hole with no bottom.
        int floor = 0;
        if (exclusive) {
            // An exclusive base speaks for the entire board, including which
            // interrupts still get through it. Polling the Tier 2 layers here
            // would mean asking the opinion of effects that aren't even being
            // drawn right now.
            floor = base.tier3SuppressionFloor(nowMillis);
        } else {
            for (EffectController held : tier1) {
                if (held.isActive(nowMillis)) floor = Math.max(floor, held.tier3SuppressionFloor(nowMillis));
            }
            for (EffectController held : tier2) {
                if (held.isActive(nowMillis)) floor = Math.max(floor, held.tier3SuppressionFloor(nowMillis));
            }
        }

        // Anything at or above the floor still preempts completely normally,
        // which is how death (100) stays unmissable no matter what else is
        // going on.
        EffectController flash = highestPriorityActive(tier3, nowMillis, floor);
        if (flash != null) {
            for (KeyGrid.LedRef ref : frame.keySet()) {
                frame.put(ref, RGBColor.BLACK);
            }
            blend(frame, flash, grid, nowMillis);
        }

        return frame;
    }

    /**
     * Source-over composite of one effect's output onto the accumulated frame.
     * Two alphas get multiplied together in here: the per-pixel alpha the
     * pattern produced, and the effect's whole-layer opacity.
     */
    private static void blend(Map<KeyGrid.LedRef, RGBColor> frame, EffectController effect,
                              KeyGrid grid, long nowMillis) {
        double layerOpacity = effect.layerOpacity(nowMillis);
        // Layer is fully transparent? Skip the whole thing and don't even
        // call render(). Free frames for anything mid-fade-out.
        if (layerOpacity <= 0) return;
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> entry : effect.render(grid, nowMillis).entrySet()) {
            KeyGrid.LedRef ref = entry.getKey();
            RGBColor beneath = frame.get(ref);
            // Null means this LED isn't on the primary surface at all, e.g.
            // an effect aiming at a mousepad zone on a setup that hasn't got
            // a mousepad. Ignore it quietly instead of growing the frame with
            // LEDs the device is going to reject anyway.
            if (beneath == null) continue;
            frame.put(ref, entry.getValue().scaledAlpha(layerOpacity).over(beneath));
        }
    }

    /**
     * The base that would win if the current one weren't there. In other
     * words, whatever a dissolving effect is dissolving into.
     */
    private static EffectController highestPriorityActiveExcluding(
            List<EffectController> candidates, long nowMillis, EffectController exclude) {
        EffectController best = null;
        for (EffectController candidate : candidates) {
            if (candidate == exclude || !candidate.isActive(nowMillis)) continue;
            if (best == null || candidate.priority() > best.priority()) {
                best = candidate;
            }
        }
        return best;
    }

    /** Convenience overload: no suppression floor, everybody's eligible. */
    private static EffectController highestPriorityActive(List<EffectController> candidates, long nowMillis) {
        return highestPriorityActive(candidates, nowMillis, Integer.MIN_VALUE);
    }

    /**
     * Highest-priority active candidate at or above {@code floor}, or null.
     *
     * <p>A linear scan, on purpose. The candidate lists are about a dozen
     * entries each and this runs once per tier per frame, so sorting them or
     * maintaining a priority queue would be more code, more allocation and
     * measurably slower at this size. Strict {@code >} in the comparison means
     * ties go to whoever registered first, which is arbitrary but at least
     * it's consistently arbitrary.
     */
    private static EffectController highestPriorityActive(List<EffectController> candidates, long nowMillis, int floor) {
        EffectController best = null;
        for (EffectController candidate : candidates) {
            if (!candidate.isActive(nowMillis)) continue;
            if (candidate.priority() < floor) continue;
            if (best == null || candidate.priority() > best.priority()) {
                best = candidate;
            }
        }
        return best;
    }

    /** Static utility. There is no state here to construct. */
    private Compositor() {
    }
}
