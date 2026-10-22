import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.BackendHealth;
import com.everythingrgbprofile.sdk.MysticLightBackend;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives the real {@code MysticLightBackend} against an MSI Mystic Light SDK
 * DLL and prints what it found and how it got diagnosed.
 *
 * <pre>
 *   javac --release 21 -cp "common/build/classes/java/main;JNA;JNA_PLATFORM" -d tuning tuning/MysticLightProbe.java
 *   ./gradlew mysticLightProbe -PmlArgs="path/to/MysticLight_SDK_x64.dll"
 * </pre>
 *
 * <p>Not read-only, unlike the OpenRGB probe, and it can't be: connecting is
 * what switches each area to a static style. Shutdown puts the original
 * colour and style back, so the lights should end up how they started.
 *
 * <p>Without Mystic Light running this mostly proves the plumbing: the DLL
 * loads, initialise fails the way MSI documents, and that failure lands in
 * the right diagnosis. Expect to wait about ten seconds for it.
 */
public class MysticLightProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: MysticLightProbe <path to MysticLight_SDK_x64.dll>");
            return;
        }
        Path dll = Path.of(args[0]).toAbsolutePath();
        Path work = Files.createTempDirectory("mysticlight-probe");

        MysticLightBackend backend = MysticLightBackend.loading(dll);
        long started = System.nanoTime();
        boolean ok = backend.connect(work, cls -> true);
        System.out.printf("connect(%s) -> %s in %d ms%n", dll, ok, (System.nanoTime() - started) / 1_000_000);

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
        }
        backend.shutdown();
        System.out.println("shutdown clean");
    }
}
