package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The End sky: a violet aurora hanging in bands across the board, with a few
 * stars left in it.
 *
 * <p>Replaces {@code twinkle-particle} at {@code #D8CB9A}, which put a
 * scattering of pale cream dots on a dark board and called it the End. That is
 * the overworld's night sky, and the End does not have one. Stand anywhere out
 * there and look up: there are no stars at all. The sky is {@code end_sky.png},
 * a violet static tiled over the whole dome and drawn at about a sixth of its
 * own brightness, so it lands near {@code #120E18}. Purple, even, and
 * everywhere. The board keeps the purple and the evenness and gives them
 * enough light and movement to show on an LED, which a near-black static does
 * not. The aurora is a liberty; the stars are a smaller one.
 *
 * <h2>What the board is actually drawing</h2>
 * Three layers, in the order they matter:
 *
 * <ol>
 *   <li><b>The band.</b> A broad horizontal glow sitting slightly below the
 *       middle of the board, low like a glow on the horizon. Its centre line
 *       undulates along the board rather than running dead straight, because
 *       an aurora drapes.
 *       This is the layer doing most of the work, and it is why the board is
 *       lit rather than dark.</li>
 *   <li><b>Curtains.</b> Vertical striations rippling sideways: three sine
 *       octaves at different wavelengths drifting at different speeds, which
 *       never repeat together and so never visibly loop. They reach further
 *       up from the band than down, because that is the direction the real
 *       ones hang.</li>
 *   <li><b>Stars.</b> Seven of them, slow, small, upper half of the board.
 *       Kept deliberately faint. They are the thing the old effect got
 *       obsessed with, and the moment they compete with the aurora the whole
 *       screen reads as night sky again.</li>
 * </ol>
 *
 * <h2>Why the colour is not the sky's colour</h2>
 * Same correction the desert needed, for the same reason. The End sky texture
 * averages {@code #73589C}, a greyed lilac that is more than half white light,
 * and lilac like that on an LED is a white key that someone has apologised
 * for. (Drawn as dark as the game draws it, it is a key that is off.) The profile drives a saturated violet with
 * the white taken out, and brightness carries what the pale tones were doing
 * on a monitor. The accent is where the pale lilac survives, up at the top of
 * the range where the band peaks and on the stars.
 *
 * <h2>No key is ever fully dark</h2>
 * A floor keeps the whole board sitting in violet. The End has no night side
 * and nowhere dark to stand; a board with holes in it reads as an effect that
 * is failing rather than as a sky that is even.
 *
 * <p>The floor has to account for the colour as well as the level, since what
 * the LED gets is the two multiplied and the base violet is dark to start
 * with. See {@link #WASH_FLOOR}.
 */
public final class EndAuroraPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    /** The End sky, as the dimension the player is standing in. */
    public static Pattern theEnd() {
        return new EndAuroraPattern();
    }

    // --- the band ----------------------------------------------------
    /** Where the bright part of the sky sits. Below centre, like a horizon glow. */
    private static final double BAND_Y = 0.56;
    /**
     * Vertical reach of the glow. Wide on purpose: this is a sky, not a
     * stripe. Tuned so the top and bottom rows still carry visible colour —
     * narrower and the board reads as a lit strip with two dead rows bolted
     * to it, which is not what looking up in the End is like.
     */
    private static final double BAND_HALF = 0.72;
    /** How far the band's centre line drapes, and how slowly it moves. */
    private static final double BAND_WAVE = 0.085;
    private static final double BAND_WAVE_CYCLES = 0.9;
    private static final double BAND_WAVE_HZ = 0.021;

    // --- the curtains ------------------------------------------------
    /**
     * Reach above and below the band centre. Asymmetric because an aurora
     * hangs downward from somewhere high up, so the striations run up out of
     * the bright part and stop fairly quickly under it.
     */
    private static final double RAY_HALF_UP = 0.70;
    private static final double RAY_HALF_DOWN = 0.34;
    /**
     * Pushes the curtains apart. Below about 1.6 they smear into one even
     * wash and the board stops having structure; much above 3 they separate
     * into hard bars with black between them, which is a barcode.
     */
    private static final double RAY_SHARPNESS = 2.3;
    private static final double RAY_GAIN = 0.62;

    /**
     * Violet everywhere, so the board is a sky rather than a shape on black.
     *
     * <p>Higher than it looks like it needs to be, and the reason is worth
     * knowing before anybody trims it back. What reaches the LED is the
     * colour multiplied by this, and the colour is already a dark violet, so
     * the bottom of the range gets squeezed twice. At 0.22 the quiet parts of
     * the sky came out around thirteen on their brightest channel, which is
     * not dim purple, it is off. Tuning this on the numbers alone is how that
     * happened: 0.22 looked like a perfectly reasonable floor right up until
     * it was rendered on a real board.
     */
    private static final double WASH_FLOOR = 0.40;

    // --- the stars ---------------------------------------------------
    private static final int STAR_COUNT = 7;
    /** Stars stay in the top of the board, out of the brightest part of the band. */
    private static final double STAR_FIELD_HEIGHT = 0.62;
    private static final double STAR_PEAK = 0.42;

    private EndAuroraPattern() {
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        RGBColor deep = ctx.baseColor();
        RGBColor pale = ctx.resolvedAccentColor();

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double x = key.x();
            double y = key.y();

            // The centre line drapes along the board instead of running level.
            double centre = BAND_Y + BAND_WAVE
                    * Math.sin(TAU * (x * BAND_WAVE_CYCLES + t * BAND_WAVE_HZ));

            // Gaussian rather than a linear falloff: an aurora has no edge,
            // and a linear one puts a visible seam across the board where the
            // glow reaches zero.
            double dv = (y - centre) / BAND_HALF;
            double band = Math.exp(-dv * dv * 1.55);

            // Three octaves, drifting at speeds that share no common multiple,
            // so the curtains never line back up into the pattern they started
            // as. Two of them would be enough to look right for a minute and
            // then start looking like a loop.
            double c = 0.55 * Math.sin(TAU * (x * 3.1 + t * 0.035))
                    + 0.30 * Math.sin(TAU * (x * 5.7 - t * 0.052 + 0.37))
                    + 0.15 * Math.sin(TAU * (x * 9.3 + t * 0.081 + 0.71));
            double curtain = Math.pow(clamp01(0.5 + 0.5 * c), RAY_SHARPNESS);

            double rayHalf = y < centre ? RAY_HALF_UP : RAY_HALF_DOWN;
            double rv = (y - centre) / rayHalf;
            double rayReach = Math.exp(-rv * rv * 1.2);

            double wash = WASH_FLOOR * (0.40 + 0.60 * band);
            double lit = clamp01(wash + band * curtain * rayReach * RAY_GAIN);

            // Colour rides brightness rather than position: the pale lilac
            // only turns up where the sky is actually bright, which is what
            // stops the whole board averaging out to one flat mauve.
            RGBColor colour = deep.lerp(pale, Math.pow(lit, 1.35));
            budget.add(key.ref(), colour, lit);
        }

        // --- stars ---------------------------------------------------
        // One key each, no halo. A star that bleeds into its neighbours is a
        // smudge; the whole point of them is that they are the only hard edge
        // on a board full of soft gradients.
        RGBColor starColour = pale.lightened(0.30);
        for (int i = 0; i < STAR_COUNT; i++) {
            double sx = hash(i * 13 + 3);
            double sy = hash(i * 7 + 11) * STAR_FIELD_HEIGHT;
            double period = 4.5 + 5.5 * hash(i * 17 + 5);
            double phase = hash(i * 23 + 9);

            // Squared so they sit dim most of the time and only occasionally
            // come up, rather than all of them breathing in unison.
            double pulse = 0.5 + 0.5 * Math.sin(TAU * (t / period + phase));
            KeyGrid.LedPosition star = nearestKey(grid, sx, sy);
            if (star != null) budget.add(star.ref(), starColour, pulse * pulse * STAR_PEAK);
        }

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private static double hash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double x, double y) {
        double aspect = Math.max(0.05, grid.aspectRatio());
        KeyGrid.LedPosition best = null;
        double bestD = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - x, (key.y() - y) * aspect);
            if (d < bestD) {
                bestD = d;
                best = key;
            }
        }
        return best;
    }
}
