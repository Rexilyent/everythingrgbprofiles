package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.RGBProfileMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * User-supplied board geometry, for hardware that will not report its own.
 *
 * <p>This is the piece that makes "someone else's keyboard" a job somebody
 * else can do. Every pattern in this mod is geometry, so a device is either
 * describable in x/y or it is useless to us — and plenty of hardware reports a
 * flat list of LEDs with names and no positions at all. Rather than that being
 * the end of the road, a user with the device writes a small JSON file, and the
 * whole effect library starts working on it.
 *
 * <h2>The file</h2>
 * Anything in {@code config/everythingrgbprofiles/layouts/*.json}:
 *
 * <pre>
 * {
 *   "match": "SteelSeries Apex*",
 *   "keys": {
 *     "Key: Escape": [0, 0],
 *     "Key: F1":     [2, 0],
 *     "Key: A":      [1.75, 3]
 *   }
 * }
 * </pre>
 *
 * <p>{@code match} is a glob against the device name the backend reported.
 * {@code keys} maps the backend's own LED names to positions.
 *
 * <h2>Units are whatever you like</h2>
 * Key units, millimetres, arbitrary grid cells — {@code KeyGrid.Builder}
 * normalises each device into 0..1 and keeps the raw span for aspect
 * correction, so only the <i>proportions</i> matter. Two rules that do matter:
 * x increases to the right, and <b>y increases downward</b>. Getting y inverted
 * gives a board that looks plausible until it rains upward.
 *
 * <h2>What happens to LEDs the file does not mention</h2>
 * They keep whatever position the backend guessed. A partial layout is
 * therefore useful on its own — describe the main key block and leave the
 * media strip alone — and there is no cliff between "no layout" and "complete
 * layout". Positions are only replaced, never removed, so a typo in one key
 * name costs you that key rather than the board.
 *
 * <h2>Finding the LED names</h2>
 * For OpenRGB devices, set {@code debug.enabled} and every LED name the device
 * reports is logged at startup; copy them out of the log. They are OpenRGB's
 * own strings, not anything this mod invents, which is why a layout file is
 * worth writing once and sharing. The fixed-grid vendors (Razer, Logitech,
 * SteelSeries) report no LED names at all, so their cells are named by
 * position instead: {@code R2C6} is row 2, column 6, counting from zero at the
 * top left.
 */
public final class KeyLayout {

    private final String match;
    private final Map<String, double[]> keys;

    private KeyLayout(String match, Map<String, double[]> keys) {
        this.match = match;
        this.keys = keys;
    }

    /** Loaded once on first use; layouts do not change while the game runs. */
    private static List<KeyLayout> cache;
    /** Set by a harness running outside Minecraft. Null in normal use. */
    private static Path directoryOverride;

    /** Points the loader somewhere else. For tools; the mod never calls this. */
    public static void useLayoutsDirectory(Path dir) {
        directoryOverride = dir;
        cache = null;
    }

    /**
     * Where to look for layouts.
     *
     * <p>The {@code Throwable} catch is not paranoia. {@code RGBProfileFiles}
     * resolves through NeoForge's {@code FMLPaths}, so touching it outside a
     * running game throws {@link NoClassDefFoundError} — an Error, which sails
     * straight through any {@code catch (Exception)} between here and the
     * render loop. Letting a config-path helper be able to kill lighting
     * entirely is not a trade worth making, and the fallback also means the
     * tuning harnesses can exercise layouts at all.
     */
    private static Path layoutsDirectory() {
        if (directoryOverride != null) return directoryOverride;
        try {
            return RGBProfileFiles.layoutsDirectory();
        } catch (Throwable t) {
            return Path.of("config", "everythingrgbprofiles", "layouts");
        }
    }

    /**
     * Applies any matching layout to a device's LED list.
     *
     * @param deviceName  as reported by the backend
     * @param ledNames    the backend's LED names, index-aligned with its LEDs
     * @param fallback    {luid, x, y} triples the backend worked out on its own
     * @return positions to use, which is {@code fallback} unchanged when no
     *         layout matches
     */
    public static List<double[]> positionsFor(String deviceName, List<String> ledNames,
                                              List<double[]> fallback) {
        KeyLayout layout = findFor(deviceName);
        if (layout == null) return fallback;

        // Name -> luid, so the file can be written in terms the user can
        // actually see rather than in indices they would have to count.
        Map<String, Integer> luidByName = new HashMap<>();
        for (int i = 0; i < ledNames.size(); i++) {
            luidByName.putIfAbsent(ledNames.get(i), i);
        }

        Map<Integer, double[]> replaced = new HashMap<>();
        int missed = 0;
        for (Map.Entry<String, double[]> e : layout.keys.entrySet()) {
            Integer luid = luidByName.get(e.getKey());
            if (luid == null) {
                missed++;
                continue;
            }
            replaced.put(luid, new double[]{luid, e.getValue()[0], e.getValue()[1]});
        }

        List<double[]> out = new ArrayList<>(fallback.size());
        for (double[] p : fallback) {
            double[] override = replaced.remove((int) p[0]);
            out.add(override != null ? override : p);
        }
        // Keys the layout placed that the backend never listed positionally.
        out.addAll(replaced.values());

        RGBProfileMod.LOGGER.info(
                "RGB Profile: layout '{}' applied to '{}' — {} keys placed, {} name(s) not found on the device.",
                layout.match, deviceName, layout.keys.size() - missed, missed);
        return out;
    }

    private static KeyLayout findFor(String deviceName) {
        if (cache == null) cache = loadAll();
        for (KeyLayout l : cache) {
            if (globMatches(l.match, deviceName)) return l;
        }
        return null;
    }

    private static List<KeyLayout> loadAll() {
        List<KeyLayout> out = new ArrayList<>();
        Path dir = layoutsDirectory();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                    String match = root.has("match") ? root.get("match").getAsString() : "*";
                    Map<String, double[]> keys = new HashMap<>();
                    if (root.has("keys")) {
                        for (Map.Entry<String, com.google.gson.JsonElement> e
                                : root.getAsJsonObject("keys").entrySet()) {
                            var arr = e.getValue().getAsJsonArray();
                            if (arr.size() < 2) continue;
                            keys.put(e.getKey(),
                                    new double[]{arr.get(0).getAsDouble(), arr.get(1).getAsDouble()});
                        }
                    }
                    if (keys.isEmpty()) {
                        RGBProfileMod.LOGGER.warn("RGB Profile: layout {} has no usable keys.", f.getFileName());
                        BackendHealth.problem("Layouts", f.getFileName() + " has no usable keys, so it is ignored.", null);
                        continue;
                    }
                    out.add(new KeyLayout(match, keys));
                    RGBProfileMod.LOGGER.info("RGB Profile: loaded layout {} ({} keys, matches '{}').",
                            f.getFileName(), keys.size(), match);
                } catch (Exception e) {
                    // Same policy as the profile loader: a broken file costs
                    // you that file and a log line, never the mod.
                    RGBProfileMod.LOGGER.warn("RGB Profile: could not read layout {}.", f.getFileName(), e);
                    BackendHealth.problem("Layouts", f.getFileName() + " could not be read, so it is ignored: " + e, null);
                }
            }
        } catch (Exception e) {
            RGBProfileMod.LOGGER.warn("RGB Profile: could not list layouts directory.", e);
        }
        return out;
    }

    /** {@code *} matches any run of characters; everything else is literal, case-insensitively. */
    static boolean globMatches(String glob, String text) {
        if (glob == null || text == null) return false;
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') re.append(".*");
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return text.matches("(?i)" + re);
    }

    /** Test seam: forget anything cached so a reload picks up edits. */
    static void invalidate() {
        cache = null;
    }

    @SuppressWarnings("unused")
    private static final Gson UNUSED = new Gson();
}
