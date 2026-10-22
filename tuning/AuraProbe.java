import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.AuraBackend;
import com.everythingrgbprofile.sdk.BackendHealth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Drives the real {@code AuraBackend} against whatever Armoury Crate has
 * installed and prints what it found and how it got diagnosed.
 *
 * <pre>
 *   javac --release 21 -cp "common/build/classes/java/main;JNA;JNA_PLATFORM" -d tuning tuning/AuraProbe.java
 *   ./gradlew auraProbe                  (connect, report, release)
 *   ./gradlew auraProbe -PauraArgs=--send   (also paints every LED red for two seconds)
 * </pre>
 *
 * <p>Connecting takes lighting control from Armoury Crate, and shutdown hands
 * it back, at which point Armoury Crate puts its own profile back on. With no
 * ASUS devices attached that is invisible, and this mostly proves the COM
 * side: the object creates, control switches, enumeration comes back empty
 * and everything releases cleanly.
 */
public class AuraProbe {

    public static void main(String[] args) throws Exception {
        boolean send = args.length > 0 && args[0].equals("--send");
        Path work = Files.createTempDirectory("aura-probe");

        AuraBackend backend = new AuraBackend();
        long started = System.nanoTime();
        boolean ok = backend.connect(work, cls -> true);
        System.out.printf("connect -> %s in %d ms%n", ok, (System.nanoTime() - started) / 1_000_000);

        for (BackendHealth.Diagnosis d : BackendHealth.diagnoses()) {
            if (!d.backendId().equals(backend.id())) continue;
            System.out.println("state:   " + d.state() + " (" + d.state().label() + ")");
            System.out.println("summary: " + d.summary());
            System.out.println("fix:     " + d.fix());
            System.out.println("detail:\n" + d.detail());
        }
        for (BackendHealth.Problem p : BackendHealth.problems()) {
            System.out.println("problem: " + p.message());
        }

        if (ok) {
            for (KeyGrid.Surface s : backend.keyGrid().surfaces()) {
                System.out.printf("  %s '%s' %s, %d LEDs%n", s.deviceId(), s.model(), s.deviceClass(), s.leds().size());
            }
            if (send) {
                Map<KeyGrid.LedRef, RGBColor> frame = new HashMap<>();
                for (KeyGrid.LedPosition p : backend.keyGrid().allKeys()) frame.put(p.ref(), new RGBColor(255, 0, 0));
                long until = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < until) {
                    backend.applyFrame(frame);
                    Thread.sleep(50);
                }
                System.out.println("sent red for two seconds, connected=" + backend.connected());
            }
        }
        backend.shutdown();
        System.out.println("shutdown clean");
    }
}
