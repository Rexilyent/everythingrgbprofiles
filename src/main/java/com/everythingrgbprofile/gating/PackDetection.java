package com.everythingrgbprofile.gating;

import com.everythingrgbprofile.RGBProfileMod;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out whether we are running inside a particular modpack, so the menu
 * theme can be the pack's rather than everyone's.
 *
 * <h2>Why this is best-effort and says so</h2>
 * There is no API for "which modpack am I in". Launchers disagree completely
 * about where that lives, and one of them does not put it on disk at all:
 *
 * <ul>
 *   <li><b>CurseForge</b> writes {@code manifest.json} or
 *       {@code minecraftinstance.json}, with the pack name in it.</li>
 *   <li><b>Prism / MultiMC</b> write {@code instance.cfg} beside the game
 *       directory, with a {@code name=} line.</li>
 *   <li><b>A .mrpack installed anywhere</b> leaves {@code modrinth.index.json}
 *       with a {@code "name"} field.</li>
 *   <li><b>Modrinth App</b> keeps profile metadata in its own SQLite database
 *       and writes nothing identifying into the instance folder. The only
 *       signal left there is the folder's own name, which is the profile
 *       name — good enough in practice, and wrong the moment somebody renames
 *       it.</li>
 * </ul>
 *
 * <p>So this reads whatever it can find, falls back to the directory name, and
 * — the important part — <b>defaults to "no, we are not in that pack"</b> when
 * it cannot tell. Guessing wrong in that direction gives someone a plain menu
 * they did not choose; guessing wrong in the other gives them a pack's bespoke
 * art on a fresh single-mod install, which is a much ruder surprise.
 *
 * <h2>The config always wins</h2>
 * Detection is a convenience, never the mechanism. A pack maintainer who wants
 * a specific theme sets it outright in {@code defaultconfigs/}, which is the
 * normal way packs configure mods and does not depend on any of the guessing
 * above. This class exists so it also happens to work without them doing that.
 */
public final class PackDetection {

    /** Files that name the pack, relative to either the game dir or its parent. */
    private static final String[] METADATA_FILES = {
            "manifest.json", "minecraftinstance.json", "modrinth.index.json", "instance.cfg"
    };

    /** Pulls a pack name out of JSON ("name": "...") or an ini (name=...). */
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final Pattern INI_NAME = Pattern.compile("(?m)^name\\s*=\\s*(.{1,120})$");

    private static volatile String detectedPackName = null;
    private static volatile boolean resolved = false;

    /**
     * The pack's name if one could be found, otherwise the game directory's
     * name, otherwise null. Resolved once and cached — this touches the disk.
     */
    public static String packName() {
        if (resolved) return detectedPackName;
        synchronized (PackDetection.class) {
            if (resolved) return detectedPackName;
            detectedPackName = resolve();
            resolved = true;
            RGBProfileMod.LOGGER.info("RGB Profile: pack detection — {}",
                    detectedPackName == null
                            ? "no pack identified (this is normal for a plain install)"
                            : "running in '" + detectedPackName + "'");
            return detectedPackName;
        }
    }

    /**
     * True if the detected pack name contains any of the comma-separated
     * patterns, case-insensitively.
     *
     * <p>Substring rather than equality on purpose: pack names pick up version
     * suffixes and launcher decorations constantly ("Forge Everything 1.4.2",
     * "Forge Everything - Server"), and a match that breaks on a point release
     * is not much of a match.
     */
    public static boolean matches(String commaSeparatedPatterns) {
        String name = packName();
        if (name == null || commaSeparatedPatterns == null) return false;
        String haystack = name.toLowerCase(Locale.ROOT);
        for (String pattern : commaSeparatedPatterns.split(",")) {
            String needle = pattern.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && haystack.contains(needle)) return true;
        }
        return false;
    }

    private static String resolve() {
        Path gameDir;
        try {
            gameDir = FMLPaths.GAMEDIR.get();
        } catch (Exception e) {
            return null;
        }
        if (gameDir == null) return null;

        // The game dir first, then its parent: MultiMC-family launchers put the
        // instance metadata one level up from ".minecraft".
        for (Path root : new Path[]{gameDir, gameDir.getParent()}) {
            if (root == null) continue;
            for (String file : METADATA_FILES) {
                String name = readName(root.resolve(file));
                if (name != null) return name;
            }
        }

        // Nothing declared itself. The folder name is the last resort, and it
        // is the only thing Modrinth App leaves behind.
        Path leaf = gameDir.getFileName();
        if (leaf == null) return null;
        String name = leaf.toString();
        // ".minecraft" is a launcher convention, not a pack name.
        return name.isBlank() || name.startsWith(".") ? null : name;
    }

    private static String readName(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            // Capped: some of these files are megabytes of mod lists and the
            // name is always near the top.
            long size = Files.size(file);
            if (size > 512_000) return null;
            String text = Files.readString(file);
            var json = JSON_NAME.matcher(text);
            if (json.find()) return json.group(1).trim();
            var ini = INI_NAME.matcher(text);
            if (ini.find()) return ini.group(1).trim();
        } catch (Exception e) {
            // Unreadable, wrong encoding, locked by the launcher — any of which
            // just means this file is not the answer.
        }
        return null;
    }

    private PackDetection() {
    }
}
