package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * A heartbeat for low health. Each beat is a lub-dub: the middle of the board
 * flushes red and a ring pushes out from it toward the edges, the dub a
 * smaller echo of the lub a moment later.
 *
 * <p>One level in, 0..1, and everything about the beat hangs off it: how fast
 * it comes, how bright it is, and how far each ring gets before it dies. Just
 * under the health threshold that's a slow, faint thump that barely leaves the
 * middle. At half a heart it's pounding and every ring reaches the edges.
 * Above the threshold there is no beat at all, which the event layer handles
 * by sending 0, because a heartbeat you can see at full health is a
 * screensaver.
 *
 * <h2>Why from the middle</h2>
 * Everything else that warns you about dying already owns a direction. Fire
 * and water rise from the bottom, and frost and heatstroke close in from the
 * edges. Heatstroke is red too, so if this were a red vignette as well, the
 * two would be the same thing with different labels. Starting in the middle
 * and pushing out is the one direction nobody's using, and it happens to be
 * what a pulse looks like.
 *
 * <h2>Why the rings are remembered instead of computed</h2>
 * Most patterns in here are pure functions of time. This one can't be, because
 * the beat rate moves with your health. Work out "where is the ring now" from
 * {@code rate * time} and every change in rate teleports every ring in flight
 * to wherever the new rate says it should be. So each beat is stamped with the
 * moment it started and how far it's allowed to travel, and then it just
 * travels.
 */
public final class HeartbeatPattern implements Pattern {

    /** Beats per minute just under the threshold, and at nearly dead. A resting heart, then a panicking one. */
    private static final double SLOW_BPM = 62;
    private static final double FAST_BPM = 140;

    /** Where in the cycle the dub lands, and how big it is next to the lub. */
    private static final double DUB_AT = 0.2;
    private static final double DUB_STRENGTH = 0.6;

    /** How fast a ring travels, in board heights per second. */
    private static final double RING_SPEED = 2.4;
    /** Half-width of a ring, in board heights. Roughly one row each side of the crest. */
    private static final double RING_WIDTH = 0.16;
    /** How far a ring gets before it's gone, at the faintest and strongest levels, in board heights. */
    private static final double SHORT_REACH = 0.55;
    private static final double LONG_REACH = 2.1;

    /** Radius of the flush in the middle, in board heights, and how quickly it fades after each beat. */
    private static final double CORE_RADIUS = 0.4;
    private static final double CORE_DECAY_PER_SECOND = 7.0;

    /** Peak opacity at the faintest and strongest levels. */
    private static final double FAINT_ALPHA = 0.45;
    private static final double STRONG_ALPHA = 1.0;

    /** How quickly the level follows the target. Fast either way, health is not a slow stat. */
    private static final double LEVEL_TAU_SECONDS = 0.4;

    /** At 140 bpm with a 2.1 reach, a ring lives under a second, so this many lub-dubs can overlap. */
    private static final int MAX_BEATS = 8;

    private volatile double targetLevel = 0;
    private double level = 0;
    private double lastElapsedMillis = 0;
    private double cyclePhase = 0;
    private boolean initialised = false;

    // A small ring buffer of beats in flight: when each started, how strong it
    // was, and how far it's allowed to get.
    private final double[] beatStart = new double[MAX_BEATS];
    private final double[] beatStrength = new double[MAX_BEATS];
    private final double[] beatReach = new double[MAX_BEATS];
    private int nextBeat = 0;
    private boolean dubDone = false;

    /** 0 = no heartbeat, 1 = nearly dead. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /** Stopped dead, mid-beat, nothing allowed to finish. For death and world unload. */
    public void stop() {
        this.targetLevel = 0;
        this.initialised = false;
    }

    /**
     * Whether there's anything to draw. Rings already in flight are allowed
     * to finish after you heal, so healing up reads as the beat settling
     * rather than a light switch.
     */
    public boolean visible() {
        if (targetLevel > 0.001) return true;
        if (!initialised) return false;
        for (int i = 0; i < MAX_BEATS; i++) {
            if (beatStrength[i] > 0 && (lastElapsedMillis / 1000.0 - beatStart[i]) * RING_SPEED < beatReach[i]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            // Snapped to the target rather than eased up from nothing, or the
            // first thump comes out at a tenth of its strength.
            level = targetLevel;
            lastElapsedMillis = elapsedMillis;
            // Starts one tick before a lub, so dropping under the threshold
            // thumps straight away instead of waiting most of a second for
            // the first beat to come round.
            cyclePhase = 0.999;
            dubDone = true;
            for (int i = 0; i < MAX_BEATS; i++) beatStrength[i] = 0;
            initialised = true;
        }
        double now = elapsedMillis / 1000.0;
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        if (dt > 0) level += (targetLevel - level) * (1 - Math.exp(-dt / LEVEL_TAU_SECONDS));

        // New beats only while there's a level to beat at. Healing above the
        // threshold stops the next lub from coming, and whatever's already
        // travelling carries on to the edge.
        if (targetLevel > 0.001 && level > 0.005) {
            double bpm = SLOW_BPM + (FAST_BPM - SLOW_BPM) * level;
            cyclePhase += dt * bpm / 60.0;
            if (cyclePhase >= 1.0) {
                cyclePhase -= Math.floor(cyclePhase);
                beat(now, 1.0);
                dubDone = false;
            }
            if (!dubDone && cyclePhase >= DUB_AT) {
                beat(now, DUB_STRENGTH);
                dubDone = true;
            }
        }

        double aspect = ctx.grid().aspectRatio();
        if (aspect <= 0) aspect = 1.0;
        double peakAlpha = FAINT_ALPHA + (STRONG_ALPHA - FAINT_ALPHA) * level;
        RGBColor base = ctx.baseColor();
        RGBColor crest = ctx.resolvedAccentColor();

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            // Distance from the middle in board heights, with the x term
            // stretched back to its real width. Without that the ring is a
            // circle in normalised space, which on a board this shape is a
            // flat ellipse that hits the top and bottom rows long before it
            // gets anywhere near the sides.
            double dist = Math.hypot((key.x() - 0.5) / aspect, key.y() - 0.5);

            double strength = 0;
            for (int i = 0; i < MAX_BEATS; i++) {
                if (beatStrength[i] <= 0) continue;
                double age = now - beatStart[i];
                double radius = age * RING_SPEED;
                double reach = beatReach[i];
                if (radius > reach + RING_WIDTH) continue;
                // Holds most of its strength for most of the trip and falls
                // away near the end, so it thins out at its reach instead of
                // stopping dead in a hard line. A plain linear-squared fade
                // looked the same on paper and in practice spent the ring
                // before it got anywhere: at near-dead, Esc and the numpad
                // never saw a single beat.
                double travelled = radius / reach;
                double life = Math.max(0, 1 - travelled * travelled);
                double offset = (dist - radius) / RING_WIDTH;
                double ring = Math.exp(-offset * offset) * life;
                double core = dist < CORE_RADIUS
                        ? Math.exp(-age * CORE_DECAY_PER_SECOND) * (1 - dist / CORE_RADIUS)
                        : 0;
                strength = Math.max(strength, beatStrength[i] * Math.max(ring, core));
            }
            if (strength < 0.03) continue;

            // Brighter, paler crest at the peak of the beat, the base red
            // through the rest of it.
            RGBColor color = base.lerp(crest, strength * 0.5);
            out.put(key.ref(), new LayerPixel(color, peakAlpha * Math.min(1.0, strength)));
        }
        return out;
    }

    private void beat(double now, double strength) {
        beatStart[nextBeat] = now;
        beatStrength[nextBeat] = strength;
        beatReach[nextBeat] = (SHORT_REACH + (LONG_REACH - SHORT_REACH) * level) * (0.7 + 0.3 * strength);
        nextBeat = (nextBeat + 1) % MAX_BEATS;
    }
}
