package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Something creeping in from the edges of the board, further the worse it
 * gets, for Tough As Nails' freezing and overheating. The keyboard version of
 * the screen frosting over from the corners, or the edges of your vision going
 * red while you cook.
 *
 * <p>Same family as {@link WaterRisePattern} and {@link FlameRisePattern}, one
 * level in and the board fills accordingly, except this one fills from all
 * four sides at once instead of from the bottom. Level 0 is nothing, a small
 * level is just the outermost ring of keys, and level 1 closes all the way
 * over the middle. The biome shows through whatever's left uncovered, so how
 * much of it you can still see IS the meter.
 *
 * <h2>Edge distance, measured like a human would</h2>
 * Distance from the nearest edge, in board heights, which means the x term is
 * divided by {@link KeyGrid#aspectRatio()}. Skip that and normalised
 * coordinates claim the board is square, the frost eats a third of the width
 * for every third of the height, and the left and right sides close in about
 * three times faster than the top and bottom. With it, the ring creeps in at
 * the same physical speed on every side and the last thing to go is a strip
 * through the middle row, which is where a vignette on a wide board ends up.
 *
 * <h2>Two personalities, one engine</h2>
 * The geometry is identical and the temperament is opposite.
 *
 * <p><b>Frost</b> grows and then holds still. Its front is jagged, every key
 * getting its own fixed nudge so ice reaches out in uneven fingers, and none
 * of that moves with time, because frost on a window doesn't wiggle. Advancing
 * means more keys freezing over one at a time. It's translucent at the front
 * (pale blue over anything reads as frosted) and the occasional key glints.
 *
 * <p><b>Heat</b> never holds still. Its front ripples around the ring like air
 * over a road, and the whole thing throbs, slowly at first and faster as the
 * heatstroke builds. It's drawn opaque, for the same reason the flames are:
 * orange blended into a green biome comes out olive, which reads as nothing.
 * So the front softens by dimming instead of going see-through.
 *
 * <h2>Why the level is eased here</h2>
 * The poll only gets round to TAN about once a second, so the level arrives
 * in steps. Easing toward it in the render loop turns those steps into a
 * creep, which is the whole point. Retreating is slower than advancing, so
 * warming up or cooling down visibly thaws rather than snapping back.
 */
public final class EdgeCreepPattern implements Pattern {

    public enum Kind { FROST, HEAT }

    /** At level 1, how far in the front reaches, in board heights. Past 0.5 so the jagged front still closes over the middle. */
    private static final double FULL_REACH = 0.58;

    private static final double RISE_TAU_SECONDS = 0.9;
    private static final double FALL_TAU_SECONDS = 1.6;

    /** How far behind the front a key has to be before it counts as fully covered, in board heights. */
    private static final double FRONT_BAND = 0.14;

    /** How ragged the frost line is, in board heights, either side of the true front. */
    private static final double FROST_JAG = 0.07;
    /** Coverage right at the frost line. Inside it climbs to fully opaque. */
    private static final double FROST_FRONT_ALPHA = 0.35;

    /** How far the heat line ripples either side of the true front, in board heights. */
    private static final double HEAT_RIPPLE = 0.05;
    /** Brightness right at the heat line, so it fades out by dimming rather than going see-through. */
    private static final double HEAT_FRONT_BRIGHTNESS = 0.45;
    /** Throb rate at the first sign of overheating and at full heatstroke, in Hz. */
    private static final double THROB_SLOW_HZ = 0.45;
    private static final double THROB_FAST_HZ = 1.6;

    private final Kind kind;

    private volatile double targetLevel = 0;
    private double displayedLevel = 0;
    private double lastElapsedMillis = 0;
    private double throbPhase = 0;
    private boolean initialised = false;

    private EdgeCreepPattern(Kind kind) {
        this.kind = kind;
    }

    public static EdgeCreepPattern frost() {
        return new EdgeCreepPattern(Kind.FROST);
    }

    public static EdgeCreepPattern heat() {
        return new EdgeCreepPattern(Kind.HEAT);
    }

    /** 0 = nothing, 1 = closed over the whole board. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /** Gone at once, no thawing: for a world going away, when there's nothing to thaw into. */
    public void clear() {
        this.targetLevel = 0;
        this.initialised = false;
    }

    /** Whether there's still anything to draw, so the edge can retreat after the danger's over. */
    public boolean visible() {
        return targetLevel > 0.001 || (initialised && displayedLevel > 0.005);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            // Snapped to nothing rather than to the target, so it creeps in
            // from the edge instead of appearing already halfway across.
            displayedLevel = 0;
            lastElapsedMillis = elapsedMillis;
            throbPhase = 0;
            initialised = true;
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        if (dt > 0) {
            double tau = targetLevel > displayedLevel ? RISE_TAU_SECONDS : FALL_TAU_SECONDS;
            displayedLevel += (targetLevel - displayedLevel) * (1 - Math.exp(-dt / tau));
        }
        double level = displayedLevel;
        if (level <= 0.005) return Map.of();

        double seconds = elapsedMillis / 1000.0;
        double aspect = ctx.grid().aspectRatio();
        if (aspect <= 0) aspect = 1.0;
        double reach = level * FULL_REACH;

        // Accumulated rather than computed as rate * time. The rate changes
        // with the level, and sin(2pi * rate * t) with a moving rate leaps
        // around the cycle every time the rate moves, which reads as a stutter.
        if (kind == Kind.HEAT) {
            double hz = THROB_SLOW_HZ + (THROB_FAST_HZ - THROB_SLOW_HZ) * level;
            throbPhase = (throbPhase + hz * dt) % 1.0;
        }

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double x = key.x();
            double y = key.y();
            double edge = Math.min(Math.min(x, 1 - x) / aspect, Math.min(y, 1 - y));

            LayerPixel px = kind == Kind.FROST
                    ? frost(ctx, key, x, y, edge, reach, seconds)
                    : heat(ctx, x, y, edge, reach, level, seconds, aspect);
            if (px != null) out.put(key.ref(), px);
        }
        return out;
    }

    /**
     * Ice blue at the front, nearly white at the rim where it's been frozen
     * longest. Base colour is the front, accent is the rim.
     */
    private LayerPixel frost(PatternContext ctx, KeyGrid.LedPosition key, double x, double y,
                             double edge, double reach, double seconds) {
        // Mostly per key so neighbours disagree, partly a slow spatial wave so
        // the disagreement clumps into fingers instead of salt and pepper.
        // Neither term has time in it. Frost doesn't move once it's there.
        double jag = 0.6 * (hash(key.ref(), 0) * 2 - 1)
                + 0.4 * Math.sin(2 * Math.PI * (x * 5.3 + y * 2.1));
        double depth = reach + FROST_JAG * jag - edge;
        if (depth < 0) return null;

        double cover = smooth(depth / FRONT_BAND);
        // 1 at the very edge of the board, 0 at the front: how long it's been frozen.
        double age = reach <= 0 ? 0 : Math.max(0, Math.min(1, 1 - edge / reach));
        RGBColor color = ctx.baseColor().lerp(ctx.resolvedAccentColor(), age);

        // Glints. Each key gets its own slow clock and catches the light for a
        // sliver of each turn, the pow() being what turns a sine into a sliver.
        // Only on keys properly frozen over, never on the thin front.
        double rate = 0.12 + 0.2 * hash(key.ref(), 1);
        double glint = Math.pow(Math.max(0, Math.sin(2 * Math.PI * (seconds * rate + hash(key.ref(), 2)))), 40);
        if (glint > 0.01 && cover > 0.8) color = color.lerp(RGBColor.WHITE, glint * 0.8);

        double alpha = FROST_FRONT_ALPHA + (1 - FROST_FRONT_ALPHA) * cover;
        return new LayerPixel(color, alpha);
    }

    /**
     * Orange at the front, deep red at the rim. Base colour is the front,
     * accent is the rim. Opaque throughout; see the class notes.
     */
    private LayerPixel heat(PatternContext ctx, double x, double y, double edge,
                            double reach, double level, double seconds, double aspect) {
        // Position around the ring, 0..1, so the ripple travels round it
        // rather than sloshing left to right. Angle from the centre with the
        // same aspect fix as the distance, or the corners would bunch up.
        double around = (Math.atan2(y - 0.5, (x - 0.5) / aspect) / (2 * Math.PI)) + 0.5;
        double ripple = 0.6 * Math.sin(2 * Math.PI * (around * 5 + seconds * 0.5))
                + 0.4 * Math.sin(2 * Math.PI * (around * 11 - seconds * 1.1) + 1.0);
        double depth = reach + HEAT_RIPPLE * ripple - edge;
        if (depth < 0) return null;

        double cover = smooth(depth / FRONT_BAND);
        double age = reach <= 0 ? 0 : Math.max(0, Math.min(1, 1 - edge / reach));
        RGBColor color = ctx.baseColor().lerp(ctx.resolvedAccentColor(), age);

        // The throb gets deeper as well as faster, so a mild overheat is a
        // slow warm pulse and full heatstroke is a pounding one.
        double throbDepth = 0.15 + 0.25 * level;
        double throb = 1 - throbDepth * (0.5 + 0.5 * Math.sin(2 * Math.PI * throbPhase));
        double brightness = (HEAT_FRONT_BRIGHTNESS + (1 - HEAT_FRONT_BRIGHTNESS) * cover) * throb;
        return LayerPixel.opaque(color.scaled(brightness));
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Stable 0..1 per key, with a salt so one key can have several unrelated values. */
    private static double hash(KeyGrid.LedRef ref, int salt) {
        long h = (ref.luid() * 2654435761L) ^ (ref.deviceId() == null ? 0 : ref.deviceId().hashCode()) ^ (salt * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 15);
        h *= 0x2C1B3C6DL;
        h ^= (h >>> 12);
        return (h & 0xFFFF) / 65536.0;
    }
}
