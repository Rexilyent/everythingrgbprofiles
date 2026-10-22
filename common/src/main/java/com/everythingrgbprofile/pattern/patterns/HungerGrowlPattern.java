package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * An empty stomach, as a pit in the bottom of the keyboard. There's a hollow
 * low down in the middle where the biome has just gone, and every so often it
 * growls: the pit gulps wider and a few ripples of nothing roll out of it,
 * carving the biome away as they pass and letting it grow back behind them.
 * The keyboard is so hungry it's eating itself. Relatable, honestly.
 *
 * <p>Same family as the Bumblezone portal ({@link CoreEmitterPattern} in
 * {@code RING} mode), which is a dark core with rings pushing out of it, but
 * turned inside out. The portal's rings are light and its core is the only
 * thing that's dark. Here the rings ARE the dark, so what they draw is the
 * absence of whatever was underneath.
 *
 * <p>One level in, 0..1, same contract as {@link HeartbeatPattern}. Just under
 * the hunger threshold the pit is a key or so wide and the ripples die out
 * before they've gone far. On an empty food bar the pit has swallowed a good
 * chunk of the bottom rows and every growl carves the whole board. Above the
 * threshold there's nothing, which the event layer handles by sending 0.
 *
 * <h2>Why the pit sits low</h2>
 * The heartbeat already pushes rings out of the dead middle of the board, and
 * a second set of rings from the same spot would just be the heartbeat with
 * the colours swapped. Down between the bottom letter row and the space bar is
 * free, and it's also roughly where a stomach would be if keyboards had them.
 * From there the ripples spread up and sideways as arcs, which doesn't look
 * like anything else in the mod.
 *
 * <h2>Why each ripple has an amber lip</h2>
 * Carving only works if there's something to carve. At night, underground or
 * in the Nether the biome underneath is dark already, and a black ring over a
 * nearly-black board is nothing at all. So every ripple leads with a thin rim
 * of the hunger amber, and that rim is what you actually spot in peripheral
 * vision. The darkness behind it is what makes it feel hollow.
 *
 * <h2>Why growls are remembered</h2>
 * Same problem the heartbeat has. The growl rate moves with the level, so
 * working out where a ripple is from {@code rate * time} would teleport every
 * ripple in flight whenever you ate half a carrot. Each one gets stamped with
 * its start time and how far it's allowed to go, and then it just goes.
 */
public final class HungerGrowlPattern implements Pattern {

    /** Where the pit sits, in normalised board coordinates. Middle across, most of the way down. */
    private static final double PIT_X = 0.5;
    private static final double PIT_Y = 0.8;

    /** Seconds between growls just under the threshold, and on an empty food bar. */
    private static final double SLOW_INTERVAL_SECONDS = 6.0;
    private static final double FAST_INTERVAL_SECONDS = 1.8;

    /** Ripples per growl, how far apart they leave, and how much weaker each one is than the last. */
    private static final int RIPPLES_PER_GROWL = 3;
    private static final double RIPPLE_GAP_SECONDS = 0.2;
    private static final double RIPPLE_FALLOFF = 0.72;

    /** Radius of the pit at rest, in board heights, at the faintest and strongest levels. A row is about a fifth. */
    private static final double SMALL_PIT = 0.13;
    private static final double LARGE_PIT = 0.42;
    /** How soft the pit's edge is, in board heights. */
    private static final double PIT_EDGE = 0.12;
    /** How much wider the pit gulps on each growl, in board heights, and how fast it settles back. */
    private static final double GULP = 0.12;
    private static final double GULP_DECAY_PER_SECOND = 3.0;

    /** How fast a ripple travels, in board heights per second. */
    private static final double RIPPLE_SPEED = 1.7;
    /** Half-width of the carved band, in board heights. Roughly a row each side. */
    private static final double RIPPLE_HALF_WIDTH = 0.13;
    /**
     * How far behind a ripple the board stays carved before it's grown back,
     * in board heights. This is the bit that makes it a carving and not just
     * a dark ring going past.
     */
    private static final double TRAIL = 0.3;
    /** Where the amber lip sits ahead of the carve, in half-widths, and how thick it is. */
    private static final double LIP_AHEAD = 1.3;
    private static final double LIP_WIDTH = 0.55;
    private static final double LIP_STRENGTH = 0.8;

    /** How far a ripple gets before it's gone, at the faintest and strongest levels, in board heights. */
    private static final double SHORT_REACH = 0.7;
    private static final double LONG_REACH = 2.3;
    /** How ragged the ripples are, in board heights. The rumble in the growl. */
    private static final double SHAKE = 0.035;

    /** Peak opacity at the faintest and strongest levels. */
    private static final double FAINT_ALPHA = 0.7;
    private static final double STRONG_ALPHA = 1.0;

    /** Hunger is a slow stat, but eating should still visibly fill the pit back in inside a second or so. */
    private static final double LEVEL_TAU_SECONDS = 0.6;

    /** A ripple lives about a second and a half at most, growls are 1.8s apart at worst, three ripples a growl. Twelve is spare room. */
    private static final int MAX_RIPPLES = 12;

    private volatile double targetLevel = 0;
    private double level = 0;
    private double lastElapsedMillis = 0;
    private double cyclePhase = 0;
    private double lastGrowl = Double.NEGATIVE_INFINITY;
    private boolean initialised = false;

    private final double[] rippleStart = new double[MAX_RIPPLES];
    private final double[] rippleStrength = new double[MAX_RIPPLES];
    private final double[] rippleReach = new double[MAX_RIPPLES];
    private final double[] rippleOrigin = new double[MAX_RIPPLES];
    private int nextRipple = 0;

    /** 0 = fed, 1 = food bar empty. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /** Filled in on the spot, ripples and all. For death and world unload. */
    public void stop() {
        this.targetLevel = 0;
        this.initialised = false;
    }

    /**
     * Whether there's anything to draw. After you eat, the pit fills back in
     * over a moment and anything already rolling gets to finish, so eating
     * reads as the hole closing rather than a light switch.
     */
    public boolean visible() {
        if (targetLevel > 0.001) return true;
        if (!initialised) return false;
        if (level > 0.005) return true;
        double now = lastElapsedMillis / 1000.0;
        for (int i = 0; i < MAX_RIPPLES; i++) {
            if (rippleStrength[i] > 0 && (now - rippleStart[i]) * RIPPLE_SPEED < rippleReach[i] + TRAIL) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            // Snapped to the target, so the pit is already there the moment
            // you drop under the threshold and the first growl isn't wasted
            // on a pit that's still opening.
            level = targetLevel;
            lastElapsedMillis = elapsedMillis;
            // One tick before a growl, so going under the threshold gets an
            // answer now instead of six seconds from now.
            cyclePhase = 0.999;
            lastGrowl = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < MAX_RIPPLES; i++) rippleStrength[i] = 0;
            initialised = true;
        }
        double now = elapsedMillis / 1000.0;
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        if (dt > 0) level += (targetLevel - level) * (1 - Math.exp(-dt / LEVEL_TAU_SECONDS));

        if (targetLevel > 0.001 && level > 0.005) {
            double interval = SLOW_INTERVAL_SECONDS + (FAST_INTERVAL_SECONDS - SLOW_INTERVAL_SECONDS) * level;
            cyclePhase += dt / interval;
            if (cyclePhase >= 1.0) {
                cyclePhase -= Math.floor(cyclePhase);
                growl(now);
            }
        }
        if (!visible()) return Map.of();

        double aspect = ctx.grid().aspectRatio();
        if (aspect <= 0) aspect = 1.0;
        double peakAlpha = FAINT_ALPHA + (STRONG_ALPHA - FAINT_ALPHA) * level;
        // The gulp decays from the last growl, so the pit widens with each one
        // and sinks back while the ripples roll away from it. The pit's size
        // is multiplied down near zero so it shrinks shut after you eat,
        // instead of sitting there at SMALL_PIT until the level is all the
        // way gone.
        double gulp = GULP * Math.exp(-Math.max(0, now - lastGrowl) * GULP_DECAY_PER_SECOND);
        double pit = (SMALL_PIT + (LARGE_PIT - SMALL_PIT) * level + gulp) * Math.min(1, level / 0.15);
        RGBColor lipColor = ctx.baseColor();
        RGBColor hollow = ctx.resolvedAccentColor();

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            // In board heights, with x stretched back to its real width so the
            // pit is round and the ripples are circles rather than flat
            // ellipses that hit the bottom row long before the sides.
            double dx = (key.x() - PIT_X) / aspect;
            double dy = key.y() - PIT_Y;
            double dist = Math.hypot(dx, dy);
            double angle = Math.atan2(dy, dx);

            // The pit itself: solid in the middle, soft at the rim, with a
            // faint amber edge so you can find it on a dark board too.
            double carve = 0;
            double lip = 0;
            if (pit > 0.01) {
                carve = smooth((pit - dist) / PIT_EDGE + 0.5);
                double rim = (dist - pit - PIT_EDGE * 0.6) / (PIT_EDGE * 0.6);
                lip = 0.35 * Math.exp(-rim * rim);
            }

            for (int i = 0; i < MAX_RIPPLES; i++) {
                if (rippleStrength[i] <= 0) continue;
                double age = now - rippleStart[i];
                if (age < 0) continue;
                double travelled = age * RIPPLE_SPEED;
                double reach = rippleReach[i];
                if (travelled > reach + TRAIL) continue;
                // Starts from the pit's rim rather than its centre, or the
                // first stretch of every ripple's life is spent inside a hole
                // that's already dark and nobody sees it.
                double radius = rippleOrigin[i] + travelled
                        + SHAKE * (Math.sin(angle * 5 + age * 9) + 0.5 * Math.sin(angle * 11 - age * 14 + 1.3));
                double life = Math.max(0, 1 - Math.pow(Math.min(1, travelled / reach), 2));
                double o = (dist - radius) / RIPPLE_HALF_WIDTH;

                // Dark at the crest, and still dark for a stretch behind it,
                // fading as the biome grows back. Nothing at all in front.
                double c;
                if (o > 0) {
                    c = Math.exp(-o * o * 2);
                } else {
                    double behind = -o * RIPPLE_HALF_WIDTH;
                    // The trail can't reach back past where the ripple started,
                    // or one that has gone a long way carves a full disc.
                    c = behind > travelled ? 0 : Math.exp(-behind / TRAIL);
                }
                double lo = (o - LIP_AHEAD) / LIP_WIDTH;
                double l = LIP_STRENGTH * Math.exp(-lo * lo);
                double s = rippleStrength[i] * life;
                carve = Math.max(carve, c * s);
                lip = Math.max(lip, l * s);
            }

            double strength = Math.max(carve, lip);
            if (strength < 0.04) continue;
            // Whichever is winning sets the colour. A key right on the leading
            // edge goes amber, one inside the carve goes to the hollow, and the
            // few in between get a muddy amber-brown for a frame, which at the
            // speed this moves just reads as the edge being soft.
            RGBColor color = hollow.lerp(lipColor, lip / (carve + lip));
            out.put(key.ref(), new LayerPixel(color, peakAlpha * Math.min(1.0, strength)));
        }
        return out;
    }

    /** One growl: the pit gulps, and a few ripples follow each other out of it, each weaker than the last. */
    private void growl(double now) {
        lastGrowl = now;
        double origin = SMALL_PIT + (LARGE_PIT - SMALL_PIT) * level;
        double reach = SHORT_REACH + (LONG_REACH - SHORT_REACH) * level;
        double strength = 1.0;
        for (int r = 0; r < RIPPLES_PER_GROWL; r++) {
            // Stamped in the future so they leave one after another. The
            // render loop skips anything with a negative age until it's due.
            rippleStart[nextRipple] = now + r * RIPPLE_GAP_SECONDS;
            rippleStrength[nextRipple] = strength;
            rippleReach[nextRipple] = reach * (1 - 0.15 * r);
            rippleOrigin[nextRipple] = origin;
            nextRipple = (nextRipple + 1) % MAX_RIPPLES;
            strength *= RIPPLE_FALLOFF;
        }
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
