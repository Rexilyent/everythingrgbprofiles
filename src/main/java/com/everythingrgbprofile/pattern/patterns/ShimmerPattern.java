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
 * The default Tier 1 ambient pattern, and the thing your keyboard is doing
 * 95% of the time. A slow, subtle, per-key <b>desynchronised</b>
 * brightness wobble, so a zone looks alive rather than like one big
 * synchronised lamp.
 *
 * <pre>
 * brightness_k(t) = floor + (1 - floor) * (0.5 + 0.5 * sin(2*pi*frequencyHz*jitter_k*t + phase_k))
 * </pre>
 *
 * <h2>The two ways to get this wrong</h2>
 * <b>1. Synchronise it.</b> Every key sharing a phase turns "a living swamp"
 * into "the whole keyboard is breathing at you". Fine for a warning (see
 * {@link PulsePattern}, which does exactly that on purpose), completely wrong
 * for wallpaper.
 *
 * <p><b>2. Re-randomise per frame.</b> Extremely tempting — it's one line and
 * it removes all this state. It also stops being a shimmer, because a key
 * picking a fresh random brightness 60 times a second is, mathematically, TV
 * static. The phases are rolled ONCE in {@link #initIfNeeded} and then held
 * for the pattern's life.
 *
 * <p>Note both jitter and phase are randomised. Phase alone would leave every
 * key at the same frequency, and identical-frequency oscillators that started
 * at different times still trace out one visible travelling wave. The ±15%
 * frequency jitter keeps them permanently drifting apart, so the shimmer never
 * settles into a pattern your eye can lock onto.
 */
public final class ShimmerPattern implements Pattern {

    private final double frequencyHz;
    private final double floor;
    private final Map<KeyGrid.LedRef, Double> phaseByKey = new HashMap<>();
    private final Map<KeyGrid.LedRef, Double> jitterByKey = new HashMap<>();
    private boolean initialized = false;

    /**
     * 0.20Hz — one cycle per five seconds — and a 0.30 floor, so it dims to
     * 30% rather than going dark. Both deliberately understated: this runs
     * under everything else all the time, and anything more energetic stops
     * being ambient and starts being distracting.
     */
    public ShimmerPattern() {
        this(0.20, 0.30);
    }

    public ShimmerPattern(double frequencyHz, double floor) {
        this.frequencyHz = frequencyHz;
        this.floor = floor;
    }

    /**
     * Rolls each key's phase and frequency jitter, once, on the first frame.
     *
     * <p>Lazily rather than in the constructor because the key set comes from
     * the context, which doesn't exist yet at construction time. Runs on the
     * SDK worker thread only, so the unsynchronised {@code initialized} flag
     * is fine — single thread, no race.
     */
    private void initIfNeeded(PatternContext ctx) {
        if (initialized) return;
        Random random = new Random();
        for (KeyGrid.LedRef key : ctx.effectiveTargetKeys()) {
            phaseByKey.put(key, random.nextDouble() * 2 * Math.PI);
            jitterByKey.put(key, 0.85 + random.nextDouble() * 0.30); // 0.85–1.15, i.e. ±15%
        }
        initialized = true;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        initIfNeeded(ctx);
        double t = elapsedMillis / 1000.0;
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedRef key : ctx.effectiveTargetKeys()) {
            // computeIfAbsent, not get(). Real bug this fixed: plug in a
            // second Corsair device mid-session and the key set grows, but
            // this instance was seeded from the OLD set. The previous get()
            // returned a null Double, which auto-unboxed straight into a
            // NullPointerException on the next line — one hotplug, dead
            // render loop.
            //
            // Now a new key just gets its phase rolled on first sight and
            // joins the shimmer. Math.random() rather than the seeded Random
            // because that one is a local in initIfNeeded and this path is a
            // rare one-off, so it isn't worth keeping a field alive for.
            double phase = phaseByKey.computeIfAbsent(key, k -> Math.random() * 2 * Math.PI);
            double jitter = jitterByKey.computeIfAbsent(key, k -> 0.85 + Math.random() * 0.30);
            double brightness = floor + (1.0 - floor) * (0.5 + 0.5 * Math.sin(2 * Math.PI * frequencyHz * jitter * t + phase));
            out.put(key, new LayerPixel(ctx.baseColor(), brightness));
        }
        return out;
    }
}
