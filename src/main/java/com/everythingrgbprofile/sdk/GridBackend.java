package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Shared plumbing for vendors that expose a keyboard as a fixed grid.
 *
 * <p>Razer, Logitech and SteelSeries all work the same way underneath: the SDK
 * does not tell you what keyboard is attached or where its keys are. It hands
 * you a rectangle — 22x6 for Razer and SteelSeries, 21x6 for Logitech — and
 * maps that rectangle onto whatever physical board is plugged in. Every game
 * that supports these vendors draws into that rectangle, and so does this.
 *
 * <p>That makes geometry free, which is the one thing every pattern needs: cell
 * (row, col) simply <i>is</i> the position. It is an approximation — a real
 * board has staggered rows and a gap above the number row — but it is the same
 * approximation the vendor made, and it is good enough for weather, bosses and
 * blood. A {@link KeyLayout} can still correct it per board, using cell names
 * of the form {@code R2C6} (row 2, column 6).
 *
 * <h2>The limitation worth knowing</h2>
 * None of these SDKs can report whether a keyboard exists. They answer
 * "connected" if the vendor software is running, full stop — G HUB installed
 * for a mouse says exactly the same thing as G HUB with a G915 attached. That is
 * why {@link BackendRegistry}'s auto mode drives every backend that answers
 * rather than trusting the first one: a grid backend with no keyboard behind it
 * costs a few idle requests, whereas picking it over a real keyboard would cost
 * the user their lighting.
 *
 * <h2>What subclasses do</h2>
 * Open the vendor session, push a whole grid, optionally keep the session
 * alive, and close. Dirty tracking, frame-rate capping, keep-alive timing and
 * giving up after repeated failures all live here so each vendor file is only
 * about its vendor.
 */
abstract class GridBackend implements LightingBackend {

    protected final int cols;
    protected final int rows;
    private final String deviceId;
    /** One packed 0xRRGGBB per cell, row-major. Reused every frame. */
    private final int[] cells;
    private final long minPushNanos;
    private final long keepAliveNanos;
    private final boolean pushCountsAsKeepAlive;

    private KeyGrid grid = KeyGrid.empty();
    private Path workDir = Path.of(".");
    private boolean connected;
    private boolean dirty = true;
    private long lastPushNanos;
    private long lastKeepAliveNanos;

    /**
     * @param maxPushHz             frame-rate cap for this vendor's transport
     * @param keepAliveMillis       how often {@link #keepAlive} runs
     * @param pushCountsAsKeepAlive true if a delivered frame resets the
     *                              keep-alive timer (SteelSeries), false if the
     *                              vendor wants heartbeats regardless (Razer)
     */
    protected GridBackend(String deviceId, int cols, int rows, int maxPushHz,
                          long keepAliveMillis, boolean pushCountsAsKeepAlive) {
        this.deviceId = deviceId;
        this.cols = cols;
        this.rows = rows;
        this.cells = new int[cols * rows];
        this.minPushNanos = 1_000_000_000L / Math.max(1, maxPushHz);
        this.keepAliveNanos = keepAliveMillis * 1_000_000L;
        this.pushCountsAsKeepAlive = pushCountsAsKeepAlive;
    }

    /** Name used in logs and for matching layout files. */
    protected abstract String modelName();

    /**
     * Starts the vendor session.
     *
     * <p>To fail, throw {@link BackendHealth.Unavailable} with what went wrong
     * and what the player can do about it. Anything else thrown is sorted by
     * {@link BackendHealth#recordFailure}: nothing answering becomes
     * {@link #notRunningSummary()}, and the rest is reported as a mod error.
     */
    protected abstract void open() throws Exception;

    /** The writable directory {@code connect} was given, for extracted files and the crash guard. */
    protected Path workDir() {
        return workDir;
    }

    /** What to tell a player when the vendor service does not answer at all. */
    protected abstract String notRunningSummary();

    /** What to suggest in that case. */
    protected abstract String notRunningFix();

    /**
     * Records why the transport was given up on mid-game. The default says the
     * vendor software stopped answering; a vendor that can tell a closed
     * program from a rejected payload overrides this to say which.
     */
    protected void reportLost(String detail) {
        BackendHealth.record(id(), displayName(), BackendHealth.State.LOST,
                displayName() + " stopped accepting lighting mid-game: closed, restarting or updating.",
                "Once it is running again, restart Minecraft to reconnect.", detail);
    }

    /** The most recent transport failure, for the diagnosis. Null if the vendor has nothing to add. */
    protected String transportDetail() {
        return null;
    }

    /**
     * Sends the whole grid, one packed 0xRRGGBB per cell, row-major.
     *
     * @return false if the transport was busy and nothing was sent, in which
     *         case the grid stays dirty and is retried next frame
     */
    protected abstract boolean push(int[] rgb) throws Exception;

    /** Keeps the vendor session from timing out. Default: nothing needed. */
    protected void keepAlive() throws Exception {
    }

    /** True once the transport has failed badly enough to stop trying. */
    protected boolean transportLost() {
        return false;
    }

    /** Hands control back to the vendor software. Must tolerate a half-opened session. */
    protected abstract void close();

    /** Documented key positions as {row, col}, for effects that target a letter. Default: none. */
    protected Map<Character, int[]> namedCells() {
        return Map.of();
    }

    @Override
    public final boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        if (!deviceEnabled.test(KeyGrid.DeviceClass.KEYBOARD)) {
            BackendHealth.record(id(), displayName(), BackendHealth.State.DISABLED,
                    "Keyboard lighting is turned off in the config, and " + displayName()
                            + " can only light keyboards.",
                    "Set keyboardEnabled = true in the [devices] section of everythingrgbprofiles-common.toml.",
                    null);
            return false;
        }
        this.workDir = workDir;
        try {
            open();
        } catch (Throwable t) {
            // Throwable, because the Logitech path is a native library, and the
            // usual reason a native library fails is an Error, not an Exception.
            BackendHealth.recordFailure(id(), displayName(), "while connecting", t,
                    notRunningSummary(), notRunningFix());
            safeClose();
            return false;
        }
        grid = buildGrid();
        connected = true;
        lastKeepAliveNanos = System.nanoTime();
        BackendHealth.record(id(), displayName(), BackendHealth.State.CONNECTED,
                "Session open (" + cols + "x" + rows + " grid). " + displayName()
                        + " says this even with no keyboard plugged in, so it does not prove one is there.",
                null, null);
        return true;
    }

    private KeyGrid buildGrid() {
        List<double[]> fallback = new ArrayList<>(cells.length);
        List<String> names = new ArrayList<>(cells.length);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                fallback.add(new double[]{r * cols + c, c, r});
                names.add("R" + r + "C" + c);
            }
        }
        List<double[]> laidOut = KeyLayout.positionsFor(modelName(), names, fallback);

        KeyGrid.Builder b = new KeyGrid.Builder();
        b.beginDevice(deviceId, modelName(), KeyGrid.DeviceClass.KEYBOARD);
        for (double[] p : laidOut) b.addLed((int) p[0], p[1], p[2]);
        b.endDevice();
        for (Map.Entry<Character, int[]> e : namedCells().entrySet()) {
            int[] rc = e.getValue();
            b.addNamedKey(e.getKey(), new KeyGrid.LedRef(deviceId, rc[0] * cols + rc[1]));
        }
        return b.build();
    }

    @Override
    public final void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (!connected) return;
        for (Map.Entry<KeyGrid.LedRef, RGBColor> e : frame.entrySet()) {
            KeyGrid.LedRef ref = e.getKey();
            if (!ref.deviceId().equals(deviceId)) continue;
            int i = ref.luid();
            if (i < 0 || i >= cells.length) continue;
            RGBColor c = e.getValue();
            int packed = (c.r() & 0xFF) << 16 | (c.g() & 0xFF) << 8 | (c.b() & 0xFF);
            if (cells[i] != packed) {
                cells[i] = packed;
                dirty = true;
            }
        }

        long now = System.nanoTime();
        try {
            // Dirty tracking matters more here than for Corsair: every push is a
            // whole-board request to another process, and most frames of an
            // ambient biome change nothing at all.
            if (dirty && now - lastPushNanos >= minPushNanos && push(cells)) {
                dirty = false;
                lastPushNanos = now;
                if (pushCountsAsKeepAlive) lastKeepAliveNanos = now;
            }
            if (now - lastKeepAliveNanos >= keepAliveNanos) {
                keepAlive();
                lastKeepAliveNanos = now;
            }
            if (transportLost()) {
                connected = false;
                reportLost(transportDetail());
            }
        } catch (Throwable t) {
            connected = false;
            BackendHealth.recordFailure(id(), displayName(), "while sending a frame", t,
                    notRunningSummary(), notRunningFix());
        }
    }

    @Override
    public final boolean connected() {
        return connected;
    }

    @Override
    public final KeyGrid keyGrid() {
        return grid;
    }

    @Override
    public final void shutdown() {
        connected = false;
        safeClose();
    }

    private void safeClose() {
        try {
            close();
        } catch (Throwable ignored) {
            // Shutdown runs from a finally in the render loop. Nothing thrown
            // here is actionable, and throwing would mask whatever put us there.
        }
    }
}
