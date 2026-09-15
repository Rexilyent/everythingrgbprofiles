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
 * when asked. Lives entirely on the SDK worker thread (see
 * {@code SdkWorkerThread}).
 *
 * <p>It is basically three lists and a delegation. All the actual thinking is
 * in {@link Compositor}; this exists so that registration and rendering have
 * one obvious home.
 *
 * <h2>Registration order does NOT set priority</h2>
 * Worth stating plainly because it looks like it might. Within Tier 1 and
 * Tier 3, precedence comes from {@code priority()} and nothing else. The order
 * effects are registered in only affects Tier 2 draw order (where everyone
 * draws anyway, so it rarely matters) and ties at equal priority.
 */
public final class EffectManager {

    // CopyOnWriteArrayList: written a handful of times at startup, then read
    // every single frame forever. That's the exact access pattern COW is built
    // for — reads are a plain unsynchronised array walk with no locking, and
    // the expensive copy-on-write only happens during registration.
    //
    // It also makes any registration after startup safe without a lock, since
    // an in-flight render iterates the old snapshot instead of throwing a
    // ConcurrentModificationException.
    private final List<EffectController> tier1 = new CopyOnWriteArrayList<>();
    private final List<EffectController> tier2 = new CopyOnWriteArrayList<>();
    private final List<EffectController> tier3 = new CopyOnWriteArrayList<>();

    /** Files an effect into its tier. The effect declares its own tier; we just sort the post. */
    public void register(EffectController controller) {
        switch (controller.tier()) {
            case TIER1_OPAQUE_BASE -> tier1.add(controller);
            case TIER2_OVERLAY -> tier2.add(controller);
            case TIER3_MOMENTARY_FLASH -> tier3.add(controller);
        }
    }

    /** One frame, ready for the hardware. All the real logic is in Compositor. */
    public Map<KeyGrid.LedRef, RGBColor> renderFrame(KeyGrid grid, long nowMillis) {
        return Compositor.composite(tier1, tier2, tier3, grid, nowMillis);
    }

    /**
     * Every registered effect, flattened. Used by diagnostics to report who is
     * active and who owns the board.
     *
     * <p>Builds a fresh list each call — fine, because this is only ever hit
     * from the debug path, never from the render loop.
     */
    public List<EffectController> all() {
        List<EffectController> all = new ArrayList<>(tier1);
        all.addAll(tier2);
        all.addAll(tier3);
        return all;
    }
}
