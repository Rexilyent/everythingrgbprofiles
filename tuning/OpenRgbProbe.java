import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.LightingBackend;
import com.everythingrgbprofile.sdk.OpenRgbBackend;

import java.nio.file.Paths;
import java.util.List;

/**
 * Drives the real {@code OpenRgbBackend} against a running OpenRGB SDK server
 * and prints what it found. Read-only: connects, reads geometry, disconnects.
 * It never writes a colour, so whatever your devices are currently showing is
 * what they keep showing.
 *
 * <pre>
 *   java -cp "build/classes/java/main;tuning;LOG4J;GSON" OpenRgbProbe
 * </pre>
 *
 * <p>Point of this over the Python probe: the Python one proves the protocol is
 * understandable, this one proves <i>our parser</i> understands it. Those are
 * different claims and only the second one ships.
 */
public class OpenRgbProbe {

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6742;

        LightingBackend backend = OpenRgbBackend.pointedAt(host, port);
        boolean ok = backend.connect(Paths.get("."), cls -> true);
        System.out.println("connect(" + host + ":" + port + ") -> " + ok);
        if (!ok) {
            System.out.println("  (is the SDK server enabled in OpenRGB?)");
            return;
        }

        KeyGrid grid = backend.keyGrid();
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        System.out.printf("grid: %d LEDs, aspect %.3f, key width %.4f%n",
                keys.size(), grid.aspectRatio(), grid.keyWidthNormalised());

        String device = null;
        int count = 0;
        for (KeyGrid.LedPosition p : keys) {
            if (!p.ref().deviceId().equals(device)) {
                if (device != null) System.out.printf("    (%d LEDs)%n", count);
                device = p.ref().deviceId();
                count = 0;
                System.out.println("  device " + device);
            }
            if (count < 4) {
                System.out.printf("    luid %3d  norm (%.3f, %.3f)  raw (%.1f, %.1f)%n",
                        p.ref().luid(), p.x(), p.y(), p.rawX(), p.rawY());
            }
            count++;
        }
        if (device != null) System.out.printf("    (%d LEDs)%n", count);

        backend.shutdown();
        System.out.println("shutdown clean");
    }
}
