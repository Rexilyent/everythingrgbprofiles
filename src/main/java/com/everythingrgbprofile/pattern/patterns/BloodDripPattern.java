package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * You died. Blood runs down the keyboard until you respawn.
 *
 * <p>Three layers, summed through a {@link LightBudget} so a drip crossing an
 * existing streak comes out brighter rather than replacing it:
 *
 * <ol>
 *   <li><b>The soak.</b> A dim, uneven red across the whole board. Without it
 *       the drips read as red rain on a dead keyboard; with it the board reads
 *       as something that has been bled on.</li>
 *   <li><b>Stains.</b> Every key a drip passes over holds colour and gives it
 *       up slowly. This is the layer that makes it blood — rain leaves nothing
 *       behind, blood leaves a streak.</li>
 *   <li><b>Drip heads.</b> The bright point, which hangs and swells at the top
 *       before it lets go.</li>
 * </ol>
 *
 * <h2>Why it hangs before it falls</h2>
 * A drop that simply starts at the top and falls is rain. Blood is viscous: it
 * gathers, swells, holds, and only then breaks loose and accelerates. That
 * hang-then-release is most of what sells it, and it costs one extra phase in
 * the position function.
 *
 * <h2>Keeping the middle of the range empty</h2>
 * The thing that makes a moving light legible on a keyboard is not how bright
 * it is, it is how little there is between it and the background. A board
 * reads as animated when its keys are mostly background or mostly element,
 * and reads as a coloured smear when they pile up in between — which is
 * exactly what happens if the trail creeps up toward head brightness.
 *
 * <p>So the three layers are deliberately kept in separate bands: the soak
 * sits near the bottom of the LED's range, the streaks in the lower third, and
 * the heads pinned at full output. Widening the gaps is almost always the
 * right fix when one of these effects is hard to see; making everything
 * brighter is almost always the wrong one, because it closes them.
 *
 * <h2>Why the fall accelerates then stops accelerating</h2>
 * Constant velocity reads as mechanical, and unbounded acceleration has the
 * drip crossing the last two rows in a single frame at 29fps. So it
 * accelerates to a terminal speed and then holds it, which is both what a real
 * drop does and what stays legible on a six-row board.
 */
public final class BloodDripPattern implements Pattern {

    /** One running drop. Recycled in place. */
    private static final class Drip {
        double x;
        double startSeconds;
        double hangSeconds;
        double terminalSpeed;
        /** Randomised per drip so they do not all swell to the same size. */
        double weight;
    }

    private static final double GRAVITY = 0.9;          // normalised y per second squared
    private static final double SPAWN_Y = -0.06;
    private static final double EXIT_Y = 1.08;

    /** Seconds for a stain to fall to half brightness. */
    private static final double STAIN_HALF_LIFE = 1.1;

    /**
     * How fast a drip stains the key it is sitting on, <b>per second</b>.
     *
     * <p>This used to be a flat 0.55 added once per frame, which was wrong in
     * two separate ways. It was frame-rate dependent — at 60fps a streak built
     * twice as fast as at 29 — and, worse, it saturated almost immediately: a
     * drip resting on a key for three frames deposited 1.4 and clamped to full.
     *
     * <p>The visible result was that the trail rendered at roughly 55% LED
     * drive against a head at 98%, so the bright drop you are supposed to be
     * following was barely brighter than the smear it had already left, and
     * with eight drips running the board filled with mid-red and stopped
     * reading as anything. Measured per second instead, a drop passing at
     * terminal speed leaves about 0.5, and only the slow gather at the top
     * builds a full-strength pool — which is the one place it should.
     */
    private static final double STAIN_PER_SECOND = 1.2;

    /**
     * How much of the blood colour a full stain contributes.
     *
     * <p>Deliberately well under the head. The trail exists to show where the
     * blood went, not to compete with the drop that is making it — see the
     * class notes on keeping the mid-range empty.
     */
    private static final double STAIN_STRENGTH = 0.35;

    /**
     * Radius of a drip head, in key widths, and how much taller than wide it is.
     *
     * <p>A head used to be exactly one LED, found with a nearest-key lookup.
     * One key is a hard thing to notice and an even harder thing to track
     * while it moves, especially against a coloured background — the reference
     * everyone reaches for here is Terraria's, and the reason that one reads
     * so clearly is that its drops are several cells of full-brightness red,
     * not single pixels. So this one is a small blob, stretched vertically
     * because that is the shape a falling drop is.
     *
     * <p>Sizes tuned by measurement, not by eye. Keys are very nearly square in
     * physical units, so one key-width of radius already reaches about one row
     * vertically — an earlier radius of 1.35 with a 1.7 stretch quietly produced a
     * blob five rows tall, which is a column, not a drop. These values put
     * roughly seven keys per frame above the "clearly lit" line, which is the
     * same order as the reference this was measured against.
     */
    private static final double HEAD_RADIUS_KEYS = 1.15;
    private static final double HEAD_STRETCH = 1.35;
    /**
     * Drives the head past full LED output so it pins at maximum whatever
     * blood colour is configured. Overshoot is safe: the light budget
     * normalises by the peak, so this saturates the key rather than shifting
     * its hue toward white.
     */
    private static final double HEAD_BOOST = 1.60;

    private final int dripCount;
    private final double dripIntervalMillis;
    private final Random random = new Random();

    private boolean initialized = false;
    private double lastElapsedMillis = 0;

    /** Board shape and head size, resolved once the real grid is known. */
    private double aspect = 1.0;
    private double headRadius = 0.05;

    private List<Drip> drips;
    /** Per-key stain, decayed every frame. Keyed by LED so it survives regardless of layout. */
    private Map<KeyGrid.LedRef, Double> stains;
    private Map<KeyGrid.LedRef, Double> soakPhase;

    public BloodDripPattern(int dripCount, double dripIntervalMillis) {
        this.dripCount = Math.max(1, dripCount);
        this.dripIntervalMillis = Math.max(80, dripIntervalMillis);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        initIfNeeded(ctx, elapsedMillis);

        // Same clock-restart guard as the other stateful engines: this effect's
        // elapsed time restarts at zero every time it is re-activated, while
        // the drips hold absolute schedules. Dying twice in one session would
        // otherwise leave every drip waiting out the previous death.
        if (elapsedMillis < lastElapsedMillis) {
            reseed(ctx, elapsedMillis);
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;

        double seconds = elapsedMillis / 1000.0;
        RGBColor soakColor = ctx.baseColor();
        RGBColor bloodColor = ctx.resolvedAccentColor();

        // Stains fade on a half-life, so the decay is frame-rate independent:
        // two frames at 15fps remove exactly as much as four at 30.
        if (dt > 0 && !stains.isEmpty()) {
            double keep = Math.pow(0.5, dt / STAIN_HALF_LIFE);
            stains.replaceAll((k, v) -> v * keep);
            stains.values().removeIf(v -> v < 0.01);
        }

        LightBudget budget = new LightBudget();

        // --- the soak ---------------------------------------------------
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double phase = soakPhase.getOrDefault(key.ref(), 0.0);
            // Slow and shallow. This is meant to feel wet, not to pulse.
            double breath = 0.78 + 0.22 * Math.sin(2 * Math.PI * 0.09 * seconds + phase);
            budget.add(key.ref(), soakColor, breath);
        }

        // --- drips, laying down stain as they go ------------------------
        for (int i = 0; i < drips.size(); i++) {
            Drip drip = drips.get(i);
            double age = seconds - drip.startSeconds;
            if (age < 0) continue;

            double y;
            double headBrightness;
            if (age < drip.hangSeconds) {
                // Gathering. It swells in place and gets brighter as it does.
                y = SPAWN_Y;
                headBrightness = drip.weight * (0.25 + 0.75 * (age / drip.hangSeconds));
            } else {
                double fall = age - drip.hangSeconds;
                double accelTime = drip.terminalSpeed / GRAVITY;
                if (fall < accelTime) {
                    y = SPAWN_Y + 0.5 * GRAVITY * fall * fall;
                } else {
                    y = SPAWN_Y + 0.5 * drip.terminalSpeed * accelTime
                            + drip.terminalSpeed * (fall - accelTime);
                }
                headBrightness = drip.weight;
            }

            if (y > EXIT_Y) {
                drips.set(i, newDrip(seconds + random.nextDouble() * (dripIntervalMillis / 1000.0)));
                continue;
            }

            // The head, as a small vertical blob rather than a single LED.
            for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
                double dx = key.x() - drip.x;
                double dy = (key.y() - y) * aspect / HEAD_STRETCH;
                double d = Math.hypot(dx, dy);
                if (d >= headRadius) continue;
                double falloff = 1.0 - d / headRadius;
                // Squared, so the blob has a hot centre and a quick edge
                // instead of a soft halo that would smear back into the mush
                // this whole change exists to remove.
                budget.add(key.ref(), bloodColor, headBrightness * falloff * falloff * HEAD_BOOST);
            }

            // Stain only the key the drop is actually on, and by elapsed time
            // rather than per frame. A drip that lingers — during the gather
            // at the top, or crossing a row boundary — leaves a heavier mark,
            // which is what pooling looks like; one falling at speed leaves a
            // streak well below head brightness.
            KeyGrid.LedPosition nearest = nearestKey(ctx.grid(), drip.x, y);
            if (nearest == null) continue;
            double deposit = headBrightness * STAIN_PER_SECOND * dt;
            stains.merge(nearest.ref(), deposit, (a, b) -> Math.min(1.0, a + b));
        }

        // --- stains, under the heads but over the soak ------------------
        for (Map.Entry<KeyGrid.LedRef, Double> entry : stains.entrySet()) {
            budget.add(entry.getKey(), bloodColor, entry.getValue() * STAIN_STRENGTH);
        }

        return budget.resolve();
    }

    private void initIfNeeded(PatternContext ctx, long elapsedMillis) {
        if (initialized) return;
        aspect = Math.max(0.05, ctx.grid().aspectRatio());
        headRadius = HEAD_RADIUS_KEYS * ctx.grid().keyWidthNormalised();
        stains = new HashMap<>();
        soakPhase = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            soakPhase.put(key.ref(), random.nextDouble() * 2 * Math.PI);
        }
        drips = new ArrayList<>(dripCount);
        for (int i = 0; i < dripCount; i++) drips.add(null);
        reseed(ctx, elapsedMillis);
        lastElapsedMillis = elapsedMillis;
        initialized = true;
    }

    private void reseed(PatternContext ctx, double fromMillis) {
        double fromSeconds = fromMillis / 1000.0;
        for (int i = 0; i < drips.size(); i++) {
            // Staggered backwards across one interval, so the effect opens
            // already mid-flow instead of releasing every drip in unison.
            drips.set(i, newDrip(fromSeconds - random.nextDouble() * (dripIntervalMillis / 1000.0)));
        }
        if (stains != null) stains.clear();
    }

    private Drip newDrip(double startSeconds) {
        Drip drip = new Drip();
        drip.x = random.nextDouble();
        drip.startSeconds = startSeconds;
        drip.hangSeconds = 0.4 + random.nextDouble() * 0.9;
        drip.terminalSpeed = 0.34 + random.nextDouble() * 0.26;
        drip.weight = 0.7 + random.nextDouble() * 0.3;
        return drip;
    }

    /** Nearest LED to a normalised point. Brute force; see DriftParticlePattern for why that's fine. */
    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double nx, double ny) {
        KeyGrid.LedPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - nx, key.y() - ny);
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        return best;
    }
}
