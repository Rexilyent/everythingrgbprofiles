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
 * <p>Needed whenever a frame rendered for one board has to be shown on a
 * different one — the tuner's drawn keyboard onto real hardware, or the lead
 * backend's keyboard onto every other backend in auto mode.
 *
 * <h2>Why not just nearest neighbour</h2>
 * Because it was tried, and it blacked out the number row. Two boards normalise
 * to different vertical spacing: a real K70 puts its F-row at y 0.2 and numbers
 * at 0.4 (there is a physical gap and a logo LED above), while a vendor grid
 * spaces six rows evenly at 0.2 apart. Plain nearest-in-2D sent the grid's
 * number row to the K70's F-row, and fourteen physical LEDs were never written.
 *
 * <p>So this matches <b>rows first</b> — cluster both boards into rows, pair
 * them top to bottom, then match within a row by x alone — and only falls back
 * to 2D distance for LEDs that belong to no key row, like a logo or an
 * indicator. Rows with fewer than five LEDs are not key rows: a K70 reports its
 * logo as a one-LED "row", and letting that take a pairing slot would shift
 * every row below it by one.
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
