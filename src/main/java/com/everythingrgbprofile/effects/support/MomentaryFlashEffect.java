package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.List;
import java.util.Map;

/**
 * The Tier 3 counterpart to {@link SustainedOverlayEffect}: fire and forget.
 * {@link #trigger} sets it off and it reports inactive again once
 * {@code durationMillis} has elapsed.
 *
 * <p>No cleanup, no teardown, nothing to remember to call. The compositor
 * simply stops asking and Tiers 1 and 2 reappear by themselves, because they
 * are recomposited from scratch every frame regardless.
 *
 * <h2>Why everything arrives at trigger time</h2>
 * Pattern, colour, accent, duration and target keys all come in as arguments
 * rather than being fixed in the constructor, and that is what lets ONE
 * registered instance serve an escalating effect.
 *
 * <p>The Shrieker Alert hands it a progressively brighter colour and a shorter
 * duration on each shriek; the level-up hands it a two-colour animation where
 * the advancement hands it a single flash. Neither allocates a new controller
 * or registers anything new mid-session.
 *
 * <p>{@code priority} settles who wins when two Tier 3 flashes overlap: the
 * more urgent one preempts. Death outranks lightning, lightning outranks a
 * sensor ping, and nothing outranks death.
 */
public final class MomentaryFlashEffect implements EffectController {

    private final String id;
    private final int priority;

    // All volatile. trigger() is supposed to be called from a job on the
    // worker thread, which is also where render() runs, so in correct use
    // there is no cross-thread traffic here whatsoever.
    //
    // Volatile costs nothing measurable at this scale and means a stray call
    // from some other thread still publishes cleanly rather than being
    // half-seen by a render midway through reading it.
    private volatile long triggeredAtMillis = Long.MIN_VALUE; // sentinel: never fired
    private volatile long durationMillis = 0;
    private volatile RGBColor color = RGBColor.WHITE;
    private volatile RGBColor accentColor = null;
    /** null = whole primary surface. */
    private volatile List<KeyGrid.LedRef> targetKeys = null;
    private volatile Pattern pattern;
    private volatile PatternParams params = PatternParams.EMPTY;

    public MomentaryFlashEffect(String id, int priority) {
        this.id = id;
        this.priority = priority;
    }

    /**
     * Fire it.
     *
     * <p>{@code synchronized} so the whole parameter set lands atomically. It
     * isn't about the individual volatile writes — those are each fine — it's
     * that two triggers racing could interleave into a frankenstate: one
     * caller's colour with another's duration and a third's pattern. Rare,
     * baffling, and exactly the kind of bug you never reproduce on demand.
     */
    public synchronized void trigger(long nowMillis, RGBColor color, RGBColor accentColor,
                                      long durationMillis, List<KeyGrid.LedRef> targetKeys, Pattern pattern, PatternParams params) {
        this.triggeredAtMillis = nowMillis;
        this.color = color;
        this.accentColor = accentColor;
        this.durationMillis = durationMillis;
        this.targetKeys = targetKeys;
        this.pattern = pattern;
        // Normalise null to EMPTY here so render() never has to check.
        this.params = params == null ? PatternParams.EMPTY : params;
    }

    /** Convenience overload for the common no-accent, no-params case. */
    public synchronized void trigger(long nowMillis, RGBColor color, long durationMillis, List<KeyGrid.LedRef> targetKeys, Pattern pattern) {
        trigger(nowMillis, color, null, durationMillis, targetKeys, pattern, PatternParams.EMPTY);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER3_MOMENTARY_FLASH;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Long.MIN_VALUE means "never triggered". Needed because plain
        // (now - triggeredAt) < duration would be true for a never-fired
        // effect with duration 0... and would also overflow spectacularly
        // against MIN_VALUE. Explicit sentinel check, no cleverness.
        return triggeredAtMillis != Long.MIN_VALUE && (nowMillis - triggeredAtMillis) < durationMillis;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        Pattern p = pattern;
        // Read into a local first: pattern is volatile and could in principle
        // be replaced by a concurrent trigger halfway through this method.
        // Snapshot it, then work with the snapshot.
        if (p == null) return Map.of();
        // Real durationMillis passed through here, unlike the sustained case —
        // one-shot patterns shape their entire envelope against it.
        PatternContext ctx = new PatternContext(grid, targetKeys, color, accentColor, durationMillis, params);
        return p.render(ctx, nowMillis - triggeredAtMillis);
    }
}
