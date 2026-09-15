package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a {@link Pattern} needs to draw one frame, bundled so that
 * {@code render} takes two arguments instead of seven.
 *
 * <p>Contents: the physical key layout, which LEDs this pattern is allowed to
 * touch, its base and optional accent colour, a nominal duration, and any
 * pattern-specific tuning from a profile's {@code patternParams}.
 *
 * @param targetKeys      null means "the whole primary surface" — the common
 *                        case, and cheaper than materialising a full key list
 *                        for every effect that just wants everything
 * @param accentColor     may be null; see {@link #resolvedAccentColor()}
 * @param durationMillis  nominal length, for patterns that shape themselves
 *                        against their own runtime. Plenty of patterns ignore
 *                        it entirely (the endless ones, and the emitter, which
 *                        derives everything from its own settings) — passing 0
 *                        for those is normal and not a bug
 */
public record PatternContext(
        KeyGrid grid,
        List<KeyGrid.LedRef> targetKeys,
        RGBColor baseColor,
        RGBColor accentColor,
        long durationMillis,
        PatternParams params
) {

    /**
     * The accent, or a lightened base if nobody supplied one.
     *
     * <p>0.6 toward white is the tuned value: enough to read as a distinct
     * highlight against the base, not so much that everything converges on
     * white and every biome ends up looking the same at peak brightness.
     */
    public RGBColor resolvedAccentColor() {
        return accentColor != null ? accentColor : baseColor.lightened(0.6);
    }

    /**
     * The LEDs this pattern may write to.
     *
     * <p>Note both branches are already safe to use blindly: a null target
     * list resolves against the <b>real</b> primary surface (an earlier version
     * built a synthetic grid, which meant patterns could happily animate keys
     * that don't physically exist), and an explicit list has already been
     * validated — EffectRegistry resolves config labels through
     * {@code KeyGrid.namedKey} at startup and drops anything the hardware
     * doesn't have. So no pattern needs to bounds-check anything. Enjoy.
     */
    public List<KeyGrid.LedRef> effectiveTargetKeys() {
        if (targetKeys != null) return targetKeys;
        List<KeyGrid.LedRef> out = new ArrayList<>(grid.allKeys().size());
        for (KeyGrid.LedPosition p : grid.allKeys()) out.add(p.ref());
        return out;
    }

    /**
     * Same set, but with coordinates attached — for the geometric patterns
     * (spiral, ring, sweep, emitter) that need to know where keys physically
     * are rather than just which ones exist.
     *
     * <p>The null-check inside the loop handles a target ref with no known
     * position, which shouldn't happen after startup validation but costs one
     * comparison to not crash over.
     */
    public List<KeyGrid.LedPosition> effectiveTargetPositions() {
        if (targetKeys == null) return grid.allKeys();
        List<KeyGrid.LedPosition> out = new ArrayList<>(targetKeys.size());
        for (KeyGrid.LedRef ref : targetKeys) {
            KeyGrid.LedPosition p = grid.position(ref);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** Same context, different base colour. Records are immutable, so changing a field means making a copy. */
    public PatternContext withColor(RGBColor color) {
        return new PatternContext(grid, targetKeys, color, accentColor, durationMillis, params);
    }
}
