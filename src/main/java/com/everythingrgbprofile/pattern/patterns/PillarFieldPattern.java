package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorRamp;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A full-board, per-key phase-offset oscillation that walks up and down a
 * {@link ColorRamp}. The second of three attempts at the Terraria Celestial
 * Pillar look for the portal; superseded by {@link CoreEmitterPattern}, and no
 * longer used by any effect.
 *
 * <h2>Where it came from</h2>
 * The first portal pattern, {@link SpiralInPattern}, drew comets spiralling in
 * across an otherwise dark board. Measuring the reference footage frame by
 * frame showed that was wrong on the facts:
 *
 * <ul>
 *   <li><b>The board is fully lit. Always.</b> 77–94% of key pixels sit above
 *       25% brightness in <i>every frame of all four pillars</i>. There is no
 *       dark board and no sparse point on black.</li>
 *   <li><b>All four cycle at 71 frames</b> (1.42s at 20ms/frame, ~0.70Hz).
 *       Identical timing across every pillar; only the palette differs.</li>
 *   <li><b>Frame-to-frame change is 1.2–3.9 / 255.</b> Gentle. This is a
 *       slow glow, not a light show.</li>
 * </ul>
 *
 * <p>Those three findings still stand. This class also rested on a fourth —
 * that nothing travels, because correlating each frame's column-brightness
 * profile against the previous frame gave a dominant horizontal shift of
 * exactly zero — and concluded every key must oscillate in place on its own
 * randomly rolled phase.
 *
 * <h2>Why it was superseded</h2>
 * The zero-shift measurement was correct but answered the wrong question. A
 * spiral rotating about a fixed centre has zero <i>net horizontal</i>
 * displacement by construction, so a horizontal test cannot see it. Testing
 * radially and angularly instead showed the per-key phases are not random at
 * all: they are a one-armed Archimedean spiral around a static dark core. That
 * fit is {@link CoreEmitterPattern}, which reproduces the reference at r = 0.992
 * where this class's random phases cannot.
 *
 * <p>It is kept because a desynchronised shimmer walking a multi-stop ramp is
 * a useful look in its own right, and as a record of why the portal pattern
 * is shaped the way it is. Per-key phases are rolled once and kept — never
 * re-randomised per frame — which is what produces a desynchronised field
 * instead of a board pulsing in unison.
 */
public final class PillarFieldPattern implements Pattern {

    /** 71 frames at 20ms, straight off the reference footage. ~0.70Hz. */
    public static final double REFERENCE_HZ = 1000.0 / (71 * 20.0);

    /**
     * How far down the ramp the field is allowed to go.
     *
     * <p>Tuned to the measured 5th-percentile brightness. Crucially it is NOT
     * zero: the references never actually sit at their darkest stop — measured
     * p5 is 35/255, not 0. Letting keys reach the true ramp floor would drop
     * board coverage out of the measured 77–94% band and reintroduce exactly
     * the sparse-dark-board look this pattern exists to get rid of.
     */
    private static final double RAMP_FLOOR = 0.16;

    private final ColorRamp ramp;
    private final double frequencyHz;

    /** Per-key frequency spread. At 0 the whole board breathes as one, which looks wrong. */
    private final double jitterAmount;

    private final Map<KeyGrid.LedRef, Double> phaseByKey = new HashMap<>();
    private final Map<KeyGrid.LedRef, Double> jitterByKey = new HashMap<>();
    private final Random random = new Random();

    /** 0..1, driven by the owning effect's envelope (charge ramp / arrival hold). */
    private volatile double intensity = 1.0;

    /** Reference-accurate defaults: measured frequency, ±12.5% jitter. */
    public PillarFieldPattern(ColorRamp ramp) {
        this(ramp, REFERENCE_HZ, 0.25);
    }

    public PillarFieldPattern(ColorRamp ramp, double frequencyHz, double jitterAmount) {
        this.ramp = ramp;
        this.frequencyHz = frequencyHz;
        this.jitterAmount = jitterAmount;
    }

    public void setIntensity(double intensity) {
        this.intensity = Math.max(0.0, Math.min(1.0, intensity));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();

        for (KeyGrid.LedRef key : ctx.effectiveTargetKeys()) {
            // Lazy per-key seeding via computeIfAbsent — same hotplug-safety
            // reasoning as ShimmerPattern, and it means no init pass is needed.
            double phase = phaseByKey.computeIfAbsent(key, k -> random.nextDouble() * 2 * Math.PI);
            double jitter = jitterByKey.computeIfAbsent(key,
                    k -> 1.0 - jitterAmount / 2 + random.nextDouble() * jitterAmount);

            // Position along the ramp for this key, this frame.
            // raw is the plain 0..1 sine; wave compresses it into
            // RAMP_FLOOR..1 so the field rides the upper portion of the ramp
            // and never bottoms out. See RAMP_FLOOR above.
            double raw = 0.5 + 0.5 * Math.sin(2 * Math.PI * frequencyHz * jitter * t + phase);
            double wave = RAMP_FLOOR + (1.0 - RAMP_FLOOR) * raw;

            // NOTE: intensity multiplies the RAMP POSITION, not the output
            // brightness. This is deliberate and it's the good bit.
            //
            // Scaling the final colour would dim the whole board uniformly —
            // a bright portal getting darker, like someone turning down a
            // dimmer switch. Scaling the ramp position instead compresses the
            // field toward the ramp's dark end, so a rising portal reads as
            // the gradient IGNITING FROM ITS EMBERS UPWARD: deep purple
            // through red through amber, colours arriving as it builds.
            //
            // That's how the references build, and it's why the portal
            // charge-up feels like something powering on rather than
            // something merely getting brighter.
            RGBColor color = ramp.sample(wave * intensity);
            // alpha 1.0: the field is opaque. The owning effect handles
            // fading via its own layer alpha, so this pattern never has to
            // think about what's underneath it.
            out.put(key, new LayerPixel(color, 1.0));
        }
        return out;
    }
}
