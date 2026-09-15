package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One way of talking to lighting hardware. Implement this to add a vendor.
 *
 * <p>Four methods, because that is genuinely all the render loop ever asked of
 * the Corsair bridge: connect, tell me what LEDs exist and where, take this
 * frame, let go. Everything else in the mod — every pattern, every effect, the
 * whole compositor — is written against {@link KeyGrid} and has no idea which
 * vendor is underneath.
 *
 * <h2>The one thing a backend really has to provide</h2>
 * <b>Positions.</b> Not key names, not indices — physical coordinates. Every
 * pattern in this mod is geometry: the dragon spans a wingspan, sand blows
 * across, bats fly, the moon tracks an arc. All of that is computed from
 * normalised x/y, so a backend that can report where its LEDs physically are
 * gets the entire effect library working for free, and a backend that cannot
 * gets nothing until someone supplies a layout for it.
 *
 * <p>That split is the whole reason {@link KeyLayout} exists. Hardware that
 * reports geometry (Corsair via {@code CorsairGetLedPositions}, OpenRGB via its
 * per-zone matrix map) needs no help. Hardware that only reports names or a
 * flat list needs a layout file, and writing one is a job a user with the
 * device can do without touching Java.
 *
 * <h2>Units do not matter</h2>
 * {@code KeyGrid.Builder} normalises whatever coordinate space you hand it into
 * 0..1 per device, keeping the raw span for aspect-ratio correction. So report
 * millimetres, matrix cells, or arbitrary units — as long as they are
 * proportional and consistently oriented, with x increasing rightward and y
 * increasing <b>downward</b>. Getting y upside down is the one mistake that
 * produces a board which looks plausible and is wrong: rain would fall upward.
 *
 * <h2>Threading</h2>
 * Every method is called from {@link SdkWorkerThread} and only from there.
 * Implementations do not need to be thread-safe, and should not spawn threads
 * that call back in: two threads inside a native SDK corrupt memory rather than
 * throw, which is the whole reason {@link SdkWorkerThread} exists.
 */
public interface LightingBackend {

    /** Stable id used in config, e.g. {@code "corsair"}. Lowercase, no spaces. */
    String id();

    /** Human-readable name for logs and diagnostics. */
    String displayName();

    /**
     * Attempts to connect and build a key grid.
     *
     * <p>Whichever way this returns, record why with {@link BackendHealth}:
     * connected, or which of not installed, not running, refused and so on,
     * with what the player should try. Every backend but Corsair is
     * experimental, and that record is what a player reads to fix their own
     * setup, and what they send when they cannot. A backend that returns false
     * without recording anything is reported as a gap in the mod.
     *
     * @param workDir       a writable directory this backend may use for
     *                      extracted natives or cached layouts
     * @param deviceEnabled which device classes the user wants lit; a backend
     *                      should skip anything this rejects rather than
     *                      lighting a headset somebody asked to be left alone
     * @return true if the backend is connected and {@link #keyGrid()} is usable
     */
    boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled);

    /** True while frames can actually be delivered. */
    boolean connected();

    /**
     * The devices this backend found. Never null — return
     * {@link KeyGrid#empty()} when not connected, because the render loop keeps
     * rendering against the grid even with no hardware, to stop effect state
     * machines freezing mid-animation.
     */
    KeyGrid keyGrid();

    /** Pushes one frame. Keys absent from the map are left as they were. */
    void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame);

    /**
     * Releases the hardware and hands control back to whatever owned it.
     *
     * <p>Must be safe to call when never connected, and safe to call twice.
     * The render loop calls it from a {@code finally}, which means it also runs
     * after a failed connect.
     */
    void shutdown();
}
