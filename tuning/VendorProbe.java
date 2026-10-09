import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.LightingBackend;
import com.everythingrgbprofile.sdk.LogitechBackend;
import com.everythingrgbprofile.sdk.RazerChromaBackend;
import com.everythingrgbprofile.sdk.SteelSeriesBackend;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tries each grid vendor backend against whatever is installed on this machine.
 *
 * <p>Two jobs. With no vendor software present it proves the path most users
 * will take: auto mode calls every backend at startup, so an absent vendor must
 * fail fast and quietly — no exception, no long stall. With the software
 * installed it proves the transport: session opens, frames are accepted, the
 * session closes and hands lighting back.
 *
 * <pre>
 *   gradlew vendorProbe                       open and close each session only
 *   gradlew vendorProbe -PprobeArgs=--send    also push alternating amber/red for three seconds
 * </pre>
 *
 * <p>{@code --send} changes the lighting on any matching device for those three
 * seconds, then hands it back. Without a device of that brand attached there
 * is nothing to see, but the vendor service still has to accept the frames —
 * which is the part being tested.
 */
public class VendorProbe {

    public static void main(String[] args) throws Exception {
        boolean send = args.length > 0 && args[0].equals("--send");
        List<LightingBackend> backends = List.of(
                new RazerChromaBackend(), new LogitechBackend(), new SteelSeriesBackend());

        for (LightingBackend b : backends) {
            long t0 = System.nanoTime();
            boolean ok;
            try {
                ok = b.connect(Path.of("."), cls -> true);
            } catch (Throwable t) {
                System.out.printf("%-24s THREW during connect: %s%n", b.displayName(), t);
                continue;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (!ok) {
                System.out.printf("%-24s not available   (%d ms)%n", b.displayName(), ms);
                b.shutdown();
                continue;
            }
            KeyGrid g = b.keyGrid();
            System.out.printf("%-24s CONNECTED       (%d ms)  %d cells, named key T %s%n",
                    b.displayName(), ms, g.allKeys().size(),
                    g.namedKey("T") != null ? "present" : "absent");

            if (send) {
                // Alternate two colours every frame. A constant frame is only
                // ever pushed once — dirty tracking skips the rest — and one
                // failed request never reaches the backend's give-up threshold,
                // so "still connected" would pass even if every frame failed.
                // Changing the colour each frame makes every frame a real send,
                // so three seconds of failures would trip the drop.
                Map<KeyGrid.LedRef, RGBColor> amber = new HashMap<>();
                Map<KeyGrid.LedRef, RGBColor> red = new HashMap<>();
                for (KeyGrid.LedPosition p : g.allKeys()) {
                    amber.put(p.ref(), new RGBColor(255, 120, 0));
                    red.put(p.ref(), new RGBColor(255, 20, 0));
                }
                long until = System.currentTimeMillis() + 3000;
                int n = 0;
                while (System.currentTimeMillis() < until) {
                    b.applyFrame((n++ & 1) == 0 ? amber : red);
                    Thread.sleep(50);
                }
                System.out.printf("%-24s after %d changing frames over 3s: %s%n", "", n,
                        b.connected() ? "still connected (frames accepted)" : "DROPPED - transport failed");
            }
            b.shutdown();
        }
    }
}
