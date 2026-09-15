package com.everythingrgbprofile.debug;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.effects.EffectManager;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The thing you turn on when a keyboard is doing something inexplicable.
 *
 * <p>This class is not general-purpose logging. It answers exactly three
 * questions, chosen because each one has personally cost real hours on this
 * mod and none of them could be answered from the log before.
 *
 * <h2>1. Is my effect firing, or firing and being drawn over?</h2>
 * <b>These look identical on the keyboard and have nothing in common as
 * causes.</b> One is a trigger bug. The other is a compositing bug. Guessing
 * wrong sends you down a completely wasted investigation.
 *
 * <p>Case in point: the portal dwell was firing perfectly, on time, every
 * time — and being blanked by incidental Tier 3 flashes (see
 * {@code EffectController#tier3SuppressionFloor}). From outside it read as
 * "the portal effect only triggers on arrival", which is a sentence describing
 * a bug that did not exist.
 *
 * <p>{@link #sample} logs every activation and deactivation together with who
 * owns the board at that instant, so the two cases separate at a glance.
 *
 * <h2>2. Is the render thread healthy?</h2>
 * The worker runs independently of the client tick, so a client freeze
 * <i>should</i> not stall the animation. "Should" was doing a lot of work in
 * that sentence and there was no way to verify it. {@link #sample} tracks
 * achieved frame rate, render cost, and job-queue depth.
 *
 * <h2>3. Did the hardware come up the way it should have?</h2>
 * LED identity is this mod's most reliable source of bugs (see
 * {@code KeyGrid}'s class doc for the three-namespace incident).
 * {@link #dumpGrid} prints the device roster, LED counts, the derived geometry
 * every pattern depends on, and whether named-key lookup actually resolves.
 *
 * <p>All off by default, costing one boolean read per frame. All of it runs on
 * the SDK worker thread.
 */
public final class Diagnostics {

    private static volatile boolean enabled = false;
    private static volatile boolean logTransitions = true;
    private static volatile long healthIntervalMillis = 10_000;

    private static Set<String> previouslyActive = new HashSet<>();
    private static long lastHealthLogMillis = 0;
    private static long framesSinceHealthLog = 0;
    private static long renderNanosTotal = 0;
    private static long renderNanosMax = 0;
    private static int queueDepthMax = 0;

    // --- biome transition counters -------------------------------------
    private static final AtomicLong biomeSamples = new AtomicLong();
    private static final AtomicLong biomePendingStarts = new AtomicLong();
    private static final AtomicLong biomeReverts = new AtomicLong();
    private static final AtomicLong biomeCommits = new AtomicLong();
    private static volatile long lastCommitMillis = 0;
    private static volatile String lastCommittedBiome = "(none)";
    private static volatile long unresolvedSinceMillis = 0;

    // --- client thread stall tracking -----------------------------------
    private static volatile long lastClientTickMillis = 0;
    private static volatile long worstTickGapMillis = 0;

    public static void configure(boolean on, boolean transitions, long healthIntervalSeconds) {
        enabled = on;
        logTransitions = transitions;
        healthIntervalMillis = Math.max(1000, healthIntervalSeconds * 1000L);
    }

    public static boolean enabled() {
        return enabled;
    }

    /**
     * Called once per rendered frame from the worker loop.
     *
     * <p>Transitions are found by DIFFING THE ACTIVE SET, not by
     * instrumenting each effect's trigger method — and that choice is the
     * whole reason this stays useful. One code path covers every effect, which
     * means nobody can add a new effect and forget to instrument it. The
     * diagnostics simply cannot fall behind the codebase.
     *
     * <p>The cost is resolution: transitions land at frame granularity
     * (~17-33ms at default frame rates). Which is roughly two orders of
     * magnitude finer than anything ever being diagnosed here, so it is not a
     * cost at all.
     */
    public static void sample(EffectManager manager, long nowMillis, long renderNanos, int queueDepth,
                              boolean hardwareConnected) {
        if (!enabled || manager == null) return;

        framesSinceHealthLog++;
        renderNanosTotal += renderNanos;
        renderNanosMax = Math.max(renderNanosMax, renderNanos);
        queueDepthMax = Math.max(queueDepthMax, queueDepth);

        if (logTransitions) {
            Set<String> active = new HashSet<>();
            for (EffectController c : manager.all()) {
                if (safeActive(c, nowMillis)) active.add(c.id());
            }
            if (!active.equals(previouslyActive)) {
                for (EffectController c : manager.all()) {
                    boolean now = active.contains(c.id());
                    boolean before = previouslyActive.contains(c.id());
                    if (now && !before) {
                        RGBProfileMod.LOGGER.info("[rgb] + {} ({} prio {}) | {}",
                                c.id(), shortTier(c.tier()), c.priority(), ownership(manager, nowMillis));
                    } else if (!now && before) {
                        RGBProfileMod.LOGGER.info("[rgb] - {} ({} prio {})",
                                c.id(), shortTier(c.tier()), c.priority());
                    }
                }
                previouslyActive = active;
            }
        }

        if (nowMillis - lastHealthLogMillis >= healthIntervalMillis) {
            if (lastHealthLogMillis > 0 && framesSinceHealthLog > 0) {
                double seconds = (nowMillis - lastHealthLogMillis) / 1000.0;
                RGBProfileMod.LOGGER.info(
                        "[rgb] health: {} fps achieved, render avg {}ms / max {}ms, peak queue {}, hardware {}",
                        String.format("%.1f", framesSinceHealthLog / seconds),
                        String.format("%.2f", renderNanosTotal / (double) framesSinceHealthLog / 1e6),
                        String.format("%.2f", renderNanosMax / 1e6),
                        queueDepthMax,
                        hardwareConnected ? "connected" : "DISCONNECTED");

                long stall = worstTickGapMillis;
                long sinceTick = lastClientTickMillis > 0 ? nowMillis - lastClientTickMillis : -1;
                if (stall > 200) {
                    RGBProfileMod.LOGGER.warn("[rgb] client thread: worst stall {}ms this window "
                            + "(currently {}ms since last tick). Main-thread hooks cannot fire faster than this, "
                            + "so this is the floor on portal arrival latency.", stall, sinceTick);
                } else {
                    RGBProfileMod.LOGGER.info("[rgb] client thread: worst gap {}ms this window (healthy)", stall);
                }
                worstTickGapMillis = 0;
            }
            long commits = biomeCommits.getAndSet(0);
            long samples = biomeSamples.getAndSet(0);
            long pendings = biomePendingStarts.getAndSet(0);
            long reverts = biomeReverts.getAndSet(0);
            if (samples > 0) {
                RGBProfileMod.LOGGER.info(
                        "[rgb] biome: {} samples, {} candidates, {} reverted at the border, {} committed | now in {}",
                        samples, pendings, reverts, commits, lastCommittedBiome);
            }

            lastHealthLogMillis = nowMillis;
            framesSinceHealthLog = 0;
            renderNanosTotal = 0;
            renderNanosMax = 0;
            queueDepthMax = 0;
        }
    }

    /**
     * Who actually owns the board this instant — the winning Tier 3 flash, the
     * Tier 1 base, every active overlay, and any suppression floor holding
     * flashes off.
     *
     * <p><b>This is THE line.</b> It's the one that separates "not firing"
     * from "firing but covered", which is question 1 in the class doc and the
     * single most expensive ambiguity in the whole project. It reimplements
     * the Compositor's resolution logic rather than sharing it, deliberately:
     * diagnostics that borrow the code they're diagnosing tend to agree with
     * it right up until the moment you need them not to.
     */
    public static String ownership(EffectManager manager, long nowMillis) {
        EffectController base = null;
        EffectController flash = null;
        List<String> overlays = new ArrayList<>();
        int floor = 0;

        for (EffectController c : manager.all()) {
            if (!safeActive(c, nowMillis)) continue;
            switch (c.tier()) {
                case TIER1_OPAQUE_BASE -> {
                    if (base == null || c.priority() > base.priority()) base = c;
                    floor = Math.max(floor, c.tier3SuppressionFloor(nowMillis));
                }
                case TIER2_OVERLAY -> {
                    overlays.add(c.id());
                    floor = Math.max(floor, c.tier3SuppressionFloor(nowMillis));
                }
                case TIER3_MOMENTARY_FLASH -> {
                    if (flash == null || c.priority() > flash.priority()) flash = c;
                }
            }
        }

        String suppressed = "";
        if (flash != null && flash.priority() < floor) {
            suppressed = " (suppressed, floor " + floor + ")";
            flash = null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("board: ");
        if (flash != null) {
            sb.append("TIER3 ").append(flash.id()).append(" prio ").append(flash.priority())
                    .append(" has blanked everything beneath");
        } else {
            sb.append("base=").append(base == null ? "none" : base.id());
            sb.append(" overlays=").append(overlays.isEmpty() ? "none" : String.join(",", overlays));
            sb.append(suppressed);
        }
        return sb.toString();
    }

    /**
     * Stamped from the client tick handler. The worker thread compares this
     * against wall time to measure how long the main thread went unserviced.
     *
     * <p><b>This number defines what is even achievable.</b> Every hook this
     * mod can reach — tick poll, clone event, packet handling — runs on the
     * main thread. Nothing can fire promptly while that thread is stalled, no
     * matter how cleverly the detection is written.
     *
     * <p>A clean NeoForge install shows gaps around the 50ms tick period. A
     * 600-mod pack mid dimension-load can show SECONDS. That figure is the
     * hard floor on portal arrival latency, and knowing it is what stopped
     * further optimisation of detection code that was already as fast as the
     * platform permits.
     */
    public static void clientTickSeen(long nowMillis) {
        if (!enabled) return;
        long previous = lastClientTickMillis;
        lastClientTickMillis = nowMillis;
        if (previous > 0) {
            long gap = nowMillis - previous;
            if (gap > worstTickGapMillis) worstTickGapMillis = gap;
        }
    }

    // ===================================================================
    // Biome transitions
    // ===================================================================

    /**
     * One-shot report on whether the biome pipeline's timings can actually
     * produce a smooth transition, logged at startup.
     *
     * <p>Two ways these settings can silently defeat each other, both caught
     * here at startup rather than discovered by squinting at a keyboard:
     *
     * <p><b>An inert debounce.</b> The debounce only does anything if it is
     * LONGER than the sampling interval. {@code pollBiome} sits behind the
     * {@code updateIntervalTicks} gate, so at the default 20 ticks the biome
     * is sampled once per second — against a 250ms debounce. The pending timer
     * has therefore always already expired by the time anyone looks at it, and
     * every change commits on the second consecutive sample no matter what the
     * debounce is set to. The setting appears to work. It does nothing.
     *
     * <p><b>A crossfade shorter than the sample interval.</b> The fade
     * finishes before the next observation, so walking through a gradient
     * reads as a series of discrete steps rather than a blend.
     */
    public static void dumpBiomeTiming(int intervalTicks, long debounceMillis, long crossfadeMillis) {
        if (!enabled) return;
        long sampleMillis = intervalTicks * 50L;
        RGBProfileMod.LOGGER.info("[rgb] biome timing: sample every {}ms ({} ticks), debounce {}ms, crossfade {}ms",
                sampleMillis, intervalTicks, debounceMillis, crossfadeMillis);
        if (debounceMillis <= sampleMillis) {
            RGBProfileMod.LOGGER.warn("[rgb] biome timing: debounce ({}ms) <= sample interval ({}ms), so it is INERT — "
                    + "every change commits on the second consecutive sample. Lower updateIntervalTicks or raise debounceMillis.",
                    debounceMillis, sampleMillis);
        }
        if (sampleMillis >= crossfadeMillis) {
            RGBProfileMod.LOGGER.warn("[rgb] biome timing: sample interval ({}ms) >= crossfade ({}ms), so each fade "
                    + "completes before the next sample and a gradient walk will read as steps, not a blend.",
                    sampleMillis, crossfadeMillis);
        }
    }

    /**
     * Every biome sample. {@code outcome} is one of {@code same},
     * {@code pending} (a new id started the debounce timer), {@code reverted}
     * (walked back before it elapsed) or {@code waiting}.
     */
    public static void biomeSampled(String biomeId, String outcome) {
        if (!enabled) return;
        biomeSamples.incrementAndGet();
        switch (outcome) {
            case "pending" -> biomePendingStarts.incrementAndGet();
            case "reverted" -> biomeReverts.incrementAndGet();
            default -> { }
        }
        if (logTransitions && !"same".equals(outcome)) {
            RGBProfileMod.LOGGER.info("[rgb] biome sample: {} -> {}", biomeId, outcome);
        }
    }

    /**
     * A committed biome change, with everything needed to judge whether the
     * result will look smooth: where the colour came from, how big a jump it
     * is, whether the pattern swapped (which forces a two-motion blend rather
     * than a pure colour fade), and whether this interrupted a fade already in
     * flight.
     */
    public static void biomeCommitted(String biomeId, boolean matchedProfile, RGBColor from, RGBColor to,
                                      String patternKey, boolean patternChanged, double interruptedFadeProgress,
                                      long nowMillis) {
        if (!enabled) return;
        biomeCommits.incrementAndGet();
        long sinceLast = lastCommitMillis > 0 ? nowMillis - lastCommitMillis : -1;
        lastCommitMillis = nowMillis;
        lastCommittedBiome = biomeId;
        if (!logTransitions) return;

        String jump = from == null ? "n/a" : String.format("%.0f%%", 100 * colorDistance(from, to));
        RGBProfileMod.LOGGER.info(
                "[rgb] biome commit: {} via {} | {} -> {} (jump {}) | pattern {}{} | {}{}",
                biomeId,
                matchedProfile ? "json profile" : "DERIVED fallback",
                from == null ? "?" : from.toHex(), to.toHex(), jump,
                patternKey, patternChanged ? " (CHANGED, blending two motions)" : "",
                sinceLast < 0 ? "first commit" : sinceLast + "ms since last commit",
                interruptedFadeProgress >= 0
                        ? String.format(" | INTERRUPTED a fade at %.0f%%", 100 * interruptedFadeProgress)
                        : "");
    }

    /**
     * The biome Holder stopped (or started) resolving to a registry key.
     *
     * <p>WARN level on both edges, including recovery, which is unusual and
     * intentional. While unresolved the biome cannot be named, so no sample is
     * taken and the board just holds its last committed colour — which is
     * indistinguishable from the effect being stuck or broken. Logging the
     * recovery too is what lets you bound the window afterwards and say "ah,
     * it was unresolved for 4 seconds" rather than guessing.
     */
    public static void biomeUnresolved(boolean unresolved, String detail) {
        if (!enabled) return;
        if (unresolved) {
            unresolvedSinceMillis = System.currentTimeMillis();
            RGBProfileMod.LOGGER.warn("[rgb] biome UNRESOLVED: holder has no registry key ({}). "
                    + "No samples will be taken and the board will hold its current colour until this clears.", detail);
        } else {
            long held = unresolvedSinceMillis > 0 ? System.currentTimeMillis() - unresolvedSinceMillis : -1;
            RGBProfileMod.LOGGER.warn("[rgb] biome resolved again after {}ms -> {}", held, detail);
            unresolvedSinceMillis = 0;
        }
    }

    /** 0 = identical, 1 = black vs white. */
    private static double colorDistance(RGBColor a, RGBColor b) {
        double dr = (a.r() - b.r()) / 255.0;
        double dg = (a.g() - b.g()) / 255.0;
        double db = (a.b() - b.b()) / 255.0;
        return Math.sqrt((dr * dr + dg * dg + db * db) / 3.0);
    }

    /** One-shot hardware and geometry report, logged after the grid is built. */
    public static void dumpGrid(KeyGrid grid) {
        if (!enabled) return;
        if (grid == null || grid.isEmpty()) {
            RGBProfileMod.LOGGER.info("[rgb] grid: EMPTY — no devices resolved, nothing will light");
            return;
        }
        TreeMap<String, Integer> perDevice = new TreeMap<>();
        for (KeyGrid.LedPosition p : grid.allKeys()) {
            perDevice.merge(p.ref().deviceId(), 1, Integer::sum);
        }
        RGBProfileMod.LOGGER.info("[rgb] grid: {} LEDs across {} device(s) {}",
                grid.allKeys().size(), perDevice.size(), perDevice);
        RGBProfileMod.LOGGER.info("[rgb] geometry: keyWidthNormalised={} aspectRatio={} centre=({}, {})",
                String.format("%.5f", grid.keyWidthNormalised()),
                String.format("%.5f", grid.aspectRatio()),
                String.format("%.4f", grid.centerX()),
                String.format("%.4f", grid.centerY()));

        // Probes five specific keys, and 'T' is the one that matters: the
        // pillar field anchors its centre there (see CoreEmitterPattern), so a
        // MISS here silently relocates the entire portal spiral to the board
        // centroid instead. Named-key resolution has broken quietly before,
        // hence checking it out loud at startup.
        StringBuilder named = new StringBuilder();
        for (String probe : new String[]{"T", "G", "H", "F", "K"}) {
            KeyGrid.LedRef ref = grid.namedKey(probe);
            named.append(probe).append('=').append(ref == null ? "MISS" : String.valueOf(ref.luid())).append(' ');
        }
        RGBProfileMod.LOGGER.info("[rgb] named keys: {}", named.toString().trim());
    }

    /**
     * {@code isActive} inside a try/catch, because a diagnostic that can crash
     * the render loop is worse than no diagnostic at all. The debugging tool
     * must never be the thing that breaks the lighting.
     */
    private static boolean safeActive(EffectController c, long nowMillis) {
        try {
            return c.isActive(nowMillis);
        } catch (Exception e) {
            return false;
        }
    }

    private static String shortTier(EffectTier tier) {
        return switch (tier) {
            case TIER1_OPAQUE_BASE -> "T1";
            case TIER2_OVERLAY -> "T2";
            case TIER3_MOMENTARY_FLASH -> "T3";
        };
    }

    private Diagnostics() {
    }
}
