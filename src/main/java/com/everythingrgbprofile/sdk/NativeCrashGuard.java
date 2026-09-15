package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stops a vendor library that crashes the game from crashing it on every
 * launch.
 *
 * <h2>The problem</h2>
 * Corsair's and Logitech's SDKs are native DLLs. When one of those faults — a
 * vendor update that changed something, a build nobody here has run — it does
 * not throw anything this mod can catch. The whole game process dies on the
 * spot, leaving an {@code hs_err_pid} file in the game folder and nothing in
 * Minecraft's log or crash reports that points at this mod. And since the mod
 * connects on every launch, the game then crashes on every launch, and the
 * player's only way out is to remove the mod.
 *
 * <h2>How it is caught</h2>
 * A marker file is written just before a backend's native connection code runs,
 * and deleted as soon as it returns, whatever the outcome. It is also deleted by
 * a shutdown hook, which runs whenever the game exits normally or through
 * Minecraft's own crash handling. A native fault is one of the few ways to end
 * the process without running shutdown hooks. So a marker still there at the
 * next launch means the process died inside that backend's native code.
 *
 * <p>That backend is then skipped, with a diagnosis saying so, until one of:
 * <ul>
 *   <li>the vendor's library changes on disk — the player updated G HUB or
 *       iCUE, which is the most likely fix — at which point it is tried again
 *       by itself;</li>
 *   <li>the player runs {@code /rgbprofiles retry}.</li>
 * </ul>
 *
 * <p>The one false positive is the game being killed from outside, by Task
 * Manager or a power cut, in the second or two a connection takes. That is rare,
 * costs one launch without that backend, and the diagnosis names the way back.
 *
 * <p>Only connecting is guarded. A fault while sending frames mid-game cannot be
 * told apart from any other mod crashing the game in the same session, and
 * guessing would switch off lighting that works.
 */
public final class NativeCrashGuard {

    private static final String DIRECTORY = "crash-guard";
    private static final String CONNECTING = ".connecting";
    private static final String CRASHED = ".crashed";

    /** Markers written this launch and not yet removed, for the shutdown hook. */
    private static final Set<Path> OPEN_MARKERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean hookInstalled;

    private NativeCrashGuard() {
    }

    /**
     * Refuses to go on if this backend crashed the last launch and nothing has
     * changed since. Call before {@link #enter}.
     *
     * @param libraries the vendor files whose change should count as "updated
     *                  since the crash"
     * @throws BackendHealth.Unavailable carrying the diagnosis, if skipped
     */
    public static void checkBefore(Path workDir, String backendId, String displayName, List<Path> libraries)
            throws BackendHealth.Unavailable {
        Path dir = workDir.resolve(DIRECTORY);
        Path connecting = dir.resolve(backendId + CONNECTING);
        Path crashed = dir.resolve(backendId + CRASHED);
        try {
            if (Files.exists(connecting)) {
                Properties record = read(connecting);
                long started = parseLong(record.getProperty("startedMillis"));
                Path crashLog = findCrashLog(started);
                record.setProperty("detectedMillis", String.valueOf(System.currentTimeMillis()));
                if (crashLog != null) {
                    record.setProperty("crashLog", crashLog.toAbsolutePath().toString());
                    record.setProperty("crashLogNamesLibrary", String.valueOf(mentionsAny(crashLog, libraries)));
                }
                write(crashed, record);
                Files.deleteIfExists(connecting);
                RGBProfileMod.LOGGER.warn("RGB Profile: the last launch ended while connecting to {}, which "
                        + "means its native library crashed the game. Skipping it until it is updated or "
                        + "/rgbprofiles retry is run.", displayName);
            }
            if (!Files.exists(crashed)) return;

            Properties record = read(crashed);
            String then = record.getProperty("fingerprint", "");
            String now = fingerprint(libraries);
            if (!then.equals(now)) {
                Files.deleteIfExists(crashed);
                RGBProfileMod.LOGGER.info("RGB Profile: {} has changed since it crashed the game, trying it again.",
                        displayName);
                return;
            }

            boolean confirmed = Boolean.parseBoolean(record.getProperty("crashLogNamesLibrary"));
            String crashLog = record.getProperty("crashLog");
            StringBuilder detail = new StringBuilder()
                    .append("Crashed connecting at ").append(new java.util.Date(parseLong(record.getProperty("startedMillis"))))
                    .append("\nLibraries: ").append(now);
            if (crashLog != null) {
                detail.append("\nCrash log: ").append(crashLog)
                        .append(confirmed ? " (names the library)" : " (does not name the library)");
            } else {
                detail.append("\nNo hs_err_pid crash log was found in the game folder.");
            }
            throw new BackendHealth.Unavailable(BackendHealth.State.CRASHED,
                    "Minecraft crashed while connecting to " + displayName + " last time"
                            + (confirmed ? ", and the crash log confirms it happened inside its library" : "")
                            + ", so it was skipped this launch to keep the game running.",
                    "Updating " + displayName + " usually fixes this, and it is tried again by itself once "
                            + "updated. To try again sooner, run /rgbprofiles retry and restart. Please also send us "
                            + "the file from /rgbprofiles report"
                            + (crashLog != null ? " and the crash log it names" : "") + ".",
                    detail.toString());
        } catch (IOException e) {
            // The guard failing must never be what stops a backend connecting.
            RGBProfileMod.LOGGER.warn("RGB Profile: could not check the crash guard for {}.", displayName, e);
        }
    }

    /** Marks the start of native connection code. Always pair with {@link #exit} in a finally. */
    public static void enter(Path workDir, String backendId, List<Path> libraries) {
        installHook();
        Path marker = workDir.resolve(DIRECTORY).resolve(backendId + CONNECTING);
        Properties record = new Properties();
        record.setProperty("startedMillis", String.valueOf(System.currentTimeMillis()));
        record.setProperty("fingerprint", fingerprint(libraries));
        try {
            write(marker, record);
            OPEN_MARKERS.add(marker);
        } catch (IOException e) {
            RGBProfileMod.LOGGER.warn("RGB Profile: could not write the crash guard marker for {}.", backendId, e);
        }
    }

    public static void exit(Path workDir, String backendId) {
        Path marker = workDir.resolve(DIRECTORY).resolve(backendId + CONNECTING);
        OPEN_MARKERS.remove(marker);
        try {
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            RGBProfileMod.LOGGER.warn("RGB Profile: could not remove the crash guard marker for {}.", backendId, e);
        }
    }

    /** Forgets every recorded crash, so each backend is tried at the next launch. Returns the backends cleared. */
    public static List<String> clearAll(Path workDir) {
        List<String> cleared = new ArrayList<>();
        Path dir = workDir.resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) return cleared;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith(CRASHED)) continue;
                Files.deleteIfExists(f);
                cleared.add(name.substring(0, name.length() - CRASHED.length()));
            }
        } catch (IOException e) {
            RGBProfileMod.LOGGER.warn("RGB Profile: could not clear the crash guard.", e);
        }
        return cleared;
    }

    /**
     * A shutdown hook, so that any exit Java gets to see — a normal quit, a
     * Minecraft crash report, another mod calling {@code System.exit} — clears
     * the markers. Only an exit Java never sees leaves one behind.
     */
    private static synchronized void installHook() {
        if (hookInstalled) return;
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Path marker : OPEN_MARKERS) {
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException ignored) {
                    // Nothing useful to do while the JVM exits.
                }
            }
        }, "RGBProfile-CrashGuard-Hook"));
    }

    /** Size and modification time of each library, which change when the vendor software is updated. */
    private static String fingerprint(List<Path> libraries) {
        StringBuilder sb = new StringBuilder();
        for (Path p : libraries) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(p);
            try {
                sb.append(" (").append(Files.size(p)).append(" bytes, modified ")
                        .append(Files.getLastModifiedTime(p).toMillis()).append(')');
            } catch (IOException e) {
                sb.append(" (missing)");
            }
        }
        return sb.toString();
    }

    /**
     * The JVM's own crash log from that launch, if there is one. It lands in
     * the working directory, which launchers set to the game folder.
     */
    private static Path findCrashLog(long startedMillis) {
        Path dir = Path.of("").toAbsolutePath();
        Path newest = null;
        long newestTime = startedMillis - 1000;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (!name.startsWith("hs_err_pid") || !name.endsWith(".log")) continue;
                long modified = Files.getLastModifiedTime(f).toMillis();
                if (modified >= newestTime) {
                    newest = f;
                    newestTime = modified;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return newest;
    }

    private static boolean mentionsAny(Path crashLog, List<Path> libraries) {
        try (InputStream in = Files.newInputStream(crashLog)) {
            String text = new String(in.readNBytes(2 * 1024 * 1024), StandardCharsets.ISO_8859_1)
                    .toLowerCase(Locale.ROOT);
            for (Path p : libraries) {
                if (p != null && text.contains(p.getFileName().toString().toLowerCase(Locale.ROOT))) return true;
            }
        } catch (IOException ignored) {
            // An unreadable log just means no confirmation.
        }
        return false;
    }

    private static Properties read(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return p;
    }

    private static void write(Path file, Properties p) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Everything RGB Profiles crash guard; see NativeCrashGuard");
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
