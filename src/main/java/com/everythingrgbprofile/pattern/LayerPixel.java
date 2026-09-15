package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.color.RGBColor;

/**
 * One LED's contribution from one layer: a colour, and how much of it there
 * is. It's a colour with an alpha channel. That's genuinely all it is.
 *
 * <h2>Why splitting these apart was a whole thing</h2>
 * The compositing model is borrowed from Terraria: Tier 1 ambient backgrounds
 * are opaque, Tier 2 alerts are <i>transparent overlays</i> that composite on
 * top instead of replacing. Reasonable. An earlier implementation, however,
 * contained no blend function of any kind. {@code Compositor} did {@code frame.putAll(...)}
 * and patterns expressed "fade out" as {@code scaled(brightness)} — literally
 * darkening the colour toward black.
 *
 * <p>Darkening toward black is only equivalent to fading out when there is
 * <b>nothing underneath</b>. The moment something is under you, it's wrong.
 * A rain streak at 30% over a jungle biome should look
 * like slightly-washed-out jungle green. What it actually did was
 * {@code jungleGreen.scaled(0.3)} — a near-black key. Rain didn't overlay the
 * biome, it deleted it and left a dark smear.
 *
 * <p>Separating colour from alpha fixes that, and here's the neat part: it
 * costs nothing to migrate. Compositing {@code (colour, alpha)} source-over an
 * initially-black frame is arithmetically <i>identical</i> to the old
 * {@code scaled(alpha)}. So every Tier 1 ambient layer looks pixel-for-pixel
 * the same as before, and every Tier 2 overlay silently becomes correct. Free
 * bug fix, no visual regression, no migration.
 *
 * @param color the layer's colour at this LED, at full strength
 * @param alpha coverage in [0,1]; 0 contributes nothing, 1 fully replaces
 */
public record LayerPixel(RGBColor color, double alpha) {

    /** Clamp on construction so {@link #over} never has to think about it. */
    public LayerPixel {
        alpha = Math.max(0.0, Math.min(1.0, alpha));
    }

    /** Fully covering. For Tier 1 bases and Tier 3 flashes, which don't blend. */
    public static LayerPixel opaque(RGBColor color) {
        return new LayerPixel(color, 1.0);
    }

    /**
     * Source-over composite of this pixel onto whatever's already there.
     *
     * <p>The two early-outs aren't just speed — {@code alpha >= 1} returns the
     * colour object itself and {@code alpha <= 0} returns {@code beneath}
     * itself, both skipping an allocation. At a full board times 60fps times a
     * handful of layers, "don't allocate for the no-op cases" adds up.
     */
    public RGBColor over(RGBColor beneath) {
        if (alpha >= 1.0) return color;
        if (alpha <= 0.0) return beneath;
        return beneath.lerp(color, alpha);
    }

    /**
     * Scales this pixel's alpha, for applying a whole-layer opacity on top of
     * per-pixel alpha. Clamping happens in the constructor, so overshooting
     * factors are already handled.
     */
    public LayerPixel scaledAlpha(double factor) {
        return new LayerPixel(color, alpha * factor);
    }
}
