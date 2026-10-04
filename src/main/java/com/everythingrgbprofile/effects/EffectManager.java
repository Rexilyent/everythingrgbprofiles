package com.everythingrgbprofile.effects;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.priority.Compositor;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds every effect, sorted into its tier, and produces one composited frame
 * when asked. Lives entirely on the SDK worker thread; see
 * {@code SdkWorkerThread} for why that matters so much.
 *
 * <p>It is three lists and a delegation. All of the actual thinking happens in
 * {@link Compositor}, and this exists so registration and rendering have one
 * obvious home rather than being scattered across whoever needed them.
 *
 * <h2>Registration order does NOT set priority</h2>
 * Stating that plainly because it certainly looks like it might. Inside Tier 1
 * and Tier 3, precedence comes from {@code priority()} and from absolutely
 * nothing else.
 *
 * <p>The order things get registered in affects exactly two things: Tier 2
 * draw order, which decides what lands on top where overlays overlap (see the
 * Tier 2 notes in EffectRegistry, where it is used deliberately), and ties at
 * equal priority.
 */
public final class EffectManager {

    // CopyOnWriteArrayList: written a handful of times at startup, then read
    // every single frame for the rest of the session. That is precisely the
    // access pattern COW was built for. Reads are a plain unsynchronised array
    // walk with no locking at all, and the expensive copy only ever happens
    // during registration.
    //
    // It also makes registering something after startup safe without a lock,
    // because an in-flight render iterates the old snapshot rather than
    // throwing a ConcurrentModificationException at 30Hz.
    private final List<EffectController> tier1 = new CopyOnWriteArrayList<>();
    private final List<EffectController> tier2 = new CopyOnWriteArrayList<>();
    private final List<EffectController> tier3 = new CopyOnWriteArrayList<>();

    /** Files an effect into its tier. The effect declares its own tier, this just sorts the post. */
    public void register(EffectController controller) {
        switch (controller.tier()) {
            case TIER1_OPAQUE_BASE -> tier1.add(controller);
            case TIER2_OVERLAY -> tier2.add(controller);
            case TIER3_MOMENTARY_FLASH -> tier3.add(controller);
        }
    }

    /** One frame, ready for the hardware. All the real logic is over in Compositor. */
    public Map<KeyGrid.LedRef, RGBColor> renderFrame(KeyGrid grid, long nowMillis) {
        return Compositor.composite(tier1, tier2, tier3, grid, nowMillis);
    }

    /**
     * Every registered effect, flattened. Used by diagnostics to report who is
     * active and who owns the board.
     *
     * <p>Builds a fresh list on every call, which is entirely fine because this
     * is only ever reached from the debug path and never from the render loop.
     */
    public List<EffectController> all() {
        List<EffectController> all = new ArrayList<>(tier1);
        all.addAll(tier2);
        all.addAll(tier3);
        return all;
    }
}
