package com.everythingrgbprofile.sdk;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.debug.Diagnostics;
import com.everythingrgbprofile.debug.EffectTimeline;
import com.everythingrgbprofile.debug.HardwareTest;
import com.everythingrgbprofile.effects.support.BiomeColorEffect;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.effects.EffectManager;
import com.everythingrgbprofile.effects.EffectRegistry;
import com.everythingrgbprofile.keymap.KeyGrid;

/**
 * The one thread that owns the connection to the lighting hardware, whichever
 * backend that turns out to be, and the reason the lighting stays smooth while
 * Minecraft stalls for five seconds loading chunks.
 *
 * <h2>The rule</h2>
 * <b>Every native call and every effect state mutation happens here.</b> Event
 * handlers on the client thread never touch the bridge or an effect object
 * directly — they call {@link #enqueue(Runnable)} with a small job and get on
 * with their lives.
 *
 * <p>This isn't architectural fussiness. The Corsair and Logitech SDKs are
 * native DLLs reached through JNA, and two threads calling into one
 * concurrently doesn't throw an exception you can catch and log; it corrupts
 * memory and takes the JVM down with no stack trace. Logitech's SDK goes further
 * and initialises itself per thread. Single-threaded ownership is the only
 * defence, and it's cheap.
 *
 * <p>The happy side effect is independence: this loop runs at
 * {@code animationFrameRateHz} regardless of what the game thread is doing. A
 * portal transition keeps spinning at full frame rate through the entire chunk
 * -loading freeze. (Which, memorably, turned out to be a
 * <i>problem</i> — see {@code PortalTransitionEffect}, where the animation
 * happily finished while the player couldn't see anything.)
 */
public final class SdkWorkerThread {

    private static volatile SdkWorkerThread instance;

    private final ExecutorService executor;
    /**
     * Whichever vendor backend this session is driving.
     *
     * <p>Resolved once at startup by {@link BackendRegistry} and never swapped
     * afterwards. Everything below this line is written against the interface,
     * so adding a vendor does not touch the render loop at all.
     */
    private final LightingBackend bridge = BackendRegistry.select();
    private final EffectManager effectManager = new EffectManager();
    private final EffectRegistry effects = new EffectRegistry(effectManager);
    // ConcurrentLinkedQueue: many client-thread producers, one worker
    // consumer, unbounded and lock-free. The client thread must NEVER block
    // to hand off a job, because a game stutter caused by the lighting queue is
    // the one cost this mod must never impose on the game itself.
    private final ConcurrentLinkedQueue<Runnable> jobQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Published by the worker for diagnostics on the game thread. Plain
    // volatiles, written once or once a frame; nothing here is worth a lock.
    private volatile KeyGrid publishedGrid = KeyGrid.empty();
    private volatile String publishedName = "not connected yet";
    private volatile boolean publishedConnected;
    private volatile long framesSent;
    private volatile long lastLoopMillis;
    private volatile String stoppedBecause;
    /** When the hardware test was asked to start, or {@link Long#MIN_VALUE} for no test. */
    private volatile long testStartMillis = Long.MIN_VALUE;

    /**
     * The lighting pipeline as the game thread may see it.
     *
     * @param lastLoopMillis when the render loop last went round; a stale value
     *                       with {@code stoppedBecause} null means it is stuck
     * @param stoppedBecause why the render loop ended, or null while it runs
     */
    public record Status(String displayName, boolean connected, boolean connecting, KeyGrid grid,
                         long framesSent, long lastLoopMillis, String stoppedBecause, long testStartMillis) {
    }

    private SdkWorkerThread() {
        ThreadFactory threadFactory = runnable -> {
            Thread t = new Thread(runnable, "RGBProfile-SDK-Worker");
            // Daemon, so a stuck native call can never prevent the JVM from
            // exiting. If Minecraft wants to quit, it quits, with or without
            // our permission.
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public static void startAsync(Path dllExtractDir) {
        SdkWorkerThread worker = new SdkWorkerThread();
        instance = worker;
        worker.running.set(true);
        worker.executor.submit(() -> worker.runLoop(dllExtractDir));

        // Two shutdown paths, deliberately.
        //
        // Shutting down is what hands lighting back to the user's own
        // software: Corsair removes our layer, Razer and SteelSeries close
        // their sessions, Logitech restores the lighting it saved at connect.
        // Skip it and the board keeps showing our last frame — for up to
        // fifteen seconds with the HTTP vendors, indefinitely if something
        // holds the session open — which from the user's side looks exactly
        // like the mod broke their keyboard. Registering here as well as via
        // the FML lifecycle hook (see RGBProfileMod) means it takes two
        // independent failures to skip it. Running it twice is harmless.
        Runtime.getRuntime().addShutdownHook(new Thread(SdkWorkerThread::shutdown, "RGBProfile-Shutdown-Hook"));
    }

    /**
     * Hands a small state-change job to the worker. Safe from any thread, and
     * a <b>silent no-op</b> if the worker was never started — gating failed,
     * no hardware, mod disabled, wrong OS.
     *
     * <p>That silence is the point. Every event handler in the mod can call
     * this unconditionally without first asking whether lighting exists, which
     * is what keeps "zero cost when irrelevant" from turning into a null-check
     * at every call site.
     */
    public static void enqueue(Runnable job) {
        SdkWorkerThread current = instance;
        if (current != null && current.running.get()) {
            current.jobQueue.add(job);
        }
    }

    /** The pipeline's state for diagnostics, or null if the worker was never started. */
    public static Status status() {
        SdkWorkerThread w = instance;
        if (w == null) return null;
        return new Status(w.publishedName, w.publishedConnected, !BackendHealth.connectFinished(),
                w.publishedGrid, w.framesSent, w.lastLoopMillis, w.stoppedBecause, w.testStartMillis);
    }

    /**
     * Runs {@link HardwareTest} in place of the effects, from {@code startMillis}.
     *
     * @return false if there is no connected hardware to test
     */
    public static boolean startHardwareTest(long startMillis) {
        SdkWorkerThread w = instance;
        if (w == null || !w.running.get() || !w.publishedConnected) return false;
        w.testStartMillis = startMillis;
        return true;
    }

    public static EffectRegistry effects() {
        SdkWorkerThread current = instance;
        return current != null ? current.effects : null;
    }

    public static void shutdown() {
        SdkWorkerThread current = instance;
        if (current == null) return;
        // Clearing the flag ends the loop, whose finally block calls
        // bridge.shutdown() — so the release happens on the worker thread,
        // the only thread allowed to make native calls. Calling
        // bridge.shutdown() from here would be exactly the cross-thread native
        // call this entire class exists to prevent.
        current.running.set(false);
        current.executor.shutdown();
    }

    /**
     * The render loop. Connect, register, then forever: drain jobs, render a
     * frame, push it, sleep the remainder.
     */
    private void runLoop(Path dllExtractDir) {
        try {
            runLoopInner(dllExtractDir);
        } catch (Throwable t) {
            // The executor would otherwise keep this to itself: a task that
            // throws just ends, and the board freezes on its last frame with
            // nothing in the log. An earlier version had exactly that hole.
            stoppedBecause = "The lighting thread stopped after an error: " + t;
            BackendHealth.bug("Lighting thread", "Stopped after an unexpected error, so the lighting is "
                    + "frozen until Minecraft restarts.", t);
        }
    }

    private void runLoopInner(Path dllExtractDir) {
        try {
            boolean connected;
            try {
                connected = bridge.connect(dllExtractDir, SdkWorkerThread::deviceClassEnabled);
            } catch (Throwable t) {
                BackendHealth.recordFailure(bridge.id(), bridge.displayName(), "while connecting", t,
                        bridge.displayName() + " did not answer.", BackendHealth.SEND_REPORT);
                connected = false;
            }
            RGBProfileMod.LOGGER.info("RGB Profile: backend '{}' ({}) {}.",
                    bridge.id(), bridge.displayName(),
                    connected ? "connected" : "not connected - running without hardware");
            publishedGrid = bridge.keyGrid();
            publishedName = connected ? bridge.displayName() : "no lighting connected";
            publishedConnected = connected;
            List<String> followers = bridge instanceof BackendRegistry.Mirror mirror
                    ? mirror.followerIds() : List.of();
            BackendHealth.connectFinished(connected ? bridge.id() : null, followers);
            // Effects are registered AFTER connecting, because several of them
            // resolve config key labels against the real KeyGrid and need to
            // know what hardware actually exists.
            effects.registerAll(bridge.keyGrid());

            Diagnostics.configure(
                    RGBProfileConfig.DEBUG_ENABLED.get(),
                    RGBProfileConfig.DEBUG_LOG_TRANSITIONS.get(),
                    RGBProfileConfig.DEBUG_HEALTH_INTERVAL_SECONDS.get());
            Diagnostics.dumpGrid(bridge.keyGrid());
            Diagnostics.dumpBiomeTiming(
                    RGBProfileConfig.BIOME_SAMPLE_INTERVAL_TICKS.get(),
                    RGBProfileConfig.DEBOUNCE_MILLIS.get(),
                    BiomeColorEffect.crossfadeMillis());

            // Read ONCE, outside the loop. Changing the frame rate needs a
            // restart, which is a deliberate trade: re-reading a config value
            // 60 times a second forever, to support a setting nobody changes
            // mid-session, is not a good deal.
            long frameIntervalNanos = 1_000_000_000L / Math.max(1, RGBProfileConfig.ANIMATION_FRAME_RATE_HZ.get());

            while (running.get()) {
                long frameStart = System.nanoTime();
                long now = System.currentTimeMillis();

                int queueDepthAtFrameStart = jobQueue.size();

                // Drain every pending job before rendering, so this frame sees
                // the freshest state. Each job is individually try/caught: one
                // misbehaving state change must not kill the render loop and
                // leave the board frozen on whatever it happened to be
                // showing. Log it, drop it, keep going.
                Runnable job;
                while ((job = jobQueue.poll()) != null) {
                    try {
                        job.run();
                    } catch (Exception e) {
                        BackendHealth.bug("Game events", "Updating a lighting effect from the game threw, "
                                + "and that update was skipped.", e);
                    }
                }

                boolean live = bridge.connected();
                publishedConnected = live;
                try {
                    // No hardware, but STILL RENDER. Looks wasteful; isn't.
                    //
                    // Effects hold timing state — activation timestamps,
                    // envelope positions, escalation levels — that only
                    // advances when rendered. Skip this and a reconnect would
                    // resume a health flash that thinks it started an hour ago
                    // and a portal arrival mid-dissolve. We throw the pixels
                    // away and keep the state machines honest; the expensive
                    // part (the native applyFrame) is what's actually skipped.
                    var frame = effectManager.renderFrame(bridge.keyGrid(), now);
                    long testStart = testStartMillis;
                    if (testStart != Long.MIN_VALUE) {
                        var test = HardwareTest.frame(bridge.keyGrid(), now - testStart);
                        if (test != null) {
                            frame = test;
                        } else {
                            testStartMillis = Long.MIN_VALUE;
                        }
                    }
                    if (live) {
                        bridge.applyFrame(frame);
                        framesSent++;
                    }
                } catch (Exception e) {
                    // One effect throwing must not end the loop, or every other
                    // effect goes dark with it. The frame is dropped and the
                    // error kept for the report, logged once however often it
                    // repeats.
                    BackendHealth.bug("Rendering", "A lighting effect threw while drawing a frame, "
                            + "so that frame was skipped.", e);
                }
                lastLoopMillis = now;
                try {
                    EffectTimeline.sample(effectManager, now);
                } catch (Exception e) {
                    BackendHealth.bug("Effect timeline", "Recording which effects are active threw.", e);
                }

                long elapsed = System.nanoTime() - frameStart;

                Diagnostics.sample(effectManager, now, elapsed, queueDepthAtFrameStart,
                        bridge.connected());

                // Sleep only the leftover. If a frame overran its budget,
                // sleepNanos is negative and we go straight into the next one
                // — degrading to "as fast as possible" rather than
                // accumulating drift or, worse, sleeping a negative duration.
                long sleepNanos = frameIntervalNanos - elapsed;
                if (sleepNanos > 0) {
                    try {
                        Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                    } catch (InterruptedException e) {
                        // Restore the flag (never swallow an interrupt) and
                        // leave. The finally block still releases the SDK.
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            stoppedBecause = "Minecraft is shutting down.";
        } finally {
            // The single most important line in this class. However we leave
            // — clean shutdown, interrupt, or an exception out of connect() —
            // lighting goes back to the user's own software.
            bridge.shutdown();
        }
    }

    /**
     * Per-device-class gating. Users and modpack authors can switch off entire
     * categories of hardware without disabling the mod.
     *
     * <p>Keyboard is the only class enabled by default, on purpose: installing
     * a Minecraft mod and having your RAM sticks and motherboard suddenly
     * start flashing at you is not a delightful surprise, it's an alarming
     * one. Opt in to the light show.
     */
    private static boolean deviceClassEnabled(KeyGrid.DeviceClass deviceClass) {
        return switch (deviceClass) {
            case KEYBOARD -> RGBProfileConfig.DEVICE_KEYBOARD_ENABLED.get();
            case MOUSE -> RGBProfileConfig.DEVICE_MOUSE_ENABLED.get();
            case MOUSEMAT -> RGBProfileConfig.DEVICE_MOUSEMAT_ENABLED.get();
            case HEADSET -> RGBProfileConfig.DEVICE_HEADSET_ENABLED.get();
            case MEMORY -> RGBProfileConfig.DEVICE_MEMORY_ENABLED.get();
            case COOLING -> RGBProfileConfig.DEVICE_COOLING_ENABLED.get();
            case MOTHERBOARD -> RGBProfileConfig.DEVICE_MOTHERBOARD_ENABLED.get();
            case OTHER -> RGBProfileConfig.DEVICE_OTHER_ENABLED.get();
        };
    }

}
