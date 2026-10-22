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
 * {@code boss_profiles.json}, {@code dimension_profiles.json}) with a frankly
 * unreasonable amount of care about not falling over when a hand-edited file
 * has something odd in it.
 *
 * <p>These are the colour and pattern definitions: which biome looks like
 * what, which boss gets which palette, what a dimension's portal does. They
 * ship as defaults inside the jar and get copied out for editing, and
 * "editing" is the operative word — the whole point of them living in config
 * as JSON rather than being compiled in is that somebody can open one at
 * midnight and change a hex code without a build toolchain.
 *
 * <p>Which means every file this class reads has, by design, been through a
 * text editor. That is what all the care below is for.
 *
 * <p>(Not to be confused with {@code KeyLayout} over in {@code sdk/}, which is
 * the file format for describing a KEYBOARD that will not report its own
 * geometry. Different problem, different file, same principle of letting
 * somebody with the hardware solve it themselves. These profile files could
 * plausibly grow into shareable themes later, but that is not what they are
 * today and nothing here should be written as though it is.)
 *
 * <p>The flow: ship sensible defaults inside the jar, copy them into the
 * config folder on first run, then parse the config copy, which by that point
 * may have been edited into something quite exciting.
 *
 * <h2>The paranoia is the feature</h2>
 * A malformed edit must never take the mod down with it, and this class means
 * that at <b>per-entry</b> granularity. One entry with something wrong in it
 * logs a warning and falls back to the bundled default <i>for that entry
 * only</i>. The other 200 biomes load completely fine and nobody notices
 * anything happened.
 *
 * <p>Be precise about what that does and does not buy, though, because it is
 * very easy to oversell and an earlier version of this comment did exactly
 * that. Damage to the file's STRUCTURE (an unclosed brace, a quote nobody
 * terminated, a half-finished download) kills the outer parse on line one, no
 * matter how the entries are handled afterwards. That lands in the catch at
 * the bottom of the method and falls back to the bundled defaults wholesale.
 * Nothing in this class can rescue it, and nothing pretends to.
 *
 * <p>What per-entry parsing actually saves is the far more common case: the
 * file is perfectly valid JSON and ONE entry has a field of the wrong shape.
 * A gradient written as {@code "#FF0000"} instead of {@code ["#FF0000"]}. A
 * threshold written as {@code "fifty"}. Parse the file as one
 * {@code Map<String,T>} and that single entry drags every other customisation
 * in the file into the bin with it, silently, with nothing in the log pointing
 * at which one did it. Handle the entries one at a time and the damage is
 * exactly that entry, named out loud in a warning.
 *
 * <p>There are four independent failure paths here, and every one of them
 * degrades to something usable rather than throwing:
 * <ol>
 *   <li>Bundled resource missing → empty map, warn.</li>
 *   <li>Copy-to-config fails (read-only FS, permissions) → run on bundled
 *       defaults, warn.</li>
 *   <li>Config file unreadable or structurally broken → bundled defaults for
 *       the whole file, warn.</li>
 *   <li>One entry the wrong shape → bundled default for that entry, warn, and
 *       every other entry loads normally.</li>
 * </ol>
 */
public final class JsonProfileLoader {

    /**
     * Lenient mode on purpose. These are hand-edited files, and lenient
     * tolerates unquoted keys and single-quoted strings, which people do write
     * into JSON sooner or later.
     *
     * <p>What it does NOT tolerate, despite what you might hope, is a trailing
     * comma after the last entry of an object. Checked against Gson 2.10.1,
     * the version Minecraft 1.21.1 ships: {@code {"a": {...},}} fails with
     * "Expected name" even in lenient mode, which is a structural failure, so
     * the whole file falls back to the bundled defaults. (A trailing comma in
     * an array doesn't fail; lenient reads it as an extra null element.)
     */
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    public static <T> Map<String, T> load(String bundledResourcePath, Path configFile, Class<T> entryType) {
        // Bundled defaults get parsed FIRST, unconditionally. They are the
        // safety net every path below falls back onto, so they have to already
        // exist before anything is given the chance to go wrong.
        Map<String, T> bundled = parseResource(bundledResourcePath, entryType);
        copyDefaultIfAbsent(bundledResourcePath, configFile);

        if (!Files.exists(configFile)) {
            // Extraction failed. Read-only filesystem, permissions, or an
            // antivirus having strong opinions about a Minecraft mod writing
            // files. Not fatal: the bundled defaults are already in memory and
            // work fine. It just means nothing can be customised this session.
            return bundled;
        }

        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            // THIS LINE IS UNUSED ON PURPOSE. DO NOT "CLEAN IT UP".
            //
            // Your IDE is greying it out right now and it is wrong to. This
            // is the type you would hand to GSON.fromJson() to deserialise the
            // entire file in one call, and doing that is comfortably the most
            // tempting simplification anywhere in this class: it deletes the
            // whole loop below and replaces it with one line that looks
            // objectively better.
            //
            // It also works. That is the trap. It compiles, it runs, and it
            // passes every test you throw at it, because every file you test
            // with is a file you just wrote correctly.
            //
            // Then somebody edits one biome out of three hundred and writes
            // its gradient as a string instead of a list. With the one-shot
            // parse, that single entry throws and takes the ENTIRE file with
            // it. Every other customisation in that file silently reverts to
            // bundled defaults, the log says nothing about which entry caused
            // it, and the person who made the edit has no way to find out.
            //
            // From where they are standing, the mod just started ignoring
            // their config.
            //
            // So it stays here, deliberately dead, as the sign on the fence.
            // If you want this line, read the class doc first, then decide.
            Type mapType = TypeToken.getParameterized(Map.class, String.class, entryType).getType();
            // Parse to a generic JsonObject first, NOT straight to
            // Map<String,T>. This is the whole per-entry resilience trick.
            // Getting the file's structure into memory and turning entries
            // into real objects are two separate steps, which means the second
            // one can fail three hundred times over without the first one
            // caring.
            //
            // Note this call still parses the entire file, so genuinely
            // broken JSON fails right here and goes to the catch below. That
            // is unavoidable and it is not what the split is protecting
            // against; see the class doc.
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return bundled; // empty or all-whitespace file

            Map<String, T> result = new HashMap<>();
            for (String key : root.keySet()) {
                try {
                    T entry = GSON.fromJson(root.get(key), entryType);
                    result.put(key, entry);
                } catch (JsonSyntaxException e) {
                    // Reached when the JSON was structurally fine but this
                    // entry's contents don't fit the target type. Wrong type
                    // on a field, an object where a list belongs, that sort of
                    // thing. Exactly one entry's worth of damage, contained.
                    T fallback = bundled.get(key);
                    // Names the key AND the file AND says exactly what it did
                    // about it. A warning that tells you something went wrong
                    // but not what happened next is just free anxiety.
                    String outcome = fallback != null ? "falling back to the bundled default for this entry" : "skipping this entry";
                    RGBProfileMod.LOGGER.warn("RGB Profile: malformed entry '{}' in {}, {}.", key, configFile.getFileName(),
                            outcome);
                    BackendHealth.problem("Profiles", "Entry \"" + key + "\" in " + configFile.getFileName()
                            + " could not be read, " + outcome + ": " + e.getMessage(), null);
                    if (fallback != null) result.put(key, fallback);
                }
            }
            // Union with the bundled set, user copy winning every tie. This
            // matters on upgrade: a mod update adds new default biomes, and
            // the config file on disk was written before any of them existed,
            // so it has no idea they're a thing. putIfAbsent means new
            // defaults show up automatically without overwriting a single
            // customisation.
            for (Map.Entry<String, T> entry : bundled.entrySet()) {
                result.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return result;
        } catch (IOException | JsonSyntaxException e) {
            // Whole-file failure: either unreadable, or so thoroughly broken
            // that even lenient Gson tapped out. Logged WITH the exception,
            // because at this point the stack trace is genuinely the useful
            // part rather than noise.
            RGBProfileMod.LOGGER.warn("RGB Profile: failed to read {}, using bundled defaults only.", configFile.getFileName(), e);
            BackendHealth.problem("Profiles", configFile.getFileName() + " could not be read at all, so every "
                    + "customisation in it is being ignored and the bundled defaults used instead. Usually a "
                    + "missing bracket or quote: " + e.getMessage(), null);
            return bundled;
        }
    }

    /**
     * Parses the copy inside the jar. Nobody can edit this one, so a failure
     * here means the jar is corrupt or the build is broken. Warn and return
     * empty rather than crashing, because the mod runs perfectly well with no
     * profiles at all; everything just falls through to derived colours.
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
     * First-run seeding. Note that it only copies when the file is
     * <b>absent</b>. It will never overwrite an edited file, not on upgrade,
     * not on a version bump, not ever. New defaults reach existing users
     * through the {@code putIfAbsent} union above instead.
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
