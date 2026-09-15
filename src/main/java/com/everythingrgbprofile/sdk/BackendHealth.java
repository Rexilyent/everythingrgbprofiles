package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What happened to each lighting backend, in words a player can act on.
 *
 * <p>Every backend except Corsair is experimental: the only keyboard this
 * project has been tested on is a Corsair K70 RGB RAPIDFIRE. So most lighting
 * problems will arrive as a player saying "my keyboard doesn't light", on
 * hardware nobody here owns, and the only way to help them is for the mod to
 * have already worked out which of three very different things is going on:
 *
 * <ol>
 *   <li><b>Their software is missing or not running</b> — G HUB not installed,
 *       OpenRGB's SDK server not started. The player can fix this in a minute
 *       if they are told what to do.</li>
 *   <li><b>Their software said no</b> — iCUE with SDK control turned off, a
 *       device type switched off in the config. Also theirs to fix.</li>
 *   <li><b>The mod got it wrong</b> — a reply it could not parse, a payload the
 *       vendor rejected, an exception. Nothing the player can do but send it
 *       to us.</li>
 * </ol>
 *
 * <p>An earlier version logged each of these at DEBUG level, or not at all,
 * because most players do not own most brands and a line per missing vendor
 * looked like noise. The result was that a failure of any kind looked exactly
 * like every other failure: a dark keyboard and nothing in the log. Now every
 * backend records a {@link Diagnosis} at each way out of connecting and at the
 * moment it drops, and this class is where the log summary, the
 * {@code /rgbprofiles} command and the report file all read them from.
 *
 * <h2>Threading</h2>
 * Written from the SDK worker thread, read from the game thread. Diagnoses are
 * immutable records and every access to the shared collections is
 * synchronised, which is cheap because nothing here is on a per-frame path.
 */
public final class BackendHealth {

    /** How a backend stands. {@link #modFault} marks the ones that are the mod's to fix. */
    public enum State {
        WAITING("waiting", false),
        CONNECTED("connected", false),
        NOT_INSTALLED("not installed", false),
        NOT_RUNNING("not running", false),
        REFUSED("refused", false),
        NO_DEVICES("no devices", false),
        DISABLED("turned off", false),
        MISCONFIGURED("setting problem", false),
        NOT_SELECTED("not selected", false),
        UNSUPPORTED("can't run here", false),
        CRASHED("skipped after a crash", false),
        LOST("connection lost", false),
        BROKEN("mod error", true);

        private final String label;
        private final boolean modFault;

        State(String label, boolean modFault) {
            this.label = label;
            this.modFault = modFault;
        }

        public String label() {
            return label;
        }

        public boolean modFault() {
            return modFault;
        }

        /** True for the states a player would want pointed out even when other lighting works. */
        public boolean needsAttention() {
            return this == REFUSED || this == NO_DEVICES || this == DISABLED || this == MISCONFIGURED
                    || this == UNSUPPORTED || this == CRASHED || this == LOST || this == BROKEN;
        }
    }

    /**
     * One backend's outcome.
     *
     * @param summary what happened, in one sentence
     * @param fix     what the player should try, or null when there is nothing to do
     * @param detail  the technical part — paths looked in, the error and where it
     *                came from — for the report file rather than for chat; may be null
     */
    public record Diagnosis(String backendId, String displayName, State state, String summary,
                            String fix, String detail, long atMillis) {
    }

    /**
     * Something that went wrong without deciding a backend's state: an effect
     * throwing, one device of several failing to read.
     */
    public static final class Problem {
        private final long firstMillis;
        private volatile long lastMillis;
        private final String source;
        private final String message;
        private final String detail;
        private final boolean modFault;
        private final AtomicInteger count = new AtomicInteger(1);

        Problem(long atMillis, String source, String message, String detail, boolean modFault) {
            this.firstMillis = atMillis;
            this.lastMillis = atMillis;
            this.source = source;
            this.message = message;
            this.detail = detail;
            this.modFault = modFault;
        }

        /** True if this can only be a bug in the mod, as opposed to hardware or software misbehaving. */
        public boolean modFault() {
            return modFault;
        }

        public long firstMillis() {
            return firstMillis;
        }

        public long lastMillis() {
            return lastMillis;
        }

        public String source() {
            return source;
        }

        public String message() {
            return message;
        }

        public String detail() {
            return detail;
        }

        public int count() {
            return count.get();
        }
    }

    /**
     * Thrown from inside a backend's connection code to leave with a precise
     * diagnosis, rather than returning false and leaving the reason to guesswork.
     */
    public static final class Unavailable extends Exception {
        private final State state;
        private final String fix;
        private final String detail;

        public Unavailable(State state, String summary, String fix, String detail) {
            super(summary);
            this.state = state;
            this.fix = fix;
            this.detail = detail;
        }
    }

    /** Where to point a player whose problem is the mod's, not theirs. */
    public static final String SEND_REPORT = "Type /rgbprofiles report in game and send us the file it makes.";

    private static final int MAX_PROBLEMS = 25;

    private static final Map<String, Diagnosis> DIAGNOSES = new LinkedHashMap<>();
    private static final ArrayDeque<Problem> PROBLEMS = new ArrayDeque<>();
    private static final AtomicInteger CHANGES = new AtomicInteger();

    private static volatile String requestedBackend = "auto";
    private static volatile String gateSummary;
    private static volatile String gateFix;
    private static volatile boolean workerStarted;
    private static volatile boolean connectFinished;
    private static volatile long connectStartedMillis;
    private static volatile long connectFinishedMillis;
    private static volatile String leaderId;
    private static volatile List<String> followerIds = List.of();

    private BackendHealth() {
    }

    // ---------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------

    /**
     * The lighting pipeline was never started, and why. A closed gate is not a
     * backend failure — no backend was asked — so it is kept apart from them.
     */
    public static void gateClosed(String summary, String fix) {
        gateSummary = summary;
        gateFix = fix;
        CHANGES.incrementAndGet();
    }

    /** Called once, before any backend is tried. Every backend starts as waiting or not selected. */
    static void begin(String requested, Map<String, String> displayNames, String onlyId) {
        long now = System.currentTimeMillis();
        requestedBackend = requested;
        workerStarted = true;
        connectStartedMillis = now;
        synchronized (DIAGNOSES) {
            DIAGNOSES.clear();
            for (Map.Entry<String, String> e : displayNames.entrySet()) {
                if (onlyId == null || onlyId.equals(e.getKey())) {
                    DIAGNOSES.put(e.getKey(), new Diagnosis(e.getKey(), e.getValue(), State.WAITING,
                            "Not tried yet.", null, null, now));
                } else {
                    DIAGNOSES.put(e.getKey(), new Diagnosis(e.getKey(), e.getValue(), State.NOT_SELECTED,
                            "lightingBackend is set to \"" + onlyId + "\", so this one was not tried.",
                            "Set lightingBackend = \"auto\" in [hardware] to try every kind of lighting software.",
                            null, now));
                }
            }
        }
        CHANGES.incrementAndGet();
    }

    public static void record(String backendId, String displayName, State state, String summary,
                              String fix, String detail) {
        Diagnosis d = new Diagnosis(backendId, displayName, state, summary, fix, detail, System.currentTimeMillis());
        synchronized (DIAGNOSES) {
            DIAGNOSES.put(backendId, d);
        }
        CHANGES.incrementAndGet();
        // Drops and mod errors are logged as they happen. Connection outcomes
        // wait for logSummary, which prints them all together.
        if (connectFinished && (state == State.LOST || state == State.BROKEN)) {
            RGBProfileMod.LOGGER.warn("RGB Profile: {} — {}: {}{}", displayName, state.label(), summary,
                    detail == null ? "" : "\n" + detail);
        }
    }

    /** Records a precise diagnosis thrown from inside a backend. */
    public static void record(String backendId, String displayName, Unavailable u) {
        record(backendId, displayName, u.state, u.getMessage(), u.fix, u.detail);
    }

    /**
     * Records a failure that arrived as an exception, sorting it into "their
     * software is not there" and "the mod got something wrong".
     *
     * @param doing              what the backend was doing, for the detail,
     *                           e.g. "while connecting"
     * @param notRunningSummary  what to say if the vendor service simply did not answer
     * @param notRunningFix      what to suggest in that case
     */
    public static void recordFailure(String backendId, String displayName, String doing, Throwable t,
                                     String notRunningSummary, String notRunningFix) {
        if (t instanceof Unavailable u) {
            record(backendId, displayName, u);
            return;
        }
        String detail = displayName + " " + doing + ":\n" + describe(t);

        if (is32BitJava() && (hasCause(t, UnsatisfiedLinkError.class) || hasCause(t, NoClassDefFoundError.class))) {
            record(backendId, displayName, State.UNSUPPORTED,
                    "Minecraft is running on 32-bit Java, which cannot load the 64-bit lighting libraries.",
                    "Switch your launcher to a 64-bit Java (any Java 21 from the last few years is 64-bit).",
                    detail);
            return;
        }
        if (hasCause(t, UnknownHostException.class)) {
            record(backendId, displayName, State.MISCONFIGURED,
                    "The address this mod was told to connect to does not exist.",
                    "Check the address settings in [hardware]; for software on this computer it should be 127.0.0.1.",
                    detail);
            return;
        }
        if (connectionTrouble(t)) {
            record(backendId, displayName, State.NOT_RUNNING, notRunningSummary, notRunningFix, detail);
            return;
        }
        if (hasCause(t, NoClassDefFoundError.class) && String.valueOf(t).contains("com/sun/jna")) {
            record(backendId, displayName, State.BROKEN,
                    "The library this mod uses to reach native lighting software failed to load, "
                            + "which usually means another mod ships a clashing copy of it.",
                    SEND_REPORT + " Include your mod list.", detail);
            return;
        }
        record(backendId, displayName, State.BROKEN,
                "An unexpected error " + doing + ". This is most likely a bug in this mod's "
                        + displayName + " support, not a problem with your setup.",
                SEND_REPORT, detail);
    }

    /**
     * Something worth keeping for the report that is not a backend's state:
     * a device that could not be read, a profile or layout file that did not
     * load, a config setting that could not be used.
     * Repeats of the same message are counted rather than stored again, and
     * only the first is logged, so an effect throwing every frame costs one
     * log entry rather than thirty a second.
     */
    public static void problem(String source, String message, Throwable t) {
        addProblem(source, message, t, false);
    }

    /** A {@link #problem} that can only be the mod's own bug, such as an effect throwing. */
    public static void bug(String source, String message, Throwable t) {
        addProblem(source, message + " This is a bug in the mod.", t, true);
    }

    private static void addProblem(String source, String message, Throwable t, boolean modFault) {
        long now = System.currentTimeMillis();
        String detail = t == null ? null : describe(t);
        synchronized (PROBLEMS) {
            for (Problem p : PROBLEMS) {
                if (p.source.equals(source) && p.message.equals(message) && Objects.equals(p.detail, detail)) {
                    p.count.incrementAndGet();
                    p.lastMillis = now;
                    return;
                }
            }
            if (PROBLEMS.size() >= MAX_PROBLEMS) PROBLEMS.removeFirst();
            PROBLEMS.addLast(new Problem(now, source, message, detail, modFault));
        }
        CHANGES.incrementAndGet();
        if (t == null) {
            RGBProfileMod.LOGGER.warn("RGB Profile: {} — {}", source, message);
        } else {
            RGBProfileMod.LOGGER.warn("RGB Profile: {} — {} (repeats are counted in /rgbprofiles report "
                    + "rather than logged again)", source, message, t);
        }
    }

    /** The connection attempt is over: fill in anything left unexplained, and log the summary. */
    static void connectFinished(String leader, List<String> followers) {
        long now = System.currentTimeMillis();
        leaderId = leader;
        followerIds = List.copyOf(followers);
        synchronized (DIAGNOSES) {
            for (Map.Entry<String, Diagnosis> e : DIAGNOSES.entrySet()) {
                Diagnosis d = e.getValue();
                if (d.state() != State.WAITING) continue;
                // Every way out of a backend's connect is meant to record why.
                // One that did not is a gap in this mod, and saying so beats
                // leaving "waiting" on screen forever.
                e.setValue(new Diagnosis(d.backendId(), d.displayName(), State.BROKEN,
                        "Did not connect, and did not say why. This is a gap in this mod's diagnostics.",
                        SEND_REPORT, null, now));
            }
        }
        connectFinishedMillis = now;
        connectFinished = true;
        CHANGES.incrementAndGet();
        logSummary();
    }

    // ---------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------

    /** Bumped on every change, so a watcher can compare one int instead of every diagnosis. */
    public static int changes() {
        return CHANGES.get();
    }

    public static List<Diagnosis> diagnoses() {
        synchronized (DIAGNOSES) {
            return new ArrayList<>(DIAGNOSES.values());
        }
    }

    public static List<Problem> problems() {
        synchronized (PROBLEMS) {
            return new ArrayList<>(PROBLEMS);
        }
    }

    public static String requestedBackend() {
        return requestedBackend;
    }

    /** Why the lighting pipeline never started, or null if it did. */
    public static String gateSummary() {
        return gateSummary;
    }

    public static String gateFix() {
        return gateFix;
    }

    public static boolean workerStarted() {
        return workerStarted;
    }

    public static boolean connectFinished() {
        return connectFinished;
    }

    /** How long connecting took, or -1 while it is still going. */
    public static long connectMillis() {
        return connectFinished ? connectFinishedMillis - connectStartedMillis : -1;
    }

    /** The backend whose geometry patterns are drawn against, or null if none connected. */
    public static String leaderId() {
        return leaderId;
    }

    public static List<String> followerIds() {
        return followerIds;
    }

    /** True for the one backend that has been tried on real hardware. */
    public static boolean tested(String backendId) {
        return "corsair".equals(backendId);
    }

    /**
     * The startup summary, always logged, whatever the debug settings.
     *
     * <p>INFO rather than DEBUG, because this block is the first thing anyone
     * helping a player will ask for, and a player cannot be expected to find a
     * debug switch before they know they have a problem.
     */
    public static void logSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("RGB Profile: lighting backends (lightingBackend = \"").append(requestedBackend)
                .append("\"). Only Corsair iCUE has been tested on real hardware; the others are experimental.");
        for (Diagnosis d : diagnoses()) {
            sb.append(String.format("%n    %-12s %-16s %s", d.backendId(), d.state().label(), d.summary()));
            if (d.fix() != null && d.state() != State.NOT_SELECTED) {
                sb.append(String.format("%n    %-12s %-16s -> %s", "", "", d.fix()));
            }
        }
        sb.append(String.format("%n    Type /rgbprofiles status in game for this again, "
                + "or /rgbprofiles report to write a file you can send us."));
        boolean anyBroken = false;
        for (Diagnosis d : diagnoses()) {
            anyBroken |= d.state().modFault();
        }
        if (anyBroken) {
            RGBProfileMod.LOGGER.warn(sb.toString());
        } else {
            RGBProfileMod.LOGGER.info(sb.toString());
        }
        for (Diagnosis d : diagnoses()) {
            if (d.state().modFault() && d.detail() != null) {
                RGBProfileMod.LOGGER.warn("RGB Profile: {} error detail:\n{}", d.displayName(), d.detail());
            }
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * An exception as text for a report: the chain of causes, and for each the
     * first few frames, which is where the mod's own code shows up.
     */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 6; depth++) {
            if (depth > 0) sb.append("\n  caused by ");
            sb.append(cur.getClass().getName());
            if (cur.getMessage() != null) sb.append(": ").append(cur.getMessage());
            StackTraceElement[] frames = cur.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 8); i++) {
                sb.append("\n    at ").append(frames[i]);
            }
            if (cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return sb.toString();
    }

    /** Shortens a vendor's reply for a report: long enough to read, short enough not to bury the rest. */
    public static String clip(String text) {
        if (text == null) return "(no body)";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 400 ? flat : flat.substring(0, 400) + "...";
    }

    /** True if nothing answered at all: refused, unreachable or timed out while connecting. */
    public static boolean connectionTrouble(Throwable t) {
        return hasCause(t, ConnectException.class)
                || hasCause(t, HttpConnectTimeoutException.class)
                || hasCause(t, HttpTimeoutException.class)
                || hasCause(t, SocketTimeoutException.class)
                || hasCause(t, NoRouteToHostException.class);
    }

    public static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cur = t; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            if (type.isInstance(cur)) return true;
        }
        return false;
    }

    private static boolean is32BitJava() {
        return "32".equals(System.getProperty("sun.arch.data.model"));
    }
}
