package com.everythingrgbprofile.pattern.patterns;

import java.util.HashMap;
import java.util.Map;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * The pulse-slow / pulse-medium / pulse-fast family. Every targeted key
 * breathes in perfect unison.
 *
 * <pre>brightness(t) = floor + (1 - floor) * (0.5 + 0.5 * sin(2*pi*frequencyHz*t))</pre>
 *
 * <h2>Why synchronised, when shimmer is deliberately not</h2>
 * Because they're saying different things. Shimmer is ambient texture — "you
 * are in a swamp" — and desynchronised keys are what make it feel alive and
 * organic rather than mechanical.
 *
 * <p>Pulse is a warning, and warnings have to be parsed in peripheral vision
 * in under a second. A unified flash does that instantly. A shimmering blob
 * does not.
 *
 * <p>If your health is critical you should not have to <i>study</i> your
 * keyboard to find that out.
 */
public final class PulsePattern implements Pattern {

    /**
     * Frequency and floor per speed. Note the floor differs and that's on
     * purpose.
     *
     * <p>SLOW keeps a 0.10 floor so that it never fully extinguishes. It gets
     * used for ambient "be aware" states, and a light going completely dark
     * every couple of seconds reads as a hardware fault rather than as a
     * mood.
     *
     * <p>MEDIUM and FAST go to a true zero, because they are signalling
     * urgency and the hard blackout between beats is the thing that makes them
     * feel urgent. 3Hz to full black is genuinely difficult to ignore, which
     * is the entire reason it is set that way.
     */
    public enum Speed {
        SLOW(0.4, 0.10),
        MEDIUM(1.0, 0.0),
        FAST(3.0, 0.0);

        final double frequencyHz;
        final double floor;

        Speed(double frequencyHz, double floor) {
            this.frequencyHz = frequencyHz;
            this.floor = floor;
        }
    }

    private final double frequencyHz;
    private final double floor;

    public PulsePattern(Speed speed) {
        this(speed.frequencyHz, speed.floor);
    }

    /** Escape hatch for callers that need a frequency the enum doesn't offer. */
    public PulsePattern(double frequencyHz, double floor) {
        this.frequencyHz = frequencyHz;
        this.floor = floor;
    }

    /** Unrecognised names get MEDIUM rather than an exception. Config files lie. */
    public static PulsePattern fromConfigName(String name) {
        return switch (name.toLowerCase()) {
            case "pulse-slow" -> new PulsePattern(Speed.SLOW);
            case "pulse-fast" -> new PulsePattern(Speed.FAST);
            default -> new PulsePattern(Speed.MEDIUM);
        };
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        // sin gives -1..1; the 0.5 + 0.5* remaps it to 0..1, then the floor
        // term compresses that into floor..1. Standard trick, three operations,
        // no branches.
        double brightness = floor + (1.0 - floor) * (0.5 + 0.5 * Math.sin(2 * Math.PI * frequencyHz * t));
        // One shared pixel — the synchronisation is literally the fact that
        // every key gets the same object.
        LayerPixel pixel = new LayerPixel(ctx.baseColor(), brightness);
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedRef key : ctx.effectiveTargetKeys()) {
            out.put(key, pixel);
        }
        return out;
    }
}
