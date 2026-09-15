package com.everythingrgbprofile.priority;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes every effect that wants a say and produces the one frame that
 * actually goes to the keyboard. This is the referee.
 *
 * <p>Order of operations, top to bottom: resolve Tier 1 down to a single
 * winner, blend every active Tier 2 overlay on top of it, then let any
 * qualifying Tier 3 flash blank the whole thing and take over.
 *
 * <h2>The bug this class was rewritten to fix</h2>
 * An earlier version handled all three tiers with
 * {@code frame.putAll(effect.render(...))}. That is opaque replacement wearing
 * a compositing costume, and it broke in two ways at once:
 *
 * <ul>
 *   <li>Tier 2 overlays clobbered each other. Whichever one happened to be
 *       last in the list won outright, so registration order silently decided
 *       what you saw.</li>
 *   <li>The whole-board Rain Cascade — a Tier 2 overlay, and therefore
 *       supposedly translucent — didn't layer over the biome, it
 *       <b>deleted</b> it. Raining in a jungle gave you a keyboard of dim
 *       grey-blue with no jungle anywhere in it.</li>
 * </ul>
 *
 * <p>Now every layer hands back {@link LayerPixel}s carrying alpha, and each
 * is composited source-over the accumulated frame. Tier 1 and Tier 3 render at
 * full opacity so their output is byte-identical to the old behaviour —
 * nothing regressed. Tier 2 actually blends, so rain over jungle now reads as
 * lightened green, which is what it always said on the tin.
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

        // Prefill every key black rather than leaving the map sparse.
        //
        // Two reasons. One: alpha-over-black is arithmetically identical to
        // the brightness scaling the ambient layers used to do, so this
        // conversion to real compositing changed nothing about how they look.
        // Two: blend() uses "is this key in the map?" as its test for whether
        // an LED is on the primary surface at all, which only works if the
        // map is fully populated up front.
        for (KeyGrid.LedPosition p : grid.allKeys()) {
            frame.put(p.ref(), RGBColor.BLACK);
        }

        // --- Tier 1: exactly one survivor, except while one is dissolving
        EffectController base = highestPriorityActive(tier1, nowMillis);
        boolean exclusive = base != null && base.exclusive();
        if (base != null) {
            // A Tier 1 effect that is mid-fade needs something to fade INTO.
            // Drawn over the black prefill it would dissolve to black and then
            // cut to the biome, which is worse than not fading at all — you
            // get the hard transition anyway, with a dip to nothing first.
            //
            // So when the winner is not fully opaque, the next base down is
            // drawn underneath it. Two layers for the length of the fade, one
            // the rest of the time.
            if (base.layerOpacity(nowMillis) < 1.0) {
                EffectController beneath = highestPriorityActiveExcluding(tier1, nowMillis, base);
                if (beneath != null) {
                    blend(frame, beneath, grid, nowMillis);
                }
            }
            blend(frame, base, grid, nowMillis);
        }

        // --- Tier 2: everyone draws, nobody argues --------------------
        // No priority sort. That's the point of the tier: each overlay is
        // confined to its own keys, so they compose instead of competing.
        //
        // Unless the base claimed exclusivity, in which case nothing composes
        // and the base is the frame. See EffectController#exclusive.
        if (!exclusive) {
            // A base that is a whole picture in its own right can ask the
            // atmospheric overlays to sit this one out. Warnings still draw —
            // see EffectController#ambient for where the line is.
            boolean quietAmbient = base != null && base.suppressesAmbientOverlays(nowMillis);
            for (EffectController overlay : tier2) {
                if (!overlay.isActive(nowMillis)) continue;
                if (quietAmbient && overlay.ambient()) continue;
                blend(frame, overlay, grid, nowMillis);
            }
        }

        // --- Tier 3: the interrupt ------------------------------------
        // Tier 3 is defined as an opaque, full-board interrupt: it takes the
        // keyboard entirely for its short duration regardless of what is
        // active in Tiers 1-2, which means blanking the composited result
        // before it draws.
        //
        // The blank is not optional, and here's the concrete reason: spiral-in
        // is a deliberately SPARSE pattern — a handful of comet keys, with the
        // rest of the board staying dark or very dim during that phase.
        // Skip the blank and the Tier 1 biome shimmer keeps rendering
        // underneath it. What you get is a full board of shimmer noise with a
        // few spiral keys lost somewhere inside. Not a dimmer spiral. Not a
        // spiral at all.
        //
        // Nothing needs restoring afterwards, before anyone worries: Tiers 1-2
        // are recomposited from scratch every single frame, so the moment the
        // flash reports inactive the previous state is just... there again.

        // First though: ask the sustained layers whether they're currently
        // holding the board against interrupts. See
        // EffectController#tier3SuppressionFloor for why this exists (short
        // version: an advancement popup was repeatedly erasing the portal
        // effect and making it look broken).
        //
        // We take the MAX across everything active, so the most protective
        // effect wins. Tier 3 itself isn't consulted — a flash suppressing
        // other flashes is a rabbit hole with no bottom.
        int floor = 0;
        if (exclusive) {
            // An exclusive base speaks for the whole board, including which
            // interrupts still get through it. Polling the Tier 2 layers for
            // their opinion would be asking effects that are not being drawn.
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
        // which is how death (100) stays unmissable.
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
     * Two alphas multiply together here: the per-pixel alpha the pattern
     * produced, and the effect's whole-layer opacity.
     */
    private static void blend(Map<KeyGrid.LedRef, RGBColor> frame, EffectController effect,
                              KeyGrid grid, long nowMillis) {
        double layerOpacity = effect.layerOpacity(nowMillis);
        // Fully transparent layer? Skip it entirely and don't even call
        // render(). Free frames for effects mid-fade-out.
        if (layerOpacity <= 0) return;
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> entry : effect.render(grid, nowMillis).entrySet()) {
            KeyGrid.LedRef ref = entry.getKey();
            RGBColor beneath = frame.get(ref);
            // Null means this LED isn't on the primary surface (an effect
            // targeting a mousepad zone on a board that hasn't got one, say).
            // Silently ignore rather than growing the frame with LEDs the
            // device won't accept.
            if (beneath == null) continue;
            frame.put(ref, entry.getValue().scaledAlpha(layerOpacity).over(beneath));
        }
    }

    /**
     * The base that would win if the current one were not there — i.e. what a
     * dissolving effect is dissolving into.
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

    /** Convenience overload: no suppression floor, everyone's eligible. */
    private static EffectController highestPriorityActive(List<EffectController> candidates, long nowMillis) {
        return highestPriorityActive(candidates, nowMillis, Integer.MIN_VALUE);
    }

    /**
     * Highest-priority active candidate at or above {@code floor}, or null.
     *
     * <p>A linear scan, on purpose. The candidate lists are a couple of dozen
     * entries and this runs once per tier per frame — sorting or maintaining a
     * priority queue would be more code, more allocation, and measurably
     * slower at this size. Strict {@code >} on the comparison means ties go to
     * whoever was registered first, which is arbitrary but at least stable.
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

    /** Static utility; there is no state to construct. */
    private Compositor() {
    }
}
