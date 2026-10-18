/**
 * In before lobsterjohn tries to remove these claiming, they need to "protect the children" - blantant transphobia is blatant.
 * If this game is meant for children LobsterJonn, then why the fuck are you actively playing it??
 */
package com.everythingrgbprofile.pattern.patterns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

/**
 * A horizontal-striped flag across the board, with a slow ripple running
 * through it like it's catching a breeze. Three so far: {@code trans-flag},
 * {@code lesbian-flag} and {@code bi-flag}.
 *
 * <h2>Hidden</h2>
 * Same deal as {@link RainbowWavePattern}: no default profile uses these and
 * the config comments don't advertise them. Name one as a biome's
 * {@code pattern} in biome_profiles.json, or as {@code menuTheme.style}, and
 * it runs. Otherwise it doesn't exist as far as anyone can tell.
 *
 * <h2>Stripes vs rows</h2>
 * Both flags have five stripes and a full-size board has six rows, so
 * something has to double up, and for the flag to stay symmetric it has to be
 * the middle one: blue, pink, white, white, pink, blue. A trans flag that
 * isn't symmetric is not, it turns out, a trans flag.
 *
 * <p>So stripes are handed out by <b>row number</b>, never by height. An
 * earlier version snapped each key's raw y to the nearest stripe centre, which
 * sounds the same and isn't, because keyboard rows aren't evenly spaced: the
 * F-row sits off on its own with a gap under it, which squashes the other five
 * rows down toward the bottom. The home row and the shift row both rounded
 * into pink and the middle got one white row. See {@link #assignStripes}.
 *
 * <p>Ignores the profile's colours. The flag is the flag.
 *
 * <h2>Why it moves at all</h2>
 * {@code PatternFactory} has a rule that nothing it builds is a solid,
 * motionless fill, because a frozen board looks exactly like a crashed mod.
 * Five static stripes would break that rule with extra steps. Hence the
 * ripple: a dark fold sliding diagonally across the flag, like cloth with
 * wind under it.
 */
public final class PrideFlagPattern implements Pattern {

    /** Ripples visible across the board at once. */
    private static final double RIPPLES_ACROSS = 1.5;

    /** Seconds for one ripple to travel the full width. Slow on purpose, this is a flag, not a rave. */
    private static final double SECONDS_PER_CROSSING = 3.0;

    /**
     * How much the deepest part of a fold dims a key. Tuned on the board,
     * where it took a lot more than the numbers suggested: 0.25 was invisible,
     * 0.6 still barely there, and 0.8 is where it finally reads as a ripple.
     * 1.0 punches black holes through the flag.
     *
     * <p>At 0.8 the dark rose bottoms out around an eighth of full drive, only
     * just above the point where a key stops reading as dim and starts reading
     * as off (see ColorPalette). So this is about as deep as it can go. If the
     * ripple ever needs to be stronger again, sharpen the fold instead.
     */
    private static final double RIPPLE_DEPTH = 0.8;

    /**
     * How far the fold leans from the top row to the bottom, in board widths.
     * Without it the fold is a vertical bar marching sideways, which looks
     * like a loading screen. Leaned, it looks like cloth.
     */
    private static final double RIPPLE_LEAN = 0.25;

    /**
     * Two keys whose y differs by less than this are in the same row, in
     * normalised units. Real rows are about 0.18 apart, and keys within a row
     * agree to well under a hundredth, so anywhere in between works.
     */
    private static final double ROW_TOLERANCE = 0.06;

    /**
     * A cluster with fewer keys than this isn't a row. It's a tall numpad key
     * (plus, enter) whose centre sits between two rows, and it gets folded
     * into whichever real row is nearest instead of counting as its own.
     */
    private static final int MIN_ROW_KEYS = 3;

    /** Top stripe first. */
    private final RGBColor[] stripes;

    /**
     * Each key's stripe, worked out once on the first frame, because the rows
     * don't move. Worker thread only, same as every other lazily-initialised
     * pattern, so no locking.
     */
    private Map<KeyGrid.LedRef, Integer> stripeByKey;

    private PrideFlagPattern(RGBColor... stripes) {
        this.stripes = stripes;
    }

    /** Blue, pink, white, pink, blue. */
    public static PrideFlagPattern trans() {
        return trans(ColorPalette.TRANS_BLUE, ColorPalette.TRANS_PINK);
    }

    /**
     * The trans flag with its blue and pink swapped out, which is what the
     * colour tuner drives off its sliders. Those two are the stripes that
     * moved away from the official hexes (see ColorPalette), so they're the
     * ones that need judging on a real board. White is white. Nobody needs a
     * slider for white.
     */
    public static PrideFlagPattern trans(RGBColor blue, RGBColor pink) {
        return new PrideFlagPattern(blue, pink, ColorPalette.FLAG_WHITE, pink, blue);
    }

    /** The five-stripe version: dark orange, orange, white, pink, dark rose. */
    public static PrideFlagPattern lesbian() {
        return lesbian(ColorPalette.LESBIAN_ORANGE, ColorPalette.LESBIAN_PINK);
    }

    /**
     * Same idea as {@link #trans(RGBColor, RGBColor)}: the orange and pink are
     * the two that got pushed away from the official hexes, so they're the two
     * the tuner gets sliders for. The dark ends were already saturated enough
     * to leave alone.
     */
    public static PrideFlagPattern lesbian(RGBColor orange, RGBColor pink) {
        return new PrideFlagPattern(
                ColorPalette.LESBIAN_DARK_ORANGE,
                orange,
                ColorPalette.FLAG_WHITE,
                pink,
                ColorPalette.LESBIAN_DARK_ROSE);
    }

    /** Pink, pink, purple, blue, blue. */
    public static PrideFlagPattern bi() {
        return bi(ColorPalette.BI_PINK, ColorPalette.BI_PURPLE);
    }

    /**
     * The bi flag is pink, purple and blue at 2:1:2, which is five stripes'
     * worth, so it goes through the same row spreading as the other two and
     * nothing special happens. On six rows the purple picks up the doubled
     * middle row, making it 2:2:2. As close as six rows get.
     *
     * <p>Only the purple moved away from its official hex. The pink gets a
     * slider anyway, because the pink is what the purple is most at risk of
     * blending into, and those two want judging side by side.
     */
    public static PrideFlagPattern bi(RGBColor pink, RGBColor purple) {
        return new PrideFlagPattern(pink, pink, purple, ColorPalette.BI_BLUE, ColorPalette.BI_BLUE);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        List<KeyGrid.LedPosition> keys = ctx.effectiveTargetPositions();
        if (stripeByKey == null) stripeByKey = assignStripes(keys, stripes.length);

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        double drift = elapsedMillis / 1000.0 / SECONDS_PER_CROSSING;
        for (KeyGrid.LedPosition p : keys) {
            Integer stripe = stripeByKey.get(p.ref());
            if (stripe == null) continue; // can't happen with a fixed key set, costs nothing to survive
            double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI
                    * (p.x() * RIPPLES_ACROSS + p.y() * RIPPLE_LEAN - drift));
            // Squared, so the dark part is a narrow fold with flat bright cloth
            // either side of it. A plain sine spends half its time dimmed,
            // and a flag that's half in shadow looks like a dimmer switch.
            double fold = wave * wave;
            out.put(p.ref(), LayerPixel.opaque(stripes[stripe].scaled(1.0 - RIPPLE_DEPTH * fold)));
        }
        return out;
    }

    /**
     * Works out which stripe every key belongs to, by row.
     *
     * <p>Three steps. Group keys into rows by y. Throw out any "row" too small
     * to be one (the tall numpad keys, see {@link #MIN_ROW_KEYS}) and give its
     * keys to the nearest real row. Then spread the stripes evenly over the row
     * <i>numbers</i>, so the spacing between rows can't skew anything.
     *
     * <p>The spreading only works out the top half and mirrors it for the
     * bottom. {@code Math.round} sends every .5 upward, so computing both halves
     * directly would hand the lower half its stripes a row early on any board
     * where the division lands on a half, and the flag would come out lopsided.
     * Mirroring makes symmetry structural instead of something the arithmetic
     * happens to get right for six rows.
     */
    private static Map<KeyGrid.LedRef, Integer> assignStripes(List<KeyGrid.LedPosition> keys, int stripeCount) {
        List<KeyGrid.LedPosition> byY = new ArrayList<>(keys);
        byY.sort(Comparator.comparingDouble(KeyGrid.LedPosition::y));

        // Walk down the board, starting a new cluster at every gap bigger
        // than the tolerance. Each cluster is {sum of y, count}.
        List<double[]> clusters = new ArrayList<>();
        double prevY = Double.NEGATIVE_INFINITY;
        for (KeyGrid.LedPosition p : byY) {
            if (p.y() - prevY > ROW_TOLERANCE) clusters.add(new double[]{0, 0});
            double[] c = clusters.get(clusters.size() - 1);
            c[0] += p.y();
            c[1]++;
            prevY = p.y();
        }
        List<Double> rowY = new ArrayList<>();
        for (double[] c : clusters) if (c[1] >= MIN_ROW_KEYS) rowY.add(c[0] / c[1]);
        // A board so small nothing qualified (a numpad on its own, say). Every
        // cluster counts then, because something has to.
        if (rowY.isEmpty()) for (double[] c : clusters) rowY.add(c[0] / c[1]);

        int rows = rowY.size();
        int last = stripeCount - 1;
        Map<KeyGrid.LedRef, Integer> out = new HashMap<>();
        for (KeyGrid.LedPosition p : keys) {
            int row = 0;
            for (int r = 1; r < rows; r++) {
                if (Math.abs(rowY.get(r) - p.y()) < Math.abs(rowY.get(row) - p.y())) row = r;
            }
            out.put(p.ref(), stripeForRow(row, rows, last));
        }
        return out;
    }

    /** Top half computed, bottom half mirrored. See {@link #assignStripes} for why. */
    private static int stripeForRow(int row, int rows, int last) {
        if (rows <= 1) return last / 2; // one row gets the middle stripe, which on both flags is white
        int mirror = rows - 1 - row;
        if (row > mirror) return last - stripeForRow(mirror, rows, last);
        return (int) Math.round(row * last / (double) (rows - 1));
    }
}
