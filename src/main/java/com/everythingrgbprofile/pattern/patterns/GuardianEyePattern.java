package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * One enormous eye in dark water, spikes out, charging a laser at you.
 *
 * <p>An Elder Guardian is a cube with an eye covering most of its face, and
 * that is the whole reason this draws what it draws. Same principle as the
 * dragon's silhouette and the Naga's body: the shape is the thing you
 * recognise, so draw the shape. A keyboard is unusually well suited to this
 * one — the board becomes the eye, which is the single most recognisable mob
 * silhouette in the game after the creeper.
 *
 * <h2>Why the eye is a ring with a hole in it</h2>
 * {@code LightBudget} only ever adds light, so a dark iris cannot be painted
 * over a lit body — there is no subtraction to be had. The body is therefore
 * drawn as an annulus and the iris is simply the gap left between it and the
 * pupil: unlit keys, which on a keyboard is exactly what dark looks like.
 * Attempting the obvious layering instead gives a pale disc with a paler dot
 * in it, and nothing about it says eye.
 *
 * <h2>The eye is an oval, and that is the point</h2>
 * Aspect-corrected, a full-size board is about 21 key widths across and 5.5
 * tall, so anything drawn genuinely round can be no more than about 2.7 key
 * widths in radius before the top and bottom are cut off. An earlier version
 * did exactly that and the result was five keys across: a ring one key thick
 * around an iris one key wide, which is not a shape, it is a smudge. There is
 * no size at which a circle both fits this board and reads as anything.
 *
 * <p>So it does not draw a circle. {@link #EYE_SQUASH} compresses the vertical
 * term until the eye is nearly twice as wide as it is tall, which lets it span
 * nine keys and every row — and costs nothing in recognition, because a drawn
 * eye is an oval anyway. The guardian's own eye is round; an eye in a picture
 * of a guardian has never had to be.
 *
 * <p>Filling the height that way leaves nowhere for the crown of spikes, so
 * it is not drawn as spears — see {@link #BODY_RADIUS_SWIMMING_KEYS} for what
 * the board does with it instead. What remains of the spines is a fringe on
 * the body's own edge.
 *
 * <h2>The laser is a line, not a ring</h2>
 * The charge could be drawn as a ring closing on the eye, and that would be
 * the wrong shape twice over. A laser is a line — and a ring contracting on
 * the middle of the board is already the Warden's arrival, an effect this one
 * must never be mistaken for. So the beam grows out of the eye along the
 * board's long axis, brightening and thickening across the three seconds the
 * Elder Guardian spends locked on, and the pupil constricts while it does.
 * That whole tell is real: 60 ticks of charge, synced, the longest wind-up of
 * any attack in vanilla.
 */
public final class GuardianEyePattern implements Pattern {

    /**
     * How much the vertical term is compressed, after the usual aspect
     * correction. 1 would draw a true circle; 0.55 makes the eye a little
     * under twice as wide as it is tall.
     *
     * <p>Chosen against the board rather than by taste: at this squash an
     * outer radius of 4.6 key widths is 9.2 keys across and just over five
     * rows tall, which on a full-size keyboard is most of the width and all of
     * the height. The eye is as large as the board can hold.
     */
    private static final double EYE_SQUASH = 0.55;

    /**
     * The body's outer radius, in key widths along its wide axis, swimming and
     * bristling.
     *
     * <p>The crown of spikes is drawn as the body <i>swelling</i>, and that is
     * a concession to the board rather than a stylistic choice. Sizing the eye
     * to the full height leaves nowhere for a spine to go: it gets the two
     * rows above the ring and then it is off the board, so an extended crown
     * came out as one extra lit key either side of the eye — a state nobody
     * could read. Growing the whole eye by most of a key is unmissable, and it
     * is the same information, because what puts the spikes out is the thing
     * coming to a stop to aim at you.
     */
    private static final double BODY_RADIUS_SWIMMING_KEYS = 4.05;
    private static final double BODY_RADIUS_BRISTLING_KEYS = 4.85;
    /** Thickness of the body band. Constant, so the whole eye grows rather than the ring. */
    private static final double BODY_BAND_KEYS = 1.5;
    private static final double PUPIL_KEYS = 1.5;

    /**
     * Spines around the body, and how far they reach when fully extended.
     *
     * <p>Twelve rather than a tidier eight, and the reason is the board. Sizing
     * the eye to the full height leaves no room above or below it, so a spine
     * is only visible over any length if it points somewhere near horizontal —
     * a steep one crosses the two rows it has and stops. At twelve the gap is
     * 30 degrees, so whatever the crown has rotated to there is always a spine
     * within fifteen degrees of flat, and always a pair of long ones out of
     * the sides. An earlier version had ten spines turning at 0.035Hz and the
     * crown appeared to come and go: every few seconds the rotation put every
     * spine into the part of the board that cannot show one.
     */
    private static final int SPIKES = 12;
    private static final double SPIKE_LENGTH_KEYS = 1.7;
    /**
     * Half-width of a spine, as a fraction of the gap between two of them.
     * 0.17 of a 30-degree slot is about 5 degrees either side — thin enough to
     * read as a spine rather than a pie slice.
     */
    private static final double SPIKE_HALF_SLOT = 0.17;
    /** Turns of the whole spike crown per second. Slow: it hovers, it does not spin. */
    private static final double SPIN_HZ = 0.02;

    /** Beam thickness in key widths, at rest and at full charge. */
    private static final double BEAM_HALF_THICKNESS_KEYS = 0.55;
    private static final double BEAM_HALF_THICKNESS_CHARGED_KEYS = 1.25;

    /**
     * The caustic wash: the water the thing is hovering in.
     *
     * <p>Two sines on deliberately unrelated frequencies, which is the same
     * trick the biome water patterns use — a single sine reads as the whole
     * board breathing in time, and light through water does not do that.
     */
    private static final double CAUSTIC_BASE = 0.085;
    private static final double CAUSTIC_DEPTH = 0.065;

    /**
     * How far the eye drifts from the middle of the board while it swims, in
     * key widths side to side and rows up and down.
     *
     * <p>The drift is the Guardian being alive in the water, not a map of
     * where it is — the board has never been a map for this boss. It follows
     * the mob's own rhythm: it swims about with its spikes in, bobs in place
     * with them out, and holds still to aim, so the eye settling is part of
     * the lock-on rather than something to read past. Mostly sideways, because
     * the eye already fills every row and the board has room only across.
     */
    private static final double DRIFT_KEYS = 2.2;
    private static final double DRIFT_ROWS = 0.35;
    /** Share of that drift kept while hovering with the crown out: a bob rather than a swim. */
    private static final double DRIFT_HOVERING_SHARE = 0.4;
    /** How much of the drift is left at full charge. */
    private static final double DRIFT_AT_FULL_CHARGE = 0.2;
    /**
     * Seconds for the drift to settle toward a new size. Without it the eye
     * would jump the moment a beam fires, because the charge drops straight
     * back to zero and the hover it returns to is wider than the aim.
     */
    private static final double DRIFT_EASE_SECONDS = 0.8;

    private volatile double spikes = 1.0;
    private volatile double charge = 0;
    private volatile double flash = 0;
    private volatile double curse = 0;
    private volatile double glow = 1.0;

    // Render-thread only.
    private double shownDrift = 0;
    private long lastRenderMillis = Long.MIN_VALUE;

    /** @param spikes 0 retracted and swimming, 1 fully out and hovering */
    public void setBody(double spikes, double glow) {
        this.spikes = Math.max(0, Math.min(1, spikes));
        this.glow = Math.max(0, glow);
    }

    /**
     * @param charge 0 to 1 across the wind-up
     * @param flash  0 to 1, the moment the beam lands, decayed by the caller
     */
    public void setBeam(double charge, double flash) {
        this.charge = Math.max(0, Math.min(1, charge));
        this.flash = Math.max(0, Math.min(1, flash));
    }

    /**
     * How cursed you are, 0 to 1.
     *
     * <p>Mining Fatigue does not change the picture, it changes its rhythm:
     * the water stops shimmering and starts heaving. Slower and deeper is what
     * "heavy" looks like in one number, and it costs nothing to read from
     * across the room, which a colour shift alone does not.
     */
    public void setCurse(double curse) {
        this.curse = Math.max(0, Math.min(1, curse));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double seconds = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();

        // --- the drift ---------------------------------------------------
        // One number, 1 for swimming and 0 for dead still, eased over time so
        // it never steps. Its shape is two sines per axis on unrelated
        // periods, which wanders rather than tracing the same figure-eight
        // every fourteen seconds.
        double wantedDrift = (DRIFT_HOVERING_SHARE + (1 - DRIFT_HOVERING_SHARE) * (1 - spikes))
                * (1 - (1 - DRIFT_AT_FULL_CHARGE) * charge);
        if (lastRenderMillis == Long.MIN_VALUE || elapsedMillis < lastRenderMillis) {
            shownDrift = wantedDrift;
        } else {
            double dt = Math.min(0.25, (elapsedMillis - lastRenderMillis) / 1000.0);
            shownDrift += (wantedDrift - shownDrift) * (1 - Math.exp(-dt / DRIFT_EASE_SECONDS));
        }
        lastRenderMillis = elapsedMillis;
        double driftX = DRIFT_KEYS * shownDrift
                * (0.75 * Math.sin(2 * Math.PI * seconds / 14.0) + 0.25 * Math.sin(2 * Math.PI * seconds / 5.3 + 1.1));
        double driftY = DRIFT_ROWS * shownDrift
                * (0.7 * Math.sin(2 * Math.PI * seconds / 6.1 + 0.4) + 0.3 * Math.sin(2 * Math.PI * seconds / 2.9));
        double cx = grid.centerX() + driftX * keyWidth;
        // A row is a key width tall once the aspect correction is undone.
        double cy = grid.centerY() + driftY * keyWidth / aspect;

        RGBColor water = ctx.baseColor();
        // Already blended toward the beam colour by the effect as the charge
        // climbs — see ElderGuardianEffect. The pattern only ever sees two
        // colours, which is what PatternContext carries.
        RGBColor eye = ctx.resolvedAccentColor();

        double outer = (BODY_RADIUS_SWIMMING_KEYS
                + (BODY_RADIUS_BRISTLING_KEYS - BODY_RADIUS_SWIMMING_KEYS) * spikes) * keyWidth;
        double inner = outer - BODY_BAND_KEYS * keyWidth;
        double pupil = PUPIL_KEYS * keyWidth * (1.0 - 0.32 * charge);
        double reach = outer + SPIKE_LENGTH_KEYS * keyWidth * spikes;
        double spin = 2 * Math.PI * SPIN_HZ * seconds;
        double slot = 2 * Math.PI / SPIKES;

        // Cursed water heaves: half the rate, twice the depth.
        double causticHz = 0.19 - 0.11 * curse;
        double causticDepth = CAUSTIC_DEPTH * (1 + 1.8 * curse);
        double beamHalf = (BEAM_HALF_THICKNESS_KEYS
                + (BEAM_HALF_THICKNESS_CHARGED_KEYS - BEAM_HALF_THICKNESS_KEYS) * charge) * keyWidth;
        // Runs out of the eye to the far edge as it locks on. Squared so the
        // first second of the wind-up is a thread and the last is a bar.
        double beamReach = charge * charge * 1.2;

        LightBudget budget = new LightBudget();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double dx = key.x() - cx;
            // Two vertical distances, deliberately. The aspect-corrected one
            // is honest key widths and is what the beam is measured in; the
            // squashed one is what turns the circle into the eye, and
            // everything radial shares it — ring, spikes and pupil — so the
            // crown radiates from the oval rather than from a circle inside
            // it. Using the squashed one for the beam would quietly make it
            // half as thick as its own constants say.
            double dyFlat = (key.y() - cy) * aspect;
            double dy = dyFlat / EYE_SQUASH;
            double d = Math.hypot(dx, dy);

            // --- the water -------------------------------------------
            // Only outside the body, which occludes the water behind it. This
            // is what makes the iris an iris: it is unlit keys, and unlit
            // means nothing else may light them. An earlier version washed the
            // whole board and the eye came out as a bright dot in a teal field
            // with no structure between them — the ring, the spikes and the
            // water were all the same colour at much the same brightness, so
            // none of the three had an edge.
            //
            // Low spatial frequencies on purpose: a few radians across the
            // whole board reads as bands of light moving through water,
            // whereas the 7.3 this started at read as per-key noise — a wave
            // shorter than the key pitch is noise by definition.
            if (d > outer) {
                double caustic = CAUSTIC_BASE + causticDepth * (0.5 + 0.5 * Math.sin(
                        2 * Math.PI * causticHz * seconds + key.x() * 3.0 + key.y() * 1.4))
                        * (0.6 + 0.4 * Math.sin(
                        2 * Math.PI * causticHz * 0.61 * seconds - key.x() * 1.7));
                budget.add(key.ref(), water, caustic * glow);
            }

            // --- the beam --------------------------------------------
            // Drawn before the body so the body's own light sits on top of it
            // where they meet, which is what keeps the eye readable through a
            // full-charge beam rather than washed out by it.
            if (charge > 0.01 && Math.abs(dyFlat) <= beamHalf && Math.abs(dx) <= beamReach) {
                // Fades along its length, so it reads as coming from the eye
                // rather than as a bar that happens to cross it.
                double along = 1.0 - Math.abs(dx) / Math.max(1e-6, beamReach);
                double taper = 1.0 - Math.abs(dyFlat) / Math.max(1e-6, beamHalf);
                budget.add(key.ref(), eye, along * taper * charge * charge * 1.35 * glow);
            }

            // --- the body ring ---------------------------------------
            if (d >= inner && d <= outer) {
                // Brightest in the middle of the band, so the ring has an edge
                // on both sides instead of bleeding into the iris.
                double across = 1.0 - Math.abs(d - (inner + outer) / 2) / ((outer - inner) / 2);
                budget.add(key.ref(), water, (0.35 + 0.65 * across) * 1.6 * glow);
            }

            // --- the spikes ------------------------------------------
            if (spikes > 0.01 && d > outer && d <= reach) {
                double angle = Math.atan2(dy, dx) - spin;
                double k = angle / slot;
                double offset = Math.abs(k - Math.rint(k));
                if (offset < SPIKE_HALF_SLOT) {
                    // Thins toward the tip both ways: along the spine and
                    // across it, so a spine is a spike and not a bar.
                    double out = 1.0 - (d - outer) / Math.max(1e-6, reach - outer);
                    double across = 1.0 - offset / SPIKE_HALF_SLOT;
                    // Kept under the ring's brightness so the body still has a
                    // clear outer edge with spines attached to it, rather than
                    // the two merging into one ragged mass.
                    budget.add(key.ref(), water, out * across * 0.8 * glow);
                }
            }

            // --- the pupil -------------------------------------------
            if (d <= pupil) {
                double f = 1.0 - d / Math.max(1e-6, pupil);
                // Hotter as it locks on, which together with the pupil
                // shrinking is the eye narrowing at you.
                budget.add(key.ref(), eye, (0.55 + 0.45 * f) * (1.0 + 1.4 * charge) * glow);
            }

            // --- the beam landing ------------------------------------
            // Held under 1 deliberately. LightBudget normalises each key
            // against its own peak, so a flash that saturates every key on its
            // own takes them all to full alpha and the eye stops existing for
            // the length of it — the shape vanishing at the one moment the
            // effect is loudest. Leaving headroom lets the eye and the beam
            // still sit on top of the glare.
            if (flash > 0.001) {
                budget.add(key.ref(), eye, flash * 0.8);
            }
        }
        return budget.resolve();
    }
}
