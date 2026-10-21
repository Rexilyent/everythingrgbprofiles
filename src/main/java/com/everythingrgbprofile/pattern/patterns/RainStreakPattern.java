package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rain, as streaks: a bright key at the head, a short fading tail behind it,
 * dropping down a column a whole row at a time and fast. Same idea as the
 * Rain effect iCUE ships, minus the part where every drop is a different
 * colour. Rain in Minecraft is one colour, and a storm of confetti is not
 * weather, it's a gender reveal. Every drop is the rain colour.
 *
 * <p>Rain used to be a preset of {@link DriftParticlePattern}: a dozen single
 * dots sliding down at about two seconds a board, with a trail too short to
 * ever reach the next row. Slow, soft, evenly spread dots falling on
 * everything is the textbook definition of snow, and that's exactly what it
 * looked like. A raindrop is mostly speed, and the tail is how a keyboard
 * shows speed, so this got its own engine instead of more numbers in that one.
 *
 * <p>Snow still uses the drift engine, which is now the point: the two should
 * never be mistaken for each other.
 */
public final class RainStreakPattern implements Pattern {

    /** Defaults for when no config is around (the pattern name in a profile, the tuner). */
    public static final int DEFAULT_DROPS = 12;
    public static final int DEFAULT_FALL_MILLIS = 550;

    /** Keys of tail behind the head. Three on a six-row board is half its height, which is plenty. */
    private static final int TAIL = 3;
    /** Longest pause before a landed drop's slot sends another, in fall times. Random up to this. */
    private static final double MAX_GAP = 1.2;
    /** Each drop's fall time is nudged up to this fraction either way, so they don't move in lockstep. */
    private static final double SPEED_JITTER = 0.2;
    /** How hard the tail drops off. Above 1 keeps it short and the head obviously the head. */
    private static final double TAIL_CURVE = 1.6;

    private static final class Drop {
        double x;
        /** When its head enters the top row. In the future while the slot is waiting. */
        double startMillis;
        double fallMillis;
    }

    private final int dropCount;
    private final double fallMillis;
    private final Random random = new Random();
    private Drop[] drops;

    private KeyGrid rowsFor;
    /** The board's keys in rows, top first, each row left to right. */
    private final List<List<KeyGrid.LedPosition>> rows = new ArrayList<>();
    private double minX, maxX;

    /**
     * @param dropCount  slots for drops. Each spends part of its time waiting,
     *                   so fewer than this are on the board at any moment.
     * @param fallMillis how long a drop's head takes from the top row to the
     *                   bottom one
     */
    public RainStreakPattern(int dropCount, int fallMillis) {
        this.dropCount = Math.max(1, dropCount);
        this.fallMillis = Math.max(50, fallMillis);
    }

    public static RainStreakPattern withDefaults() {
        return new RainStreakPattern(DEFAULT_DROPS, DEFAULT_FALL_MILLIS);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        if (rowsFor != grid) buildRows(grid);
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        if (rows.isEmpty()) return out;
        RGBColor tint = ctx.baseColor();

        if (drops == null) {
            drops = new Drop[dropCount];
            for (int i = 0; i < dropCount; i++) {
                drops[i] = new Drop();
                // Spread backwards over a whole cycle, so the rain opens
                // already falling at every height instead of as one neat rank
                // of drops lined up along the top row.
                respawn(drops[i], elapsedMillis - random.nextDouble() * fallMillis * (1 + SPEED_JITTER + MAX_GAP));
            }
        }

        int rowCount = rows.size();
        for (Drop d : drops) {
            double age = elapsedMillis - d.startMillis;
            // Waiting its turn. Unless the wait is longer than any wait could
            // be, which means the clock went backwards: start over from now.
            if (age < 0) {
                if (-age > fallMillis * (1 + SPEED_JITTER) * (MAX_GAP + 1)) respawn(d, elapsedMillis);
                continue;
            }
            // Rows the head has travelled. It keeps going past the bottom by
            // the tail's length, so the tail drains off the board instead of
            // vanishing all at once on the last row.
            double travelled = age / (d.fallMillis / rowCount);
            if (travelled >= rowCount + TAIL) {
                respawn(d, elapsedMillis);
                continue;
            }
            // Whole rows only. A head smeared across two rows by its
            // fractional position reads as a soft blob, and the crisp step
            // from key to key is half of what makes it look like rain.
            int head = (int) Math.floor(travelled);
            for (int j = 0; j <= TAIL; j++) {
                int row = head - j;
                if (row < 0 || row >= rowCount) continue;
                KeyGrid.LedPosition key = nearestInRow(rows.get(row), d.x);
                double b = j == 0 ? 1.0 : Math.pow(1.0 - j / (TAIL + 1.0), TAIL_CURVE);
                // Additive, so two drops crossing the same key light it more
                // rather than one quietly hiding the other.
                out.merge(key.ref(), new LayerPixel(tint, b),
                        (a, c) -> new LayerPixel(a.color(), Math.min(1.0, a.alpha() + c.alpha())));
            }
        }
        return out;
    }

    /** A new drop in a random column, after a random pause. */
    private void respawn(Drop d, double nowMillis) {
        d.x = minX + random.nextDouble() * (maxX - minX);
        d.fallMillis = fallMillis * (1 + SPEED_JITTER * (2 * random.nextDouble() - 1));
        d.startMillis = nowMillis + random.nextDouble() * MAX_GAP * fallMillis;
    }

    /**
     * Groups keys into rows by height. A key more than half a row below
     * where the current row started begins a new one, which survives the odd
     * key that sits a little high or low in its row, like the arrow cluster.
     */
    private void buildRows(KeyGrid grid) {
        rows.clear();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        List<KeyGrid.LedPosition> keys = new ArrayList<>(grid.allKeys());
        keys.sort(Comparator.comparingDouble(KeyGrid.LedPosition::y));
        minX = Double.MAX_VALUE;
        maxX = -Double.MAX_VALUE;
        List<KeyGrid.LedPosition> row = null;
        double rowTop = 0;
        for (KeyGrid.LedPosition key : keys) {
            if (row == null || key.y() - rowTop > rowStep * 0.5) {
                row = new ArrayList<>();
                rows.add(row);
                rowTop = key.y();
            }
            row.add(key);
            minX = Math.min(minX, key.x());
            maxX = Math.max(maxX, key.x());
        }
        for (List<KeyGrid.LedPosition> r : rows) r.sort(Comparator.comparingDouble(KeyGrid.LedPosition::x));
        rowsFor = grid;
    }

    private static KeyGrid.LedPosition nearestInRow(List<KeyGrid.LedPosition> row, double x) {
        KeyGrid.LedPosition best = row.get(0);
        for (KeyGrid.LedPosition key : row) {
            if (Math.abs(key.x() - x) < Math.abs(best.x() - x)) best = key;
        }
        return best;
    }
}
