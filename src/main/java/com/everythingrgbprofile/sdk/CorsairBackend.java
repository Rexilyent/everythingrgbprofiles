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
 * rewrite of it. That class is the most fought-over code in the mod — the
 * session-state latch, the contiguous native array, the exclusive-control trap,
 * the reused write buffers — and every one of those details was paid for with a
 * bug. Reshaping it to fit a new interface would risk all of it for no gain,
 * so the interface is satisfied by delegation and the hard-won parts are left
 * exactly where they are.
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
     * Connects inside {@link NativeCrashGuard}, which is why the guard lives
     * here rather than in the bridge: the bridge's connection sequence is left
     * exactly as it is, and only wrapped.
     */
    @Override
    public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        // Only iCUE's own copies count towards "changed since the crash".
        // The bundled copy is unpacked once and never changes, so counting it
        // would only make its first unpacking look like an update.
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
