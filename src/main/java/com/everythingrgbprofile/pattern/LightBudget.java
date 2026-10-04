package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.HashMap;
import java.util.Map;

/**
 * Accumulates light from several sources onto the same keys, then resolves it
 * into one {@link LayerPixel} per key.
 *
 * <p>This is for patterns built out of more than one layer. The obvious
 * approach, rendering each layer and {@code put()}-ing the results into one
 * shared map, is opaque replacement: whichever layer happens to run last wins
 * the key outright, regardless of whether it was the brighter thing.
 *
 * <p>Two consequences, both visible. A spark crossing a torch pool comes out
 * no brighter than a spark crossing the dark. And a dim twinkle's fade-out
 * frames actively punch a hole through whatever layer is underneath them.
 *
 * <p>So here every source <b>adds</b> its contribution instead, in linear 0..1
 * units per channel, and the total gets normalised exactly once at the end.
 *
 * <h2>Why it normalises instead of clipping</h2>
 * A key can very easily be handed more light than an LED is physically capable
 * of emitting. Clipping each channel independently at 1.0 changes the HUE,
 * which is the part that makes it a bug rather than a rounding detail.
 *
 * <p>A hot orange {@code (1.4, 0.6, 0.2)} clips to {@code (1.0, 0.6, 0.2)},
 * which is a <i>different and yellower</i> colour. And it gets worse the
 * brighter the fire gets, so the flames slide toward white exactly when they
 * should be at their most intensely orange.
 *
 * <p>Dividing all three channels by the peak preserves the ratio between them,
 * so an over-bright orange stays orange and simply maxes out, which is what
 * fire does in real life too.
 *
 * <p>The split back into (colour, alpha) exists because that's what the
 * compositor consumes: colour is the light normalised to full brightness and
 * alpha carries the magnitude, so {@code colour * alpha} over black
 * reproduces the accumulated budget exactly.
 */
public final class LightBudget {

    /** Per key: {r, g, b} in linear 0..1 units, deliberately uncapped until resolve(). */
    private final Map<KeyGrid.LedRef, double[]> channels = new HashMap<>();

    /** Adds {@code intensity} worth of {@code color} to one key. Zero and negative are no-ops. */
    public void add(KeyGrid.LedRef ref, RGBColor color, double intensity) {
        if (ref == null || intensity <= 0) return;
        double[] c = channels.computeIfAbsent(ref, k -> new double[3]);
        c[0] += color.r() / 255.0 * intensity;
        c[1] += color.g() / 255.0 * intensity;
        c[2] += color.b() / 255.0 * intensity;
    }

    /** Adds a whole layer's output, scaled. This is the "that sub-pattern, but at 40%" call. */
    public void addLayer(Map<KeyGrid.LedRef, LayerPixel> layer, double scale) {
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> entry : layer.entrySet()) {
            LayerPixel pixel = entry.getValue();
            add(entry.getKey(), pixel.color(), pixel.alpha() * scale);
        }
    }

    /** The accumulated light, as pixels. Keys that received nothing at all are omitted entirely. */
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
