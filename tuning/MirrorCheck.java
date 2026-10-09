import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.SurfaceMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks auto mode's mirroring against a real keyboard's geometry.
 *
 * <p>In auto mode the lead backend's frame is resampled onto every other
 * backend by {@code SurfaceMapper}. The way that goes wrong is not subtle once
 * you know to look: it sends one board's number row to the other's F-row and
 * leaves physical keys dark — which already happened once, in the tuner. This
 * connects to the real Corsair board read-only (it never pushes a frame), builds
 * the fixed grids Razer and Logitech expose, and reports which row of the
 * source each row of the target draws from. A correct mapping is a clean
 * diagonal.
 *
 * <pre>
 *   java -cp "build/classes/java/main;tuning;jna-5.14.0.jar" MirrorCheck
 * </pre>
 */
public class MirrorCheck {

    public static void main(String[] args) {
        HardwareBridge hb = new HardwareBridge();
        System.out.println(hb.connect());
        if (!hb.ok()) return;
        KeyGrid real = hb.grid();

        KeyGrid razer = grid("razer:keyboard", 22, 6);
        KeyGrid logi = grid("logitech:keyboard", 21, 6);

        int bad = 0;
        bad += check("Razer 22x6 mirroring the real board", razer.allKeys(), real.allKeys());
        bad += check("Logitech 21x6 mirroring the real board", logi.allKeys(), real.allKeys());
        bad += check("real board mirroring a Razer 22x6 lead", real.allKeys(), razer.allKeys());
        hb.close();
        System.out.println(bad == 0 ? "ALL ROWS ALIGNED" : bad + " MISALIGNED ROW(S)");
    }

    static KeyGrid grid(String id, int cols, int rows) {
        KeyGrid.Builder b = new KeyGrid.Builder();
        b.beginDevice(id, id, KeyGrid.DeviceClass.KEYBOARD);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) b.addLed(r * cols + c, c, r);
        }
        b.endDevice();
        return b.build();
    }

    /** @return number of key rows that drew mostly from the wrong source row */
    static int check(String label, List<KeyGrid.LedPosition> targets, List<KeyGrid.LedPosition> sources) {
        Map<KeyGrid.LedRef, KeyGrid.LedRef> m = SurfaceMapper.map(targets, sources);
        Map<KeyGrid.LedRef, Integer> tRow = rowIndex(targets);
        Map<KeyGrid.LedRef, Integer> sRow = rowIndex(sources);

        int unsourced = 0;
        for (KeyGrid.LedPosition t : targets) if (!m.containsKey(t.ref())) unsourced++;

        System.out.printf("%n%s%n  %d/%d target LEDs have a source%n", label,
                targets.size() - unsourced, targets.size());
        // target row -> (source row -> count)
        TreeMap<Integer, TreeMap<Integer, Integer>> hist = new TreeMap<>();
        for (Map.Entry<KeyGrid.LedRef, KeyGrid.LedRef> e : m.entrySet()) {
            int tr = tRow.getOrDefault(e.getKey(), -1);
            int sr = sRow.getOrDefault(e.getValue(), -1);
            hist.computeIfAbsent(tr, k -> new TreeMap<>()).merge(sr, 1, Integer::sum);
        }
        int bad = 0;
        for (var e : hist.entrySet()) {
            int tr = e.getKey();
            int dominant = e.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue()).get().getKey();
            boolean ok = tr < 0 || dominant == tr;
            if (!ok) bad++;
            System.out.printf("  target row %-2s <- source rows %s %s%n",
                    tr < 0 ? "-" : String.valueOf(tr), e.getValue(), ok ? "" : "  <-- WRONG ROW");
        }
        return bad;
    }

    /** Key-row index per LED, using the same rule as the mapper; -1 for non-key LEDs. */
    static Map<KeyGrid.LedRef, Integer> rowIndex(List<KeyGrid.LedPosition> leds) {
        List<KeyGrid.LedPosition> sorted = new ArrayList<>(leds);
        sorted.sort(Comparator.comparingDouble(KeyGrid.LedPosition::y));
        List<List<KeyGrid.LedPosition>> rows = new ArrayList<>();
        List<KeyGrid.LedPosition> cur = new ArrayList<>();
        double last = Double.NaN;
        for (KeyGrid.LedPosition p : sorted) {
            if (!cur.isEmpty() && p.y() - last > 0.04) {
                rows.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(p);
            last = p.y();
        }
        if (!cur.isEmpty()) rows.add(cur);
        Map<KeyGrid.LedRef, Integer> out = new HashMap<>();
        int idx = 0;
        for (List<KeyGrid.LedPosition> r : rows) {
            boolean keyRow = r.size() >= 5;
            for (KeyGrid.LedPosition p : r) out.put(p.ref(), keyRow ? idx : -1);
            if (keyRow) idx++;
        }
        return out;
    }
}
