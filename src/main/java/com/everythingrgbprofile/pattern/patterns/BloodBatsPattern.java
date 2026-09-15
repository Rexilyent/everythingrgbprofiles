package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Vampire forest: blood running down a black keyboard, with bats crossing in
 * front of it.
 *
 * <p>The blood is {@link BloodDripPattern} — the same engine as the death
 * screen, because the vampire forest wants exactly the same thing and writing
 * a second copy would only have been a way of pretending otherwise. Only the
 * numbers differ: fewer drips, further apart, because this is wallpaper you
 * stand in for ten minutes rather than a three-second "you died".
 *
 * <h2>The bats are holes, not shapes</h2>
 * Every other creature in this mod is drawn by adding light. The bats are
 * drawn by taking it away — they carve the blood layer down to nothing where
 * they pass. That is not a shortcut, it is the only version that looks right:
 * a bat is something you notice because it blocks what is behind it, and an
 * LED keyboard renders "unlit" far more convincingly than it renders "dark
 * brown".
 *
 * <p>A coloured bat was the obvious alternative, and every candidate colour
 * fails for the same reason. Dark red or brown is just a dimmer patch of the
 * blood, so the eye reads it as part of a drip rather than as something in
 * front of one. Purple or grey needs enough brightness to register on an LED
 * that it stops looking dark at all, and a glowing bat reads as a firefly. An
 * unlit key is the only "dark" an RGB keyboard can show without compromise,
 * so the bats are black.
 *
 * <p>The consequence worth knowing before retuning anything: <b>this only
 * works while the board underneath is lit.</b> A silhouette needs something to
 * be a silhouette against. The death screen's soak is near-black on purpose
 * and bats over it would be invisible, which is why the vampire forest profile
 * uses a much brighter soak than the death screen does. Darken that colour in
 * a config and the bats do not break — they quietly stop existing, with no
 * error to explain where they went.
 *
 * <h2>Why they wander instead of flying across</h2>
 * The dragon crosses the board in a straight line because a dragon in flight
 * commits to a direction. Bats are not famous for this. Each one here is two
 * sine waves per axis: a slow, wide sweep that carries it around the board,
 * and a faster, smaller jitter on top that gives it the darting quality. One
 * sine per axis would trace a Lissajous figure — a closed loop the eye picks
 * up within a few seconds. Two per axis at frequencies sharing no common
 * multiple means the path never closes and never repeats a figure you could
 * learn. It reads as erratic without being random — which matters, because
 * random needs state, and state would need a clock-restart guard.
 *
 * <p>On that note: bat positions are a pure function of elapsed time, so this
 * pattern has no state to corrupt when the effect clock restarts at zero. The
 * blood inside it does, and looks after itself.
 */
public final class BloodBatsPattern implements Pattern {

    /**
     * One bat's flight plan. Nothing here changes after construction — a bat
     * is nine constants and a clock.
     */
    private static final class Bat {
        final double sweepHzX, sweepHzY, jitterHzX, jitterHzY;
        final double phaseX, phaseY, jitterPhaseX, jitterPhaseY;
        final double flapHz;
        final double scale;

        Bat(double sweepHzX, double sweepHzY, double jitterHzX, double jitterHzY,
            double phaseX, double phaseY, double jitterPhaseX, double jitterPhaseY,
            double flapHz, double scale) {
            this.sweepHzX = sweepHzX;
            this.sweepHzY = sweepHzY;
            this.jitterHzX = jitterHzX;
            this.jitterHzY = jitterHzY;
            this.phaseX = phaseX;
            this.phaseY = phaseY;
            this.jitterPhaseX = jitterPhaseX;
            this.jitterPhaseY = jitterPhaseY;
            this.flapHz = flapHz;
            this.scale = scale;
        }

        double x(double t) {
            return 0.5 + SWEEP_X * Math.sin(TAU * sweepHzX * t + phaseX)
                    + JITTER_X * Math.sin(TAU * jitterHzX * t + jitterPhaseX);
        }

        double y(double t) {
            return 0.5 + SWEEP_Y * Math.sin(TAU * sweepHzY * t + phaseY)
                    + JITTER_Y * Math.sin(TAU * jitterHzY * t + jitterPhaseY);
        }

        /**
         * Analytic derivative of {@link #x}, used only for its sign, to decide
         * which way the bat is facing. Differentiating is cheaper and tidier
         * than remembering last frame's position, and it cannot go stale when
         * the clock restarts.
         */
        double facing(double t) {
            double dx = SWEEP_X * TAU * sweepHzX * Math.cos(TAU * sweepHzX * t + phaseX)
                    + JITTER_X * TAU * jitterHzX * Math.cos(TAU * jitterHzX * t + jitterPhaseX);
            return dx >= 0 ? 1 : -1;
        }
    }

    private static final double TAU = 2 * Math.PI;

    /** How far the wander reaches from centre, in board widths and heights. */
    private static final double SWEEP_X = 0.40;
    private static final double SWEEP_Y = 0.26;
    private static final double JITTER_X = 0.07;
    private static final double JITTER_Y = 0.06;

    /**
     * Bat dimensions, in key widths — the same unit the Wither's heads use,
     * and for the same reason. Anything expressed as a fraction of the board
     * silently changes size with the hardware, and anything under about two
     * keys across falls between LEDs and renders as nothing at all.
     *
     * <p>Six keys of wingspan is roughly the floor for reading as a bat rather
     * than as a smudge, so that is what this is.
     */
    private static final double WING_HALF_KEYS = 3.0;
    private static final double WING_THICKNESS_KEYS = 0.85;
    private static final double BODY_RADIUS_KEYS = 1.05;
    /** Vertical travel of the wingtips, in key widths. */
    private static final double FLAP_RISE_KEYS = 1.5;
    /**
     * A permanent upward crook in the wings, in key widths, that the beat
     * swings around rather than starting from.
     *
     * <p>Without it the wings pass through dead flat twice per beat, and a
     * flat bat is a dash. The silhouette everyone actually recognises has the
     * wings angled up out of the body, so the shape holds that angle at rest
     * and the flap modulates it — which also means the bat still reads as a
     * bat on the frames where the beat happens to be crossing zero.
     */
    private static final double WING_CAMBER_KEYS = 0.55;

    /**
     * How much light a bat removes at its centre. 1.0 = fully unlit.
     *
     * <p>Full black is deliberate. The rim is soft regardless, so this does not
     * produce a hard-edged blob — it produces a shape with a solid core, which
     * is what a silhouette is.
     */
    private static final double BAT_OPACITY = 1.0;

    private final Bat[] bats;
    private final BloodDripPattern blood;

    private BloodBatsPattern(Bat[] bats, BloodDripPattern blood) {
        this.bats = bats;
        this.blood = blood;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        // Same clock forwarded, so the blood's own restart guard stays honest.
        Map<KeyGrid.LedRef, LayerPixel> frame = blood.render(ctx, elapsedMillis);
        if (frame.isEmpty()) return frame;

        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();

        // Poses resolved once per frame rather than once per key. Three bats
        // against a hundred keys is not much trigonometry, but recomputing an
        // identical sine a hundred times is exactly the sort of thing that
        // turns into a profiler mystery six months later.
        int count = bats.length;
        double[] batX = new double[count];
        double[] batY = new double[count];
        double[] flap = new double[count];
        double[] span = new double[count];
        for (int i = 0; i < count; i++) {
            Bat bat = bats[i];
            batX[i] = bat.x(t);
            batY[i] = bat.y(t);
            // Facing flips the stroke rather than mirroring the geometry: the
            // shape is symmetric so there is nothing to mirror, but a bat that
            // turns around mid-beat should not carry the old beat with it.
            flap[i] = Math.sin(TAU * bat.flapHz * t) * bat.facing(t);
            span[i] = WING_HALF_KEYS * keyWidth * bat.scale;
        }

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(frame.size());
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> entry : frame.entrySet()) {
            LayerPixel pixel = entry.getValue();
            KeyGrid.LedPosition pos = grid.position(entry.getKey());
            if (pos == null || pixel.alpha() <= 0) {
                out.put(entry.getKey(), pixel);
                continue;
            }

            double cover = 0;
            for (int i = 0; i < count && cover < 1.0; i++) {
                cover = Math.max(cover, coverage(pos, batX[i], batY[i], flap[i], span[i],
                        bats[i].scale, keyWidth, aspect));
            }
            if (cover <= 0) {
                out.put(entry.getKey(), pixel);
                continue;
            }
            // Scale the alpha down rather than darkening the colour. Blood at
            // 20% over black and near-black blood look identical right here,
            // but only one of them stays correct if anything is ever
            // composited beneath this layer. LayerPixel has the long version
            // of that argument, and the bug it came from.
            out.put(entry.getKey(),
                    new LayerPixel(pixel.color(), pixel.alpha() * (1.0 - cover * BAT_OPACITY)));
        }
        return out;
    }

    /**
     * How much of this key one bat covers, 0 to 1, with a soft rim.
     *
     * @param flap -1..1, current wingbeat position
     * @param span half the wingspan, in normalised x units
     */
    private static double coverage(KeyGrid.LedPosition key, double batX, double batY,
                                   double flap, double span, double scale,
                                   double keyWidth, double aspect) {
        double dx = key.x() - batX;
        // Vertical distance is scaled into x units before anything is measured,
        // so the bat is the shape it claims to be on the physical board rather
        // than on the 3:1 normalised grid.
        double dy = (key.y() - batY) * aspect;

        double body = BODY_RADIUS_KEYS * keyWidth * scale;
        double reach = span + body;
        // Cheap reject. Most keys are nowhere near any bat, most of the time.
        if (Math.abs(dx) > reach || Math.abs(dy) > reach) return 0;

        double cover = 0;

        // --- body ------------------------------------------------------
        double bodyDist = Math.hypot(dx, dy);
        if (bodyDist < body) {
            // 1.6x so the middle of the body saturates to fully unlit well
            // before the edge, leaving a solid core with a soft rim rather
            // than a gradient that never quite reaches black anywhere.
            cover = Math.min(1.0, 1.6 * (1.0 - bodyDist / body));
        }

        // --- wings -----------------------------------------------------
        // u runs 0..1 from the body out to the wingtip. The membrane lifts with
        // |u|^1.3, so most of the travel lives in the tips — which is what a
        // wingbeat looks like, and the same curve the dragon's wings use.
        double u = Math.abs(dx) / span;
        if (u <= 1.0) {
            // Negative is up: y runs downward here, same screen convention the
            // drift engine documents. Camber and beat share the sign so the
            // wings hinge from an already-raised position.
            double membrane = -(WING_CAMBER_KEYS + FLAP_RISE_KEYS * flap)
                    * keyWidth * scale * Math.pow(u, 1.3);
            double thickness = WING_THICKNESS_KEYS * keyWidth * scale * (1.0 - 0.35 * u);
            double d = Math.abs(dy - membrane);
            if (d < thickness) {
                cover = Math.max(cover, 1.0 - d / thickness);
            }
        }
        return Math.min(1.0, cover);
    }

    /**
     * The vampire forest's three bats.
     *
     * <p>Three is the tuned number. One reads as a fault in the lighting, two
     * tend to pair up and look choreographed, and four or more eat enough of
     * the board that the blood stops being legible — which matters, because
     * the blood is the thing they are silhouetted against.
     *
     * <p>The frequencies are deliberately awkward numbers. Any two bats on
     * tidy ratios drift into sync and then stay there, and two bats moving in
     * formation stop looking like animals immediately.
     */
    public static BloodBatsPattern vampireForest() {
        Bat[] bats = {
                new Bat(0.113, 0.171, 0.61, 0.73, 0.0, 1.9, 0.4, 2.7, 3.7, 1.00),
                new Bat(0.087, 0.139, 0.53, 0.67, 2.3, 4.4, 1.8, 0.9, 4.3, 0.82),
                new Bat(0.142, 0.101, 0.71, 0.59, 4.7, 3.1, 5.2, 4.1, 3.1, 1.12),
        };
        // Fewer and slower drips than the death screen. Standing in a forest is
        // not the same event as bleeding out, and a board running with blood at
        // full death-screen rate would be exhausting to actually play in.
        return new BloodBatsPattern(bats, new BloodDripPattern(4, 900));
    }
}
