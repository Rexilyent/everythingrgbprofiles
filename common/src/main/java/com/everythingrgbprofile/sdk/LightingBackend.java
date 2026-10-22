package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

/**
 * One way of talking to lighting hardware. Implement this to add a vendor.
 *
 * <p>Four methods, because four is genuinely everything the render loop ever
 * asked of the Corsair bridge: connect, tell me what LEDs exist and where they
 * are, take this frame, let go. Everything else in this mod — every pattern,
 * every effect, the entire compositor — is written against {@link KeyGrid} and
 * has no idea whatsoever which vendor is underneath it.
 *
 * <h2>The one thing a backend genuinely has to provide</h2>
 * <b>Positions.</b> Not key names, not indices. Physical coordinates.
 *
 * <p>Every pattern in this mod is geometry. The dragon spans a wingspan, sand
 * blows across, bats fly, the moon tracks an arc. All of it computed from
 * normalised x/y. So a backend that can report where its LEDs physically sit
 * gets the entire effect library working for free, and a backend that cannot
 * gets absolutely nothing until somebody supplies a layout for it.
 *
 * <p>That split is the whole reason {@link KeyLayout} exists. Hardware that
 * reports geometry (Corsair via {@code CorsairGetLedPositions}, OpenRGB via
 * its per-zone matrix map) needs no help at all. Hardware that only reports
 * names or a flat list needs a layout file, and writing one of those is a job
 * somebody who owns the device can do without touching a line of Java.
 *
 * <h2>Units genuinely do not matter</h2>
 * {@code KeyGrid.Builder} normalises whatever coordinate space you hand it into
 * 0..1 per device, keeping the raw span around for aspect-ratio correction. So
 * report millimetres, matrix cells, or units you invented this morning, as
 * long as they are proportional and consistently oriented: x increasing
 * rightward, y increasing <b>downward</b>.
 *
 * <p>Getting y upside down is the one mistake here that produces a board which
 * looks entirely plausible and is completely wrong. Rain would fall upward,
 * and it would do it confidently.
 *
 * <h2>Threading</h2>
 * Every method here is called from {@link SdkWorkerThread}, and only from
 * there. Implementations do not need to be thread-safe, and must not spawn
 * threads that call back in.
 *
 * <p>Two threads inside a native SDK do not throw an exception at you. They
 * corrupt memory and let you find out later. That is the entire reason
 * {@link SdkWorkerThread} exists.
 */
public interface LightingBackend {

    /** Stable id used in the config, e.g. {@code "corsair"}. Lowercase, no spaces. */
    String id();

    /** Human-readable name for logs and diagnostics. */
    String displayName();

    /**
     * Attempts to connect and build a key grid.
     *
     * <p>Whichever way this returns, record WHY with {@link BackendHealth}:
     * connected, or which flavour of not installed / not running / refused,
     * along with what the player should try next.
     *
     * <p>Every backend except Corsair is experimental, and that record is both
     * the thing a player reads to fix their own setup and the thing they send
     * when they cannot. A backend that returns false without recording
     * anything gets reported as a gap in the mod, which is fair, because it
     * is one.
     *
     * @param workDir       a writable directory this backend may use for
     *                      extracted natives or cached layouts
     * @param deviceEnabled which device classes the user wants lit; a backend
     *                      should skip anything this rejects rather than
     *                      lighting a headset somebody asked to be left alone
     * @return true if the backend is connected and {@link #keyGrid()} is usable
     */
    boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled);

    /** True while frames can actually be delivered to something. */
    boolean connected();

    /**
     * The devices this backend found. Never null: return
     * {@link KeyGrid#empty()} when not connected.
     *
     * <p>The render loop keeps rendering against the grid even with no
     * hardware attached, specifically so effect state machines do not freeze
     * halfway through an animation and come back wrong when something does
     * connect.
     */
    KeyGrid keyGrid();

    /** Pushes one frame. Any key absent from the map is left exactly as it was. */
    void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame);

    /**
     * Releases the hardware and hands control back to whatever owned it.
     *
     * <p>Must be safe to call having never connected, and safe to call twice.
     * The render loop calls it from a {@code finally}, which means it also runs
     * immediately after a connect that failed.
     */
    void shutdown();
}
