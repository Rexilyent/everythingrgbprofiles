package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Corsair iCUE, as a {@link LightingBackend}.
 *
 * <p>Deliberately a thin adapter over {@link CueSdkBridge} rather than a
 * rewrite of it, and that restraint is the point.
 *
 * <p>That class is the most fought-over code in this entire mod: the
 * session-state latch, the contiguous native array, the exclusive-control
 * trap, the reused write buffers. Every single one of those details was paid
 * for with a bug, several of which did not have the decency to throw an
 * exception on the way past.
 *
 * <p>Reshaping it to fit a new interface would put all of that at risk for
 * precisely no gain. So the interface gets satisfied by delegation and the
 * hard-won parts stay exactly where they are.
 */
public final class CorsairBackend implements LightingBackend {

    private final CueSdkBridge bridge = new CueSdkBridge();

    @Override
    public String id() {
        return "corsair";
    }

    @Override
    public String displayName() {
        return "Corsair iCUE";
    }

    /**
     * Connects inside {@link NativeCrashGuard}, which is exactly why the guard
     * lives out here rather than in the bridge. The bridge's connection
     * sequence is left untouched and merely wrapped.
     */
    @Override
    public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        // Only iCUE's own installed copies count toward "has anything changed
        // since the crash". The bundled copy is unpacked once and then never
        // changes again, so counting it would make its very first unpacking
        // look like a driver update and wrongly clear the guard.
        List<Path> libraries = CueSdkBridge.installedLibraries();
        try {
            NativeCrashGuard.checkBefore(workDir, id(), displayName(), libraries);
        } catch (BackendHealth.Unavailable skipped) {
            BackendHealth.record(id(), displayName(), skipped);
            return false;
        }
        NativeCrashGuard.enter(workDir, id(), libraries);
        try {
            bridge.connect(workDir, deviceEnabled);
        } finally {
            NativeCrashGuard.exit(workDir, id());
        }
        return connected();
    }

    @Override
    public boolean connected() {
        return bridge.mode() == CueSdkBridge.Mode.CONNECTED;
    }

    @Override
    public KeyGrid keyGrid() {
        return bridge.keyGrid();
    }

    @Override
    public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        bridge.applyFrame(frame);
    }

    @Override
    public void shutdown() {
        bridge.shutdown();
    }
}
