package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * A serpent winding across the board, head first, body trailing behind it.
 *
 * <p>Built for the Twilight Forest Naga on the same principle as the Ender
 * Dragon's silhouette: draw the boss, do not tint the board and hope. A green
 * pulse says something is happening; a snake with a head and a tail crossing
 * the keys says <i>that</i> is happening.
 *
 * <h2>The body is the head's past, not a simulation</h2>
 * There is no per-segment state and nothing is integrated frame to frame. The
 * head sits at a position {@code u} along a fixed winding track, and body
 * segment {@code i} simply sits a fixed distance behind it along the same
 * track. A snake IS its own history, so expressing it that way makes the body
 * follow perfectly by construction — it cannot drift, stretch, or come apart,
 * and it behaves identically at 10fps and 60.
 *
 * <h2>Why the track wraps cleanly</h2>
 * The track is {@code y = centre + amplitude * sin(2*pi*WAVES*u + drift)} with
 * {@code WAVES} an integer, so {@code y(0)} and {@code y(1)} are equal and the
 * snake can run off the right edge and back on at the left without a visible
 * seam. Make WAVES fractional and the body tears in half every lap.
 *
 * <h2>Segments you can count</h2>
 * The Naga sheds body segments as it takes damage, so the length of this thing
 * is the health bar — which only works if a viewer can actually see one
 * segment leave. Two things make that legible:
 *
 * <p><b>Beads, not a smear.</b> Each segment is drawn a little smaller than
 * its spacing, so the body reads as a chain of bright beads joined by dimmer
 * necks rather than one continuous bar. Neighbours still overlap — the chain
 * stays connected as one animal, and {@code LightBudget} adds their light, so
 * the necks land around 70% of a bead rather than going dark. That ripple is
 * the whole point: it is what lets you count the body, and notice when the
 * count drops.
 *
 * <p><b>Spaced by distance, not by {@code u}.</b> Segments sit at equal
 * <i>arc length</i> along the track, found by integrating it into a lookup
 * table. Spacing evenly in {@code u} instead, as an earlier version did, makes
 * the gaps uneven: where the wave is steep the track climbs faster than it
 * advances, so segments spaced for the flat sections pull apart vertically and
 * the body turns into a dotted line exactly where it is bending most. Even arc
 * spacing also means one shed segment shortens the snake by the same visible
 * amount wherever on the wave it happens to go.
 *
 * <h2>Charging</h2>
 * The Naga's signature move is lining up and bolting at you. That is expressed
 * by flattening the track toward a straight line while speeding it up: a snake
 * that stops weaving and comes straight at you reads as a charge without
 * needing a separate animation.
 */
public final class SnakeSlitherPattern implements Pattern {

    /** Full sine cycles across the board. Integer, so the track wraps seamlessly. */
    private static final int WAVES = 2;
    /**
     * Gap between segment centres, in key widths, measured along the track.
     *
     * <p>Authored in key widths rather than as a fraction of a lap, so a
     * segment is the same physical size of thing whatever the board: one shed
     * segment costs a key and a half of visible body on a full-size keyboard,
     * and the guard below tightens that where a board cannot spare it.
     */
    private static final double SEGMENT_SPACING_KEYS = 1.55;
    /**
     * Longest body the spacing is laid out for — {@code NagaEffect.MAX_SEGMENTS}
     * is kept at or under it.
     *
     * <p>Spacing is worked out against this rather than against the count
     * currently being drawn, and that is the whole point of the constant. Fit
     * the body to the <i>current</i> count instead and shedding a segment lets
     * the survivors spread out to fill the space it left, so the snake stays
     * very nearly as long as it was — measured at three tenths of a key
     * shorter for the first segment lost, which is nothing. Pinning the gap to
     * a fixed distance makes a lost segment simply a lost segment.
     */
    private static final int DESIGN_SEGMENTS = 11;
    /**
     * How much of a straight lap a full-length body may occupy.
     *
     * <p>Two jobs. It stops a long snake on a small board running the whole
     * lap to meet its own head, which reads as a ring rather than a serpent.
     * And it keeps clear track behind the tail even at full health — which is
     * what actually makes the length readable, because a tail needs somewhere
     * visible to retract to. An earlier version allowed 0.82, and at full
     * health the first segment lost was invisible: the body already wrapped
     * four fifths of the board, so losing one only shuffled the tail along
     * inside the crowd.
     */
    private static final double MAX_BODY_FRACTION = 0.75;
    /**
     * Segment radius as a fraction of the spacing actually used.
     *
     * <p>Tied to the spacing rather than set independently, because the ratio
     * between the two <i>is</i> the bead effect: at 0.62 two neighbours meet
     * at about 70% of a bead's own brightness. Fix the radius in key widths
     * instead and a board that needs tighter spacing gets beads that overlap
     * until the ripple flattens out and the body goes back to being a smear —
     * on exactly the small boards where the length is hardest to read.
     */
    private static final double SEGMENT_RADIUS_OF_SPACING = 0.62;
    /**
     * Floor for that radius, so a bead landing between two keys still lights
     * one. It binds only on boards narrow enough that the guard has squeezed
     * the spacing below about 1.3 keys, and there the beads do merge back into
     * a smear — a 15-column board has nowhere to put ten separate ones. Length
     * alone carries the health on those; it is still most of the board's width
     * travelling, so it carries it well enough.
     */
    private static final double MIN_SEGMENT_RADIUS_KEYS = 0.8;
    private static final double HEAD_RADIUS_KEYS = 1.7;

    /**
     * Samples used to integrate the track's length. 256 across a two-cycle
     * sine is far finer than the key pitch it all gets quantised onto anyway.
     */
    private static final int TRACK_SAMPLES = 256;

    private volatile int segments = 8;
    private volatile double lapsPerSecond = 0.16;
    private volatile double waveAmplitude = 0.24;
    private volatile double glow = 1.0;
    /** 0 = weaving normally, 1 = straightened out and charging. */
    private volatile double charge = 0;

    private double travel = 0;
    private double lastElapsedMillis = 0;
    private boolean initialised = false;

    /**
     * Cumulative track length at {@code u = i / TRACK_SAMPLES}. Rebuilt every
     * frame, because the track's shape moves — the weave drifts, and charging
     * flattens it — and held as a field rather than reallocated, since render
     * runs on the one SDK worker thread.
     */
    private final double[] arcAt = new double[TRACK_SAMPLES + 1];

    public void setBody(int segments, double waveAmplitude) {
        this.segments = Math.max(2, Math.min(40, segments));
        this.waveAmplitude = Math.max(0.02, Math.min(0.45, waveAmplitude));
    }

    public void setMotion(double lapsPerSecond, double charge) {
        this.lapsPerSecond = Math.max(0.01, lapsPerSecond);
        this.charge = Math.max(0, Math.min(1, charge));
    }

    public void setGlow(double glow) {
        this.glow = Math.max(0, glow);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        // Travel is accumulated rather than derived from elapsed time, because
        // the speed changes when it charges. Integrating keeps the head moving
        // continuously through a speed change instead of teleporting to
        // wherever the new speed says it should have been all along.
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            lastElapsedMillis = elapsedMillis;
            initialised = true;
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        travel = (travel + dt * lapsPerSecond) % 1.0;

        double seconds = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        double headRadius = HEAD_RADIUS_KEYS * keyWidth;

        RGBColor scale = ctx.baseColor();
        RGBColor highlight = ctx.resolvedAccentColor();

        // Charging flattens the weave and slides the track's phase, so it does
        // not merely go faster along the same curve — it stops weaving.
        double amplitude = waveAmplitude * (1.0 - 0.85 * charge);
        double drift = 0.35 * Math.sin(2 * Math.PI * 0.07 * seconds);

        double trackLength = measureTrack(amplitude, drift, aspect);
        int count = segments;
        int gaps = Math.max(1, count - 1);
        // Honour the authored spacing where a full-length body fits, tighten
        // it where it does not. Two things are deliberately kept out of this:
        //
        // The count being drawn — the guard sizes DESIGN_SEGMENTS, so the gap
        // is the same whether the snake is whole or down to its last few.
        //
        // The current track length — the guard uses a straight lap, which is
        // 1.0 in normalised x and the shortest the track ever gets, since a
        // charge flattens the weave and a deep weave adds a good 10%. Size
        // against the live length instead and the body quietly contracts every
        // time the Naga straightens out to charge, which reads as damage it
        // has not taken. The last term is the only one that watches the live
        // track, and only binds for a caller asking for more segments than the
        // envelope allows for — keeping even that snake off its own head.
        double spacing = Math.min(SEGMENT_SPACING_KEYS * keyWidth,
                Math.min(MAX_BODY_FRACTION / (DESIGN_SEGMENTS - 1),
                        0.95 * trackLength / gaps));
        double bodyRadius = Math.max(MIN_SEGMENT_RADIUS_KEYS * keyWidth, SEGMENT_RADIUS_OF_SPACING * spacing);
        double headArc = travel * trackLength;

        LightBudget budget = new LightBudget();
        for (int i = 0; i < count; i++) {
            double u = uAtArc(headArc - i * spacing, trackLength);
            double sx = u;
            double sy = trackY(u, amplitude, drift);

            boolean head = i == 0;
            double radius = head ? headRadius : bodyRadius;
            // Tapers toward the tail, so the shape has a direction even when
            // it is not moving much. Shallow, though: the last segments are
            // the ones being counted, and a tail that fades to nothing is a
            // tail whose length nobody can read.
            double taper = head ? 1.0 : 1.0 - 0.3 * (i / (double) gaps);
            RGBColor color = head ? highlight : scale;

            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double dx = key.x() - sx;
                // The track wraps, so a segment near an edge has to light keys
                // on the far side too or the snake breaks apart mid-lap.
                if (dx > 0.5) dx -= 1.0;
                if (dx < -0.5) dx += 1.0;
                double dy = (key.y() - sy) * aspect;
                double d = Math.hypot(dx, dy);
                if (d > radius) continue;
                double falloff = 1.0 - Math.pow(d / radius, 2.0);
                // Body segments are driven past full so a bead's own keys clip
                // at the top of the budget while the necks between them do
                // not. Understating this is what makes a bead chain read as
                // scattered dim keys rather than as one segmented animal.
                budget.add(key.ref(), color, falloff * taper * glow * (head ? 1.3 : 1.15));
            }
        }
        return budget.resolve();
    }

    /** The track's height at {@code u}. */
    private static double trackY(double u, double amplitude, double drift) {
        return 0.5 + amplitude * Math.sin(2 * Math.PI * WAVES * u + drift);
    }

    /**
     * Fills {@link #arcAt} for the current track shape and returns the track's
     * total length, in aspect-corrected normalised units — so a lap gets
     * longer as the weave deepens, which is right: a snake following a deeper
     * wave really does travel further to cross the board.
     */
    private double measureTrack(double amplitude, double drift, double aspect) {
        double du = 1.0 / TRACK_SAMPLES;
        double total = 0;
        double prevY = trackY(0, amplitude, drift);
        arcAt[0] = 0;
        for (int i = 1; i <= TRACK_SAMPLES; i++) {
            double y = trackY(i * du, amplitude, drift);
            total += Math.hypot(du, (y - prevY) * aspect);
            arcAt[i] = total;
            prevY = y;
        }
        return Math.max(1e-6, total);
    }

    /**
     * Inverse of {@link #arcAt}: the {@code u} lying {@code arc} along the
     * track, wrapping so a tail hanging off the start of the lap comes back on
     * at the end of it.
     */
    private double uAtArc(double arc, double trackLength) {
        double s = arc % trackLength;
        if (s < 0) s += trackLength;
        // arcAt only ever increases, so a binary search lands in the right
        // cell; interpolating inside it is what keeps segments off the sample
        // lattice, which they would otherwise visibly snap along.
        int lo = 0;
        int hi = TRACK_SAMPLES;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (arcAt[mid] <= s) lo = mid;
            else hi = mid;
        }
        double cell = arcAt[hi] - arcAt[lo];
        double frac = cell > 1e-12 ? (s - arcAt[lo]) / cell : 0;
        return (lo + frac) / TRACK_SAMPLES;
    }
}
