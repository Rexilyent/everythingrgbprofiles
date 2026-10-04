package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.color.RGBColor;

/**
 * One LED's contribution from one layer: a colour, and how much of it there
 * is. It's a colour with an alpha channel. That's genuinely all it is.
 *
 * <h2>Why splitting these two apart turned into a whole thing</h2>
 * The compositing model is borrowed from Terraria: Tier 1 ambient backgrounds
 * are opaque, Tier 2 alerts are <i>transparent overlays</i> that composite on
 * top rather than replacing. Perfectly reasonable.
 *
 * <p>An earlier implementation contained no blend function of any kind.
 * {@code Compositor} did {@code frame.putAll(...)}, and patterns expressed
 * "fade out" as {@code scaled(brightness)}, which is to say by literally
 * darkening the colour toward black.
 *
 * <p>Darkening toward black is only equivalent to fading out when there is
 * <b>nothing underneath you</b>. The instant something is under you it is
 * wrong, and it is wrong in a way that looks deliberate.
 *
 * <p>A rain streak at 30% over a jungle biome should read as
 * slightly-washed-out jungle green. What it actually did was
 * {@code jungleGreen.scaled(0.3)}, which is a nearly black key. Rain was not
 * overlaying the biome. It was deleting it and leaving a dark smear where the
 * jungle used to be.
 *
 * <p>Separating colour from alpha fixes it, and here is the genuinely neat
 * part: migrating cost nothing. Compositing {@code (colour, alpha)}
 * source-over an initially-black frame is arithmetically <i>identical</i> to
 * the old {@code scaled(alpha)}.
 *
 * <p>So every Tier 1 ambient layer came out pixel-for-pixel the same as
 * before, and every Tier 2 overlay quietly became correct on the way past. A
 * free bug fix with no visual regression and no migration step, which is not
 * a sentence anybody gets to write very often.
 *
 * @param color the layer's colour at this LED, at full strength
 * @param alpha coverage in [0,1]; 0 contributes nothing, 1 fully replaces
 */
public record LayerPixel(RGBColor color, double alpha) {

    /** Clamped on construction so {@link #over} never has to think about it. */
    public LayerPixel {
        alpha = Math.max(0.0, Math.min(1.0, alpha));
    }

    /** Fully covering. For Tier 1 bases and Tier 3 flashes, neither of which blend. */
    public static LayerPixel opaque(RGBColor color) {
        return new LayerPixel(color, 1.0);
    }

    /**
     * Source-over composite of this pixel onto whatever's already there.
     *
     * <p>The two early-outs are not purely about speed. {@code alpha >= 1}
     * returns the colour object itself and {@code alpha <= 0} returns
     * {@code beneath} itself, and both of those skip an allocation entirely.
     * Multiply that by a full board, times 30 frames a second (up to 60 if
     * you raise it), times a handful of layers,
     * and "do not allocate for the no-op cases" stops being pedantry.
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
