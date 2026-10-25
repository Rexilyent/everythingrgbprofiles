package com.everythingrgbprofile.fabric.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.config.ConfigSpec;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.platform.Platform;

/**
 * The config file on Fabric, doing by hand what NeoForge does for free:
 *
 * <ul>
 *   <li>Writes {@code config/everythingrgbprofiles-common.toml} with every
 *       default if it isn't there. Same name as on NeoForge, so the same file
 *       works on both.</li>
 *   <li>Corrects what it reads. A missing or unusable value gets its default
 *       back, a number out of range gets clamped, an unknown key gets dropped,
 *       and each one is logged. Then the file is rewritten, comments and
 *       all.</li>
 *   <li>Reloads when you edit the file with the game running, so changes take
 *       effect without a restart, same as NeoForge.</li>
 * </ul>
 *
 * <p>One thing it does that NeoForge doesn't: if a line can't be read at all,
 * the original file is copied to {@code .bak} before the rewrite, so whatever
 * was on that line still exists somewhere. Losing somebody's hand-tuned colour
 * to a stray quote mark would be an extremely annoying way to find out the
 * parser is strict.
 */
public final class FabricConfig {

    private static final String FILE_NAME = "everythingrgbprofiles-common.toml";

    /** How often, in client ticks, to check whether the file changed. A second. */
    private static final int RELOAD_CHECK_TICKS = 20;

    private static final Object LOCK = new Object();
    private static Path file;
    private static FileTime lastSeenModified;
    private static long lastSeenSize = -1;
    private static int ticksUntilCheck;

    // Swapped whole, never edited in place: the SDK worker thread reads these
    // while the client thread reloads them, and a map half way through a
    // reload is a great way to read a value that was never in either file.
    private static volatile Map<ConfigSpec.ConfigValue<?>, Object> current;

    /** Loads the file, creating or correcting it as needed, and hands the values to common. */
    public static void load() {
        ConfigSpec spec = RGBProfileConfig.SPEC;
        synchronized (LOCK) {
            file = Platform.get().configDir().resolve(FILE_NAME);
            read(spec);
        }
        spec.attach(new ConfigSpec.Store() {
            @Override
            public Object get(ConfigSpec.ConfigValue<?> value) {
                Object v = current.get(value);
                return v != null ? v : value.getDefault();
            }

            @Override
            public void set(ConfigSpec.ConfigValue<?> value, Object newValue) {
                Object corrected = value.correct(newValue);
                if (corrected == null) {
                    throw new IllegalArgumentException("Not a valid value for " + String.join(".", value.getPath()) + ": " + newValue);
                }
                synchronized (LOCK) {
                    Map<ConfigSpec.ConfigValue<?>, Object> next = new IdentityHashMap<>(current);
                    next.put(value, corrected);
                    current = next;
                }
            }

            @Override
            public void save() {
                synchronized (LOCK) {
                    writeFile(spec, current, false);
                }
            }
        });
    }

    /** Called every client tick; actually looks at the file once a second. */
    public static void reloadIfChanged() {
        if (--ticksUntilCheck > 0) return;
        ticksUntilCheck = RELOAD_CHECK_TICKS;
        synchronized (LOCK) {
            if (file == null) return;
            try {
                if (!Files.exists(file)) {
                    // Deleted with the game running. NeoForge would put a
                    // fresh one back, so this does too.
                    RGBProfileMod.LOGGER.info("RGB Profile: {} was deleted, writing a fresh one.", FILE_NAME);
                    read(RGBProfileConfig.SPEC);
                    return;
                }
                FileTime modified = Files.getLastModifiedTime(file);
                long size = Files.size(file);
                if (modified.equals(lastSeenModified) && size == lastSeenSize) return;
            } catch (IOException e) {
                // Mid-save in an editor, most likely. Next second, then.
                return;
            }
            RGBProfileMod.LOGGER.info("RGB Profile: {} changed, reloading.", FILE_NAME);
            read(RGBProfileConfig.SPEC);
        }
    }

    // ---------------------------------------------------------------

    private static void read(ConfigSpec spec) {
        Map<ConfigSpec.ConfigValue<?>, Object> values = new IdentityHashMap<>();

        if (!Files.exists(file)) {
            for (ConfigSpec.ConfigValue<?> value : spec.values()) values.put(value, value.getDefault());
            current = values;
            writeFile(spec, values, false);
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Can't read it: keep whatever we had, or the defaults on a first
            // load, and leave the file alone. It isn't ours to overwrite when
            // we don't know what's in it.
            RGBProfileMod.LOGGER.error("RGB Profile: could not read {}, using {} values.", file,
                    current == null ? "default" : "the previous", e);
            if (current == null) {
                for (ConfigSpec.ConfigValue<?> value : spec.values()) values.put(value, value.getDefault());
                current = values;
            }
            return;
        }

        TomlFile.Parsed parsed = TomlFile.parse(lines);
        for (String problem : parsed.problems()) {
            RGBProfileMod.LOGGER.warn("RGB Profile: {}: {}", FILE_NAME, problem);
        }

        boolean rewrite = !parsed.problems().isEmpty();
        Set<String> known = new HashSet<>();
        for (ConfigSpec.ConfigValue<?> value : spec.values()) {
            String path = String.join(".", value.getPath());
            known.add(path);
            Object raw = parsed.values().get(path);
            if (raw == null) {
                if (parsed.unreadable().contains(path)) {
                    RGBProfileMod.LOGGER.warn("RGB Profile: {} in {} could not be read (see above), setting it to its default, {}.",
                            path, FILE_NAME, TomlFile.writeValue(value.getDefault()));
                } else {
                    RGBProfileMod.LOGGER.warn("RGB Profile: {} is missing from {}, setting it to its default, {}.",
                            path, FILE_NAME, TomlFile.writeValue(value.getDefault()));
                }
                values.put(value, value.getDefault());
                rewrite = true;
                continue;
            }
            Object corrected = value.correct(raw);
            if (corrected == null) {
                RGBProfileMod.LOGGER.warn("RGB Profile: {} = {} in {} is not usable, setting it to its default, {}.",
                        path, TomlFile.writeValue(raw), FILE_NAME, TomlFile.writeValue(value.getDefault()));
                values.put(value, value.getDefault());
                rewrite = true;
            } else {
                if (!sameValue(raw, corrected)) {
                    RGBProfileMod.LOGGER.warn("RGB Profile: {} = {} in {} is out of range, clamped to {}.",
                            path, TomlFile.writeValue(raw), FILE_NAME, TomlFile.writeValue(corrected));
                    rewrite = true;
                }
                values.put(value, corrected);
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String path : parsed.values().keySet()) {
            if (!known.contains(path)) unknown.add(path);
        }
        if (!unknown.isEmpty()) {
            unknown.sort(null);
            RGBProfileMod.LOGGER.warn("RGB Profile: removing settings {} doesn't have (any more): {}", FILE_NAME, unknown);
            rewrite = true;
        }

        current = values;
        if (rewrite) {
            writeFile(spec, values, !parsed.problems().isEmpty());
        } else {
            remember();
        }
    }

    private static boolean sameValue(Object raw, Object corrected) {
        if (raw instanceof Number a && corrected instanceof Number b) return a.doubleValue() == b.doubleValue();
        return Objects.equals(raw, corrected);
    }

    private static void writeFile(ConfigSpec spec, Map<ConfigSpec.ConfigValue<?>, Object> values, boolean backUpFirst) {
        try {
            Files.createDirectories(file.getParent());
            if (backUpFirst && Files.exists(file)) {
                Path backup = file.resolveSibling(FILE_NAME + ".bak");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                RGBProfileMod.LOGGER.warn("RGB Profile: some lines of {} could not be read; the file as it was is saved as {}.",
                        FILE_NAME, backup.getFileName());
            }
            // Written next to the real file and moved over it, so a crash
            // mid-write leaves the old file instead of half of a new one.
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, TomlFile.write(spec, values), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            remember();
        } catch (IOException e) {
            RGBProfileMod.LOGGER.error("RGB Profile: could not write {}.", file, e);
        }
    }

    /** Notes the file as it is now, so our own writes don't count as edits. */
    private static void remember() {
        try {
            lastSeenModified = Files.getLastModifiedTime(file);
            lastSeenSize = Files.size(file);
        } catch (IOException e) {
            lastSeenModified = null;
            lastSeenSize = -1;
        }
    }

    private FabricConfig() {
    }
}
