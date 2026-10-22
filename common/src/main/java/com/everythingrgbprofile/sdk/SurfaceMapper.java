package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which LED of one surface each LED of another should copy.
 *
 * <p>Needed any time a frame rendered for one board has to be shown on a
 * different one: the tuner's drawn keyboard onto real hardware, or the lead
 * backend's keyboard onto every other backend in auto mode.
 *
 * <h2>Why not just nearest neighbour</h2>
 * Because that was tried first, and it blacked out the number row.
 *
 * <p>Two boards normalise to different vertical spacing. A real K70 puts its
 * F-row at y 0.2 and its numbers at 0.4, because there is a physical gap and a
 * logo LED sitting above them. A vendor grid spaces its six rows evenly, 0.2
 * apart. So plain nearest-in-2D confidently sent the grid's number row to the
 * K70's F-row, and fourteen physical LEDs were simply never written to at all.
 *
 * <p>So this matches <b>rows first</b>: cluster both boards into rows, pair
 * them off top to bottom, then match within a row by x alone. It only falls
 * back to 2D distance for LEDs belonging to no key row, such as a logo or an
 * indicator.
 *
 * <p>Rows holding fewer than five LEDs do not count as key rows, and that
 * threshold is load-bearing. A K70 reports its logo as a one-LED "row", and
 * letting that take a pairing slot shifts every single row below it down by
 * one.
 */
public final class SurfaceMapper {

    private SurfaceMapper() {
    }

    /** For each target LED, the source LED whose colour it should show. */
    public static Map<KeyGrid.LedRef, KeyGrid.LedRef> map(List<KeyGrid.LedPosition> targets,
                                                   List<KeyGrid.LedPosition> sources) {
        Map<KeyGrid.LedRef, KeyGrid.LedRef> out = new HashMap<>();
        if (targets.isEmpty() || sources.isEmpty()) return out;

        List<List<KeyGrid.LedPosition>> targetRows = keyRows(targets);
        List<List<KeyGrid.LedPosition>> sourceRows = keyRows(sources);

        // Row pairing only makes sense when both sides are actually keyboards.
        // A mouse or a strip has one or two "rows", and pairing those by index
        // is meaningless; 2D distance is the better answer there.
        if (targetRows.size() >= 2 && sourceRows.size() >= 2) {
            int paired = Math.min(targetRows.size(), sourceRows.size());
            for (int i = 0; i < paired; i++) {
                for (KeyGrid.LedPosition t : targetRows.get(i)) {
                    KeyGrid.LedPosition best = null;
                    double bestD = Double.MAX_VALUE;
                    for (KeyGrid.LedPosition s : sourceRows.get(i)) {
                        double d = Math.abs(s.x() - t.x());
                        if (d < bestD) {
                            bestD = d;
                            best = s;
                        }
                    }
                    if (best != null) out.put(t.ref(), best.ref());
                }
            }
        }

        for (KeyGrid.LedPosition t : targets) {
            if (out.containsKey(t.ref())) continue;
            KeyGrid.LedPosition best = null;
            double bestD = Double.MAX_VALUE;
            for (KeyGrid.LedPosition s : sources) {
                double d = Math.hypot(s.x() - t.x(), s.y() - t.y());
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            if (best != null) out.put(t.ref(), best.ref());
        }
        return out;
    }

    /** Rows of at least five LEDs, top to bottom. */
    private static List<List<KeyGrid.LedPosition>> keyRows(List<KeyGrid.LedPosition> leds) {
        List<KeyGrid.LedPosition> sorted = new ArrayList<>(leds);
        sorted.sort(Comparator.comparingDouble(KeyGrid.LedPosition::y));
        List<List<KeyGrid.LedPosition>> rows = new ArrayList<>();
        List<KeyGrid.LedPosition> cur = new ArrayList<>();
        double last = Double.NaN;
        for (KeyGrid.LedPosition p : sorted) {
            // 4% of the board's height: well under any real row spacing, well
            // over the jitter within one row.
            if (!cur.isEmpty() && p.y() - last > 0.04) {
                rows.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(p);
            last = p.y();
        }
        if (!cur.isEmpty()) rows.add(cur);
        rows.removeIf(r -> r.size() < 5);
        return rows;
    }
}
