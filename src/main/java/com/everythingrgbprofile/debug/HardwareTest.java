package com.everythingrgbprofile.debug;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.HashMap;
import java.util.Map;

/**
 * A fixed sequence of frames that shows, by eye, whether a backend is driving
 * a keyboard correctly. Started from {@code /rgbprofiles test}.
 *
 * <p>Every backend except Corsair is experimental, and two of the ways they
 * can be wrong produce a board that lights up and looks broken in a way nobody
 * can usefully describe over the internet.
 *
 * <p>A swapped colour channel shows you a biome in the wrong colours with no
 * error logged anywhere; Razer's and OpenRGB's byte order currently rest on
 * documentation rather than on a keyboard anyone here has actually watched. A
 * flipped or scrambled layout sends rain upward, or scatters it into confetti.
 * Ordinary effects hide both of those perfectly, because nobody on earth knows
 * what colour a forest is supposed to be on a keyboard.
 *
 * <p>So this draws things with exactly one right answer, and the step names in
 * chat tell you what that answer is:
 *
 * <ol>
 *   <li>Solid red, green, blue, then white. A wrong colour names the swapped
 *       channel; a tinted white, a weak one.</li>
 *   <li>A bar sweeping left to right, then top to bottom. Backwards is a
 *       flipped axis, a scatter of keys is a wrong layout.</li>
 *   <li>W, A, S and D alone. Keys looked up by their printed label are how
 *       the warnings find their key (H, F, T and K by default), so if these
 *       four land in the wrong places, so will those.</li>
 * </ol>
 */
public final class HardwareTest {

    /** One step of the test: how long it runs, and what you should be seeing while it does. */
    public enum Step {
        RED(2000, "Every key should be RED"),
        GREEN(2000, "Every key should be GREEN"),
        BLUE(2000, "Every key should be BLUE"),
        WHITE(2000, "Every key should be WHITE, with no pink, yellow or blue tint"),
        SWEEP_RIGHT(3000, "A bar should sweep from LEFT to RIGHT"),
        SWEEP_DOWN(2500, "A bar should sweep from TOP to BOTTOM"),
        WASD(3000, "Only the W, A, S and D keys should be lit");

        private final long millis;
        private final String expectation;

        Step(long millis, String expectation) {
            this.millis = millis;
            this.expectation = expectation;
        }

        public String expectation() {
            return expectation;
        }
    }

    private static final RGBColor BAR = RGBColor.WHITE;
    private static final RGBColor KEY_TARGET = RGBColor.fromHex("#00FFFF");

    private HardwareTest() {
    }

    public static long totalMillis() {
        long total = 0;
        for (Step s : Step.values()) total += s.millis;
        return total;
    }

    /** Whichever step is running this far into the test, or null once it's over. */
    public static Step stepAt(long elapsedMillis) {
        if (elapsedMillis < 0) return null;
        long t = elapsedMillis;
        for (Step s : Step.values()) {
            if (t < s.millis) return s;
            t -= s.millis;
        }
        return null;
    }

    /**
     * The frame for this exact moment of the test, covering every LED on every
     * device, or null once the test has finished.
     */
    public static Map<KeyGrid.LedRef, RGBColor> frame(KeyGrid grid, long elapsedMillis) {
        Step step = stepAt(elapsedMillis);
        if (step == null) return null;
        long into = elapsedMillis;
        for (Step s : Step.values()) {
            if (s == step) break;
            into -= s.millis;
        }
        double progress = into / (double) step.millis;

        Map<KeyGrid.LedRef, RGBColor> frame = new HashMap<>();
        java.util.Set<KeyGrid.LedRef> wasd = new java.util.HashSet<>();
        if (step == Step.WASD) {
            for (String key : new String[]{"W", "A", "S", "D"}) {
                KeyGrid.LedRef ref = grid.namedKey(key);
                if (ref != null) wasd.add(ref);
            }
        }
        for (KeyGrid.Surface surface : grid.surfaces()) {
            for (KeyGrid.LedPosition p : surface.leds()) {
                frame.put(p.ref(), switch (step) {
                    case RED -> new RGBColor(255, 0, 0);
                    case GREEN -> new RGBColor(0, 255, 0);
                    case BLUE -> new RGBColor(0, 0, 255);
                    case WHITE -> RGBColor.WHITE;
                    case SWEEP_RIGHT -> bar(p.x(), progress);
                    case SWEEP_DOWN -> bar(p.y(), progress);
                    case WASD -> wasd.contains(p.ref()) ? KEY_TARGET : RGBColor.BLACK;
                });
            }
        }
        return frame;
    }

    /** A soft-edged bar travelling from 0 to 1 along one axis. Soft-edged so a flipped axis is obvious rather than ambiguous. */
    private static RGBColor bar(double position, double progress) {
        double centre = -0.1 + 1.2 * progress;
        double distance = Math.abs(position - centre);
        if (distance >= 0.12) return RGBColor.BLACK;
        double level = 1 - distance / 0.12;
        int v = (int) Math.round(255 * level);
        return new RGBColor(v * BAR.r() / 255, v * BAR.g() / 255, v * BAR.b() / 255);
    }
}
