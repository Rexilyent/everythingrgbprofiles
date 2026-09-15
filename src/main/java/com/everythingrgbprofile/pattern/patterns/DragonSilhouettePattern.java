package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The dragon itself, drawn on the keys: a wingspan, a body, a head with an eye,
 * and a tail. It glides, it beats its wings, it lunges at you, and it perches.
 *
 * <p>The breath attack is <b>not</b> here. It used to be a cone drawn forward
 * from the head, which was geometrically honest and read as a triangle — see
 * {@link DragonBreathFirePattern}, which puts burning purple on the floor
 * instead, the way the attack actually behaves.
 *
 * <h2>Why draw it at all</h2>
 * Terraria's RGB does not give a boss an ambient colour, it gives you the
 * <i>boss</i>: Moon Lord turns the keyboard into an eye, Duke Fishron swims
 * across it. A purple shimmer says "something is happening"; a silhouette with
 * a wingspan says "that thing is happening". This is the same idea applied to
 * the one Minecraft boss whose silhouette everybody already knows.
 *
 * <h2>The board is the right shape for it</h2>
 * A keyboard is about eighteen keys wide and six tall — roughly 3:1. A dragon
 * with its wings out is roughly 3:1. Almost nothing else in Minecraft would
 * fit this canvas as neatly, which is a large part of why this works at all.
 *
 * <h2>Reading it at six rows</h2>
 * Six rows is not much vertical room, so the shape is carried by the parts
 * that survive being three pixels tall: a bright body bar, wings that arc up
 * and down through roughly two rows, and a single very bright eye. Detail
 * finer than that is wasted, so there isn't any.
 */
public final class DragonSilhouettePattern implements Pattern {

    /** Half the wingspan, as a fraction of board width. */
    private static final double WINGSPAN = 0.46;
    /**
     * How far the wingtips travel vertically, in board heights. 0.42 puts the wingtips on the top and bottom rows at full stroke. Tuned
     * against a six-row board: less and the beat only uses the middle four
     * rows and reads as a bar wobbling, more and the tips clip off the board
     * at the extremes of the stroke.
     */
    private static final double FLAP_RISE = 0.42;
    private static final double MEMBRANE_THICKNESS = 0.15;
    private static final double BODY_HALF_LENGTH = 0.13;
    private static final double BODY_HALF_HEIGHT = 0.11;

    private volatile double centerX = 0.5;
    private volatile double centerY = 0.5;
    private volatile double scale = 1.0;
    private volatile double flapHz = 0.75;
    /** 0 = wings barely move (perched), 1 = full beat. */
    private volatile double flapDepth = 1.0;
    private volatile double glow = 1.0;
    /** +1 faces right, -1 faces left. Set from travel direction. */
    private volatile double facing = 1.0;

    public void setPose(double centerX, double centerY, double scale, double facing) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.scale = Math.max(0.35, scale);
        this.facing = facing >= 0 ? 1 : -1;
    }

    public void setWings(double flapHz, double flapDepth) {
        this.flapHz = Math.max(0.05, flapHz);
        this.flapDepth = Math.max(0, Math.min(1, flapDepth));
    }

    public void setGlow(double glow) {
        this.glow = Math.max(0, glow);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double seconds = elapsedMillis / 1000.0;
        RGBColor body = ctx.baseColor();
        RGBColor accent = ctx.resolvedAccentColor();

        double flap = Math.sin(2 * Math.PI * flapHz * seconds) * flapDepth;
        double span = WINGSPAN * scale;
        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            // u runs -1..1 across the wingspan, v is height above/below the
            // spine. Deliberately NOT aspect-corrected: the silhouette is meant
            // to fill the board, not to be geometrically circular on it.
            double u = (key.x() - centerX) / span;
            double v = key.y() - centerY;
            double au = Math.abs(u);

            // --- wings -------------------------------------------------
            // The membrane rises toward the tips, so the whole span sweeps
            // rather than pivoting rigidly. ^1.4 keeps the inner wing near the
            // spine and throws most of the travel out to the tips, which is
            // what a wingbeat looks like.
            if (au <= 1.0) {
                double membraneY = -FLAP_RISE * flap * Math.pow(au, 1.4);
                double d = Math.abs(v - membraneY);
                if (d < MEMBRANE_THICKNESS) {
                    double across = 1.0 - d / MEMBRANE_THICKNESS;
                    // Thins out toward the tips so the shape tapers.
                    double taper = 1.0 - 0.45 * au;
                    budget.add(key.ref(), body, across * taper * 0.75 * glow);
                }
            }

            // --- body --------------------------------------------------
            if (au < BODY_HALF_LENGTH / span && Math.abs(v) < BODY_HALF_HEIGHT) {
                budget.add(key.ref(), body, 0.9 * glow);
            }

            // --- head and eye ------------------------------------------
            // Sits just forward of the body, in the direction of travel.
            double headU = facing * (BODY_HALF_LENGTH / span + 0.22);
            double dHead = Math.hypot((u - headU) * 0.9, v / 0.9);
            if (dHead < 0.30) {
                double f = 1.0 - dHead / 0.30;
                budget.add(key.ref(), accent, f * f * 1.15 * glow);
            }


            // --- tail --------------------------------------------------
            double tailU = -facing;
            if (u * tailU > BODY_HALF_LENGTH / span && au < 1.0) {
                double sway = 0.09 * Math.sin(2 * Math.PI * flapHz * seconds - 1.2);
                double d = Math.abs(v - sway * au);
                if (d < 0.09) {
                    budget.add(key.ref(), body, (1.0 - d / 0.09) * (1.0 - au) * 0.5 * glow);
                }
            }
        }
        return budget.resolve();
    }
}
