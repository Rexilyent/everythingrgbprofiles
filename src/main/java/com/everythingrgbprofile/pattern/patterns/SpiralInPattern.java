package com.everythingrgbprofile.pattern.patterns;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * {@code spiral-in}: comet arms spiral inward from the board edge to the
 * centre, then a whole-board arrival flash. Parameterised on rotations,
 * arm count, trail length, wobble and arrival style so each dimension gets a
 * genuinely distinct signature rather than one motion with the hue swapped.
 *
 * <h2>Status: superseded, kept deliberately</h2>
 * The portal effect no longer uses this — {@link CoreEmitterPattern} does. See
 * that class for the measurement work showing the Terraria pillars this was
 * modelled on are a single rotating spiral fixed around a static core, with
 * nothing travelling in from the edges — which rules out a pattern whose
 * entire premise is comets travelling in from the edges.
 *
 * <p>It stays because it is a good comet-spiral in its own right, the three
 * genuinely hard problems below are already solved in it, and it is still the
 * natural pick for any future effect that wants something to sweep across the
 * board. Not dead code by accident — a working tool on the shelf.
 *
 * <h2>Three things a spiral on a keyboard has to get right</h2>
 * All three are non-obvious, all three look like "the animation is broken"
 * rather than like a maths problem, and all three are easy to reintroduce.
 *
 * <p><b>1. Trace an ellipse, not a circle.</b> {@code maxRadius()} is the
 * distance to the CORNER (about 0.707 in normalised space). Draw a circle at
 * that radius and most of the arc is off the board entirely, and every
 * off-board sample snaps to whatever edge key is nearest. The result is not a
 * spiral, it is a handful of dots pinned along the top and bottom rows. Size
 * the path to the board's real extents instead.
 *
 * <p><b>2. Wobble has to be continuous.</b> Calling
 * {@code random.nextDouble()} per sample per frame is not an organic flight
 * path, it is the head teleporting thirty times a second. Two summed sines at
 * an irrational frequency ratio give a path that is smooth, never exactly
 * repeats, and reads as genuinely unsteady.
 *
 * <p><b>3. One key is not a comet.</b> Lighting only the nearest LED gives a
 * one-key stroke, and at keyboard LED spacing a hard jump between keys reads
 * as a stutter rather than as motion. Every key within
 * {@code GLOW_RADIUS_KEYS} gets a share with smooth falloff, so the arm is a
 * band of light travelling rather than a dot skipping.
 */
public final class SpiralInPattern implements Pattern {

    /**
     * Faint bed of the dimension's colour under everything during the spiral.
     * The board is meant to stay dark or very dim here — note DIM, not black.
     * At true black it looks switched off between comet passes; at 7% it reads
     * as a portal glowing faintly with something moving through it.
     */
    private static final double AMBIENT_BED_ALPHA = 0.07;
    private static final double FRAME_MILLIS = 1000.0 / 30.0;
    /** Sanity cap on tail samples, so a long slow spiral can't blow up the per-frame cost. */
    private static final int MAX_TAIL_SAMPLES = 64;
    /** Glow radius around each sample, in key widths. 2.0 ≈ a comfortably thick arm. */
    private static final double GLOW_RADIUS_KEYS = 2.0;
    /** See the tailSpanP comment in renderSpiralPhase for why this exists. */
    private static final double TAIL_FRAMES_PER_UNIT = 7.0;

    private final double spiralPhaseFraction;
    private final double spiralRotations;
    private final int spiralArmCount;
    private final int trailLength;
    private final double radiusWobbleAmplitude;
    private final ArrivalStyle arrivalFlashStyle;
    private final Random random = new Random();

    /** SHARP = snappy strike. BLOOM = soft swell. Per-dimension flavour. */
    public enum ArrivalStyle { SHARP, BLOOM }

    public SpiralInPattern(double spiralPhaseFraction, double spiralRotations, int spiralArmCount,
                            int trailLength, double radiusWobbleAmplitude, ArrivalStyle arrivalFlashStyle) {
        this.spiralPhaseFraction = spiralPhaseFraction;
        this.spiralRotations = spiralRotations;
        this.spiralArmCount = Math.max(1, spiralArmCount);
        this.trailLength = Math.max(1, trailLength);
        this.radiusWobbleAmplitude = radiusWobbleAmplitude;
        this.arrivalFlashStyle = arrivalFlashStyle;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double duration = Math.max(1, ctx.durationMillis());
        double spiralPhaseMillis = spiralPhaseFraction * duration;
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();

        // Two phases, hard switch. spiralPhaseFraction decides the split.
        if (elapsedMillis <= spiralPhaseMillis) {
            renderSpiralPhase(ctx, elapsedMillis, spiralPhaseMillis, out);
        } else {
            renderArrivalFlash(ctx, elapsedMillis - spiralPhaseMillis, duration - spiralPhaseMillis, out);
        }
        return out;
    }

    private void renderSpiralPhase(PatternContext ctx, double elapsed, double phaseMillis, Map<KeyGrid.LedRef, LayerPixel> out) {
        KeyGrid grid = ctx.grid();
        double p = Math.min(1.0, elapsed / phaseMillis);
        RGBColor accent = ctx.resolvedAccentColor();

        // The dim bed, laid down first so the comet draws over it.
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            out.put(key.ref(), new LayerPixel(ctx.baseColor(), AMBIENT_BED_ALPHA));
        }

        // ELLIPSE, not circle — point 1 in the class doc. These two lines are
        // the whole of it, and they are the difference between "a spiral" and
        // "confetti stuck to the edges".
        double rx = grid.extentX();
        double ry = grid.extentY();

        // How far back in TIME the tail reaches.
        //
        // The spec is genuinely ambiguous here: its parameter table calls
        // trailLength "keys trailing behind each arm's head", while the prose
        // says "across trailLength preceding frames". Read either way
        // literally, the tail is 2-5 units long — on a 109-LED board that's a
        // dot with a stub, and trailLength stops being a meaningful
        // per-dimension differentiator at all.
        //
        // So each unit is treated as TAIL_FRAMES_PER_UNIT frames of travel.
        // That produces the sweeping arc the Terraria-pillar comparison
        // actually implies, and keeps trailLength doing its job: Nether at 2
        // is short and sharp, End at 5 is long and soft.
        //
        // Expressing the tail in time rather than distance is also what makes
        // a fast spiral throw a long tail and a slow one a short tail — which
        // is how real comets work.
        double tailSpanP = Math.min(p, trailLength * TAIL_FRAMES_PER_UNIT * FRAME_MILLIS / phaseMillis);
        double meanRadius = (rx + ry) * 0.25;
        double tailArc = spiralRotations * 2 * Math.PI * meanRadius * tailSpanP;
        // Subdivide finely enough that consecutive samples land on ADJACENT
        // keys — 0.6 of a key width apart. Coarser and the tail becomes a row
        // of disconnected dots instead of a continuous arc.
        int samples = (int) Math.ceil(tailArc / Math.max(1e-4, grid.keyWidthNormalised() * 0.6));
        samples = Math.max(trailLength, Math.min(samples, MAX_TAIL_SAMPLES));

        for (int arm = 0; arm < spiralArmCount; arm++) {
            double armOffset = (2 * Math.PI / spiralArmCount) * arm; // arms evenly spaced around the circle
            for (int i = 0; i < samples; i++) {
                double back = samples <= 1 ? 0 : i / (double) (samples - 1); // 0 = head, 1 = tail tip
                double samplePhase = Math.max(0, p - back * tailSpanP);
                double eased = FadePattern.easeInOutCubic(samplePhase);

                // Point 2: smooth low-frequency wobble from two summed sines.
                // 11.0 and 17.7 are in an irrational ratio on purpose — a
                // rational ratio produces a short repeating loop and your eye
                // finds repetition instantly. This never exactly repeats, so
                // it reads as genuinely unsteady flight.
                double wobble = 0;
                if (radiusWobbleAmplitude > 0) {
                    double w = Math.sin(eased * 11.0 + armOffset)
                             + 0.5 * Math.sin(eased * 17.7 + armOffset * 1.7);
                    wobble = w * radiusWobbleAmplitude * 0.10;
                }

                double shrink = (1 - eased) + wobble; // 1 at the edge, 0 at the centre
                double angle = armOffset + spiralRotations * 2 * Math.PI * eased;

                double x = grid.centerX() + rx * shrink * Math.cos(angle);
                double y = grid.centerY() + ry * shrink * Math.sin(angle);

                // Colour along the tail: the head runs WHITE-HOT and cools
                // through the base colour into the accent behind it. A
                // uniformly-coloured trail reads as a worm; a hot head reading
                // into a cooler tail reads as energy. Same trick every good
                // particle system uses.
                RGBColor color;
                if (back < 0.15) {
                    // First 15% — blend up to 55% toward white at the very tip.
                    color = ctx.baseColor().lerp(RGBColor.WHITE, (0.15 - back) / 0.15 * 0.55);
                } else {
                    // Remaining 85% — base fading to accent.
                    color = ctx.baseColor().lerp(accent, (back - 0.15) / 0.85);
                }
                // Slightly super-linear falloff: dims a touch faster than
                // linear so the tail has a defined end rather than trailing
                // off into an indistinct smear.
                double alpha = Math.pow(1.0 - back, 1.1);

                // Point 3: spread each sample over a glow radius rather than
                // lighting the single nearest key.
                double glow = grid.keyWidthNormalised() * GLOW_RADIUS_KEYS;
                for (KeyGrid.LedPosition key : grid.allKeys()) {
                    double d = Math.hypot(key.x() - x, key.y() - y);
                    if (d > glow) continue;
                    double falloff = 1.0 - (d / glow);
                    double a2 = alpha * falloff;
                    // Below the ambient bed this contributes nothing visible —
                    // skip rather than merge a value that loses anyway.
                    if (a2 <= AMBIENT_BED_ALPHA) continue;
                    final RGBColor c = color;
                    // merge with MAX, not addition: samples overlap heavily
                    // along the arc, and summing them would saturate the whole
                    // tail to white. Brightest sample wins. (Contrast with the
                    // drift engine, which DOES add — separate motes should
                    // stack, overlapping samples of one continuous stroke
                    // should not.)
                    out.merge(key.ref(), new LayerPixel(c, a2),
                            (u, v) -> u.alpha() >= v.alpha() ? u : v);
                }
            }
        }
    }

    /**
     * Phase 2: the whole board flashes as you arrive.
     *
     * <p>SHARP attacks in 10% and decays cubically to nothing — a strike.
     * BLOOM swells over 35% with an eased curve and only falls back 60% — a
     * swell that leaves an afterglow. Two genuinely different arrivals from
     * one function.
     */
    private void renderArrivalFlash(PatternContext ctx, double elapsed, double phaseMillis, Map<KeyGrid.LedRef, LayerPixel> out) {
        double p = Math.min(1.0, elapsed / Math.max(1, phaseMillis));
        double brightness;
        if (arrivalFlashStyle == ArrivalStyle.SHARP) {
            double attackEnd = 0.10;
            brightness = p <= attackEnd ? p / attackEnd : 1.0 - (1 - Math.pow(1 - Math.min(1, (p - attackEnd) / (1 - attackEnd)), 3));
        } else { // BLOOM
            double attackEnd = 0.35;
            brightness = p <= attackEnd ? FadePattern.easeInOutCubic(p / attackEnd)
                    : 1.0 - FadePattern.easeInOutCubic(Math.min(1, (p - attackEnd) / (1 - attackEnd))) * 0.6;
        }
        // Halfway between base and accent: brighter than the base so it reads
        // as a flash, still recognisably this dimension's colour rather than
        // generic white.
        RGBColor color = ctx.baseColor().lerp(ctx.resolvedAccentColor(), 0.5);
        LayerPixel pixel = new LayerPixel(color, Math.max(0, brightness));
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            out.put(key.ref(), pixel);
        }
    }

    /**
     * Nearest key to a point.
     *
     * <p>Currently unused — the leftover from the single-key version described
     * in point 3 of the class doc. Kept because any future revision that wants
     * a hard single-key head will want it back, and it is eight lines.
     */
    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double x, double y) {
        KeyGrid.LedPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - x, key.y() - y);
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        return best;
    }
}
