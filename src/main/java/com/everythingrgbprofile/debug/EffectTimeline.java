package com.everythingrgbprofile.debug;

import com.everythingrgbprofile.effects.EffectManager;
import com.everythingrgbprofile.priority.EffectController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The last few hundred times an effect started or stopped, and who owned the
 * board at each, kept whether or not debug logging is on.
 *
 * <p>{@link Diagnostics} answers "did my effect fire, or fire and get drawn
 * over" in the log, but only with {@code [debug] enabled} switched on — and
 * nobody switches it on until after the thing they wanted to catch has
 * happened. A player saying "the portal effect didn't show" has, by then, lost
 * the only evidence there was. This keeps that evidence by default, for
 * {@code /rgbprofiles effects} and the report.
 *
 * <p>The cost is one {@code isActive} call per effect per frame, which the
 * compositor makes anyway, and nothing allocated unless something changed.
 * Like {@link Diagnostics}, it finds changes by comparing the active set frame
 * to frame, so no effect can be added without being covered.
 *
 * <p>Sampled on the SDK worker thread; read from the game thread through
 * {@link #recent} and {@link #current}, which copy under a lock.
 */
public final class EffectTimeline {

    /** One effect starting or stopping. {@code board} is who owned the board just after. */
    public record Event(long atMillis, String effectId, String tier, int priority, boolean started, String board) {
    }

    /** What is active right now. */
    public record Snapshot(long atMillis, List<String> active, String board) {
    }

    private static final int CAPACITY = 300;

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static EffectController[] tracked;
    private static boolean[] wasActive;
    private static volatile Snapshot current = new Snapshot(0, List.of(), "not sampled yet");

    private EffectTimeline() {
    }

    /** Called once per rendered frame from the worker loop. */
    public static void sample(EffectManager manager, long nowMillis) {
        if (tracked == null) {
            // Effects are all registered before the render loop starts and
            // never after, so the list is taken once instead of every frame.
            List<EffectController> all = manager.all();
            tracked = all.toArray(new EffectController[0]);
            wasActive = new boolean[tracked.length];
        }
        boolean changed = current.atMillis() == 0;
        boolean[] active = null;
        for (int i = 0; i < tracked.length; i++) {
            boolean on = safeActive(tracked[i], nowMillis);
            if (on != wasActive[i]) {
                if (active == null) active = wasActive.clone();
                active[i] = on;
                changed = true;
            }
        }
        if (!changed) return;

        String board = Diagnostics.ownership(manager, nowMillis);
        if (active != null) {
            synchronized (EVENTS) {
                for (int i = 0; i < tracked.length; i++) {
                    if (active[i] == wasActive[i]) continue;
                    if (EVENTS.size() >= CAPACITY) EVENTS.removeFirst();
                    EVENTS.addLast(new Event(nowMillis, tracked[i].id(), tier(tracked[i]), tracked[i].priority(),
                            active[i], board));
                }
            }
            wasActive = active;
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tracked.length; i++) {
            if (wasActive[i]) names.add(tracked[i].id() + " (" + tier(tracked[i]) + " prio " + tracked[i].priority() + ")");
        }
        current = new Snapshot(nowMillis, List.copyOf(names), board);
    }

    /** Oldest first. */
    public static List<Event> recent() {
        synchronized (EVENTS) {
            return new ArrayList<>(EVENTS);
        }
    }

    public static Snapshot current() {
        return current;
    }

    private static String tier(EffectController c) {
        return switch (c.tier()) {
            case TIER1_OPAQUE_BASE -> "base";
            case TIER2_OVERLAY -> "overlay";
            case TIER3_MOMENTARY_FLASH -> "flash";
        };
    }

    /** An effect throwing from {@code isActive} is the compositor's to report, not this. */
    private static boolean safeActive(EffectController c, long nowMillis) {
        try {
            return c.isActive(nowMillis);
        } catch (Exception e) {
            return false;
        }
    }
}
