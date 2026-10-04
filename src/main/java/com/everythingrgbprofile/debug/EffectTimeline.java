package com.everythingrgbprofile.debug;

import com.everythingrgbprofile.effects.EffectManager;
import com.everythingrgbprofile.priority.EffectController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The last few hundred times an effect started or stopped, and who owned the
 * board at each one, kept whether or not debug logging is switched on.
 *
 * <p>{@link Diagnostics} answers "did my effect fire, or fire and get drawn
 * over" in the log, but only with {@code [debug] enabled} turned on. And
 * nobody turns that on until after the thing they were trying to catch has
 * already happened. Somebody saying "the portal effect didn't show" has, by
 * that point, lost the only evidence there ever was. So this keeps the
 * evidence by default, for {@code /rgbprofiles effects} and for the report.
 *
 * <p>The cost is one {@code isActive} call per effect per frame, which the
 * compositor was making anyway, and zero allocation unless something actually
 * changed. Same approach as {@link Diagnostics}: changes get found by
 * comparing the active set frame to frame, so there is no way to add an effect
 * and accidentally leave it uncovered.
 *
 * <p>Sampled on the SDK worker thread, read from the game thread through
 * {@link #recent} and {@link #current}, both of which copy under a lock.
 */
public final class EffectTimeline {

    /** One effect starting or stopping. {@code board} is whoever owned the board immediately after. */
    public record Event(long atMillis, String effectId, String tier, int priority, boolean started, String board) {
    }

    /** Whatever is active right this second. */
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
            // Every effect gets registered before the render loop starts and
            // none are ever added after, so the list is grabbed once here
            // rather than rebuilt on every single frame.
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

    /** Oldest first, which is the order you want when reading it as a story. */
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

    /** An effect throwing out of {@code isActive} is the compositor's problem to report, not ours. */
    private static boolean safeActive(EffectController c, long nowMillis) {
        try {
            return c.isActive(nowMillis);
        } catch (Exception e) {
            return false;
        }
    }
}
