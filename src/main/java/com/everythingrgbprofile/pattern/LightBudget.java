package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates light from several sources onto the same keys, then resolves it
 * into one {@link LayerPixel} per key.
 *
 * <p>For patterns built from more than one layer. The obvious approach —
 * render each layer and {@code put()} the results into a shared map — is
 * opaque replacement, so whichever layer runs last wins the key outright
 * whether or not it was the brighter thing. A spark crossing a torch pool
 * comes out no brighter than a spark in the dark, and a dim twinkle's fade-out
 * frames actively punch a hole in the layer underneath.
 *
 * <p>Here every source instead <b>adds</b> its contribution, in linear 0..1
 * units per channel, and the total is normalised once at the end.
 *
 * <h2>Why it normalises rather than clips</h2>
 * A key can easily be handed more light than an LED can emit. Clipping each
 * channel independently at 1.0 changes the hue: a hot orange
 * {@code (1.4, 0.6, 0.2)} clips to {@code (1.0, 0.6, 0.2)}, which is a
 * <i>different, yellower</i> colour, and the brighter the fire gets the more
 * it slides toward white. Dividing all three channels by the peak preserves
 * the ratio between them, so an over-bright orange stays orange and simply
 * maxes out.
 *
 * <p>The split back into (colour, alpha) exists because that's what the
 * compositor consumes: colour is the light normalised to full brightness and
 * alpha carries the magnitude, so {@code colour * alpha} over black
 * reproduces the accumulated budget exactly.
 */
public final class LightBudget {

    /** Per key: {r, g, b} in linear 0..1 units, uncapped until resolve(). */
    private final Map<KeyGrid.LedRef, double[]> channels = new HashMap<>();

    /** Adds {@code intensity} worth of {@code color} to one key. Zero and negative are no-ops. */
    public void add(KeyGrid.LedRef ref, RGBColor color, double intensity) {
        if (ref == null || intensity <= 0) return;
        double[] c = channels.computeIfAbsent(ref, k -> new double[3]);
        c[0] += color.r() / 255.0 * intensity;
        c[1] += color.g() / 255.0 * intensity;
        c[2] += color.b() / 255.0 * intensity;
    }

    /** Adds a whole layer's output, scaled. Convenient for "this sub-pattern, at 40%". */
    public void addLayer(Map<KeyGrid.LedRef, LayerPixel> layer, double scale) {
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> entry : layer.entrySet()) {
            LayerPixel pixel = entry.getValue();
            add(entry.getKey(), pixel.color(), pixel.alpha() * scale);
        }
    }

    /** The accumulated light as pixels. Keys that received nothing are omitted. */
    public Map<KeyGrid.LedRef, LayerPixel> resolve() {
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(channels.size());
        for (Map.Entry<KeyGrid.LedRef, double[]> entry : channels.entrySet()) {
            double[] c = entry.getValue();
            double peak = Math.max(c[0], Math.max(c[1], c[2]));
            if (peak <= 0.0001) continue;
            double scale = peak > 1.0 ? 1.0 / peak : 1.0;
            double alpha = Math.min(1.0, peak);
            RGBColor color = new RGBColor(
                    (int) Math.round(c[0] * scale / alpha * 255),
                    (int) Math.round(c[1] * scale / alpha * 255),
                    (int) Math.round(c[2] * scale / alpha * 255));
            out.put(entry.getKey(), new LayerPixel(color, alpha));
        }
        return out;
    }
}
