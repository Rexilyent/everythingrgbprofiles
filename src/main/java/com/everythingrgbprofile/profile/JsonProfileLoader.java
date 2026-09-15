package com.everythingrgbprofile.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.sdk.BackendHealth;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the three profile files ({@code biome_profiles.json},
 * {@code boss_profiles.json}, {@code dimension_profiles.json}) with what is
 * frankly an unreasonable amount of care about not breaking when a user
 * fat-fingers a comma.
 *
 * <p>The flow: ship sensible defaults inside the jar, copy them into the
 * config folder on first run, then parse the — possibly heavily edited —
 * config copy.
 *
 * <h2>The paranoia is the feature</h2>
 * A malformed user edit must never crash the mod, and this class takes that
 * seriously at <b>per-entry</b> granularity. One busted entry logs
 * a warning and falls back to the bundled default <i>for just that entry</i>.
 * The other 200 biomes load perfectly.
 *
 * <p>Parsing the whole file as one object would mean a single stray comma
 * somewhere in a large hand-edited file silently reverts every customisation
 * the user has ever made — with no indication which line was the problem.
 * That's a genuinely miserable experience and the failure mode this design
 * exists to avoid.
 *
 * <p>There are four independent failure paths here, and every one of them
 * degrades to something usable rather than throwing:
 * <ol>
 *   <li>Bundled resource missing → empty map, warn.</li>
 *   <li>Copy-to-config fails (read-only FS, permissions) → run on bundled
 *       defaults, warn.</li>
 *   <li>Config file unreadable or wholly invalid → bundled defaults, warn.</li>
 *   <li>One entry malformed → bundled default for that entry, warn.</li>
 * </ol>
 */
public final class JsonProfileLoader {

    /**
     * Lenient mode on purpose. These are hand-edited files, and lenient
     * tolerates trailing commas and unquoted keys — the two things every
     * human writes into JSON at some point. Being strict here would generate
     * bug reports that are really just a comma.
     */
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    public static <T> Map<String, T> load(String bundledResourcePath, Path configFile, Class<T> entryType) {
        // Bundled defaults parsed FIRST, unconditionally. They're the safety
        // net every path below falls back onto, so they have to exist before
        // anything can go wrong.
        Map<String, T> bundled = parseResource(bundledResourcePath, entryType);
        copyDefaultIfAbsent(bundledResourcePath, configFile);

        if (!Files.exists(configFile)) {
            // Extraction failed — read-only filesystem, permissions, an
            // antivirus having opinions. Not fatal: the bundled defaults are
            // in memory and work fine. The user just can't customise.
            return bundled;
        }

        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            // Vestigial: this is the type you'd use for the naive
            // whole-file-at-once parse. Left as documentation of the road not
            // taken — if you ever find yourself reaching for it, re-read the
            // class doc first. (Unused; the compiler will tell you so.)
            Type mapType = TypeToken.getParameterized(Map.class, String.class, entryType).getType();
            // Parse to a generic JsonObject first, NOT straight to
            // Map<String,T>. This is the whole per-entry-resilience trick:
            // deserialising to the target type happens one entry at a time
            // below, so a single bad entry can be caught and replaced instead
            // of taking the entire file down with it.
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return bundled; // empty or all-whitespace file

            Map<String, T> result = new HashMap<>();
            for (String key : root.keySet()) {
                try {
                    T entry = GSON.fromJson(root.get(key), entryType);
                    result.put(key, entry);
                } catch (JsonSyntaxException e) {
                    T fallback = bundled.get(key);
                    // Names the key AND the file, and says exactly what it did
                    // about it. A warning that doesn't tell you what happened
                    // next is just anxiety.
                    String outcome = fallback != null ? "falling back to the bundled default for this entry" : "skipping this entry";
                    RGBProfileMod.LOGGER.warn("RGB Profile: malformed entry '{}' in {}, {}.", key, configFile.getFileName(),
                            outcome);
                    BackendHealth.problem("Profiles", "Entry \"" + key + "\" in " + configFile.getFileName()
                            + " could not be read, " + outcome + ": " + e.getMessage(), null);
                    if (fallback != null) result.put(key, fallback);
                }
            }
            // Union with the bundled set, user copy winning. This matters on
            // upgrade: a mod update adds new default biomes, and the user's
            // config file — written before those existed — has no idea about
            // them. putIfAbsent means new defaults appear automatically
            // without touching anything the user customised.
            for (Map.Entry<String, T> entry : bundled.entrySet()) {
                result.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return result;
        } catch (IOException | JsonSyntaxException e) {
            // Whole-file failure — unreadable, or so broken even lenient Gson
            // gives up. Logged WITH the exception, since at this point the
            // stack trace is genuinely the useful part.
            RGBProfileMod.LOGGER.warn("RGB Profile: failed to read {}, using bundled defaults only.", configFile.getFileName(), e);
            BackendHealth.problem("Profiles", configFile.getFileName() + " could not be read at all, so every "
                    + "customisation in it is being ignored and the bundled defaults used instead. Usually a "
                    + "missing bracket or quote: " + e.getMessage(), null);
            return bundled;
        }
    }

    /**
     * Parses the copy inside the jar. This one is not user-editable, so a
     * failure here means the jar is corrupt or the build is broken — warn and
     * return empty rather than crash, because the mod can still run with no
     * profiles (everything falls through to derived colours).
     */
    private static <T> Map<String, T> parseResource(String resourcePath, Class<T> entryType) {
        try (InputStream in = JsonProfileLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                RGBProfileMod.LOGGER.warn("RGB Profile: bundled default resource {} not found in jar.", resourcePath);
                BackendHealth.bug("Profiles", "The bundled defaults " + resourcePath + " are missing from the jar.", null);
                return Map.of();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            Map<String, T> result = new HashMap<>();
            if (root != null) {
                for (String key : root.keySet()) {
                    result.put(key, GSON.fromJson(root.get(key), entryType));
                }
            }
            return result;
        } catch (IOException e) {
            RGBProfileMod.LOGGER.warn("RGB Profile: error reading bundled resource {}.", resourcePath, e);
            return Map.of();
        }
    }

    /**
     * First-run seeding. Note it only copies when the file is <b>absent</b> —
     * it will never overwrite a user's edited file, not on upgrade, not ever.
     * New defaults reach existing users through the {@code putIfAbsent} union
     * above instead.
     */
    private static void copyDefaultIfAbsent(String resourcePath, Path configFile) {
        if (Files.exists(configFile)) return;
        try (InputStream in = JsonProfileLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) return;
            Files.createDirectories(configFile.getParent());
            Files.copy(in, configFile);
        } catch (IOException e) {
            RGBProfileMod.LOGGER.warn("RGB Profile: failed to copy default profile file to {}.", configFile, e);
            BackendHealth.problem("Profiles", "Could not create " + configFile.getFileName()
                    + " in the config folder, so it cannot be customised: " + e, null);
        }
    }

    private JsonProfileLoader() {
    }
}
