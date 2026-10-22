package com.everythingrgbprofile.gating;

import com.everythingrgbprofile.RGBProfileMod;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out whether we're running inside a particular modpack, so the menu
 * theme can be that pack's instead of everyone's.
 *
 * <h2>Read this before assuming it runs</h2>
 * On a default config it doesn't. {@code menuTheme.style} ships as
 * {@code vanilla}, and {@code EffectRegistry.useMineshaftMenu} only reaches
 * this class on {@code auto} or on a value it doesn't recognise. So unless
 * somebody has opted in, nothing below ever executes and this class never
 * touches the disk at all.
 *
 * <p>An earlier version defaulted {@code style} to {@code auto}, which meant
 * every install ran the whole guessing routine below on startup to decide
 * whether to redecorate your title screen. That got changed to
 * {@code vanilla} when the mod went public, and for a boring but correct
 * reason: the mineshaft theme was drawn for the Forge Everything modpack
 * specifically, and a mod somebody just downloaded on its own has no business
 * defaulting to another pack's bespoke art. {@code auto} is still there for
 * anyone who actually wants it.
 *
 * <h2>Why this is best-effort and extremely loud about it</h2>
 * There is no API for "which modpack am I in". Launchers have never agreed on
 * where that information lives, and one of them doesn't put it on disk at all:
 *
 * <ul>
 *   <li><b>CurseForge</b> writes {@code manifest.json} or
 *       {@code minecraftinstance.json}, with the pack name in it.</li>
 *   <li><b>Prism / MultiMC</b> write {@code instance.cfg} beside the game
 *       directory, with a {@code name=} line.</li>
 *   <li><b>A .mrpack installed anywhere</b> leaves {@code modrinth.index.json}
 *       with a {@code "name"} field.</li>
 *   <li><b>Modrinth App</b> writes nothing identifying into the instance
 *       folder whatsoever. The only signal left is the folder's own name,
 *       which happens to be the profile name. Good enough in practice, and
 *       wrong the instant anybody renames anything.</li>
 * </ul>
 *
 * <p>So this reads whatever it can find, falls back to the folder name, and
 * then the part that actually matters: it <b>defaults to "no, we're not in
 * that pack"</b> whenever it can't tell. Get it wrong in that direction and
 * somebody gets a plain menu they didn't specifically ask for, which is a
 * non-event. Get it wrong the other way and a fresh single-mod install
 * suddenly has a stranger's bespoke art direction on its title screen, which
 * is a significantly ruder thing to do to a person.
 *
 * <h2>The config always wins</h2>
 * Detection is a convenience and never the mechanism. A pack maintainer who
 * wants a specific theme sets it outright in {@code defaultconfigs/}, which is
 * how packs configure mods normally and which depends on exactly none of the
 * guesswork above. This class exists so that it also happens to work when they
 * haven't.
 */
public final class PackDetection {

    /** Files that name the pack, relative to the game dir or to its parent. */
    private static final String[] METADATA_FILES = {
            "manifest.json", "minecraftinstance.json", "modrinth.index.json", "instance.cfg"
    };

    /**
     * Pulls a pack name out of JSON ({@code "name": "..."}) or an ini
     * ({@code name=...}). Two regexes rather than a JSON parser, because the
     * only field wanted is one string near the top of the file, and a parser
     * would build the whole mod list into objects just to throw all of it
     * away. The catch: this takes the first {@code "name"} it finds, and
     * nothing checks that it belongs to the pack rather than to something
     * nested above it. Best-effort, as advertised.
     */
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final Pattern INI_NAME = Pattern.compile("(?m)^name\\s*=\\s*(.{1,120})$");

    private static volatile String detectedPackName = null;
    private static volatile boolean resolved = false;

    /**
     * The pack's name if one turned up, otherwise the game directory's name,
     * otherwise null. Resolved once and cached, because this hits the disk and
     * the answer is not going to change halfway through a session.
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
     * patterns, ignoring case.
     *
     * <p>Substring instead of equality on purpose. Pack names collect version
     * suffixes and launcher decorations constantly ("Forge Everything 1.4.2",
     * "Forge Everything - Server"), and a match that falls over on a point
     * release is not really a match.
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

        // Game dir first, then its parent, because the MultiMC family parks
        // its instance metadata one level up from ".minecraft" rather than
        // inside it.
        for (Path root : new Path[]{gameDir, gameDir.getParent()}) {
            if (root == null) continue;
            for (String file : METADATA_FILES) {
                String name = readName(root.resolve(file));
                if (name != null) return name;
            }
        }

        // Nothing on disk declared itself. Folder name is the last resort, and
        // for Modrinth App it is the only thing there has ever been.
        Path leaf = gameDir.getFileName();
        if (leaf == null) return null;
        String name = leaf.toString();
        // ".minecraft" is a launcher convention rather than anybody's pack
        // name, and neither is anything else starting with a dot.
        return name.isBlank() || name.startsWith(".") ? null : name;
    }

    private static String readName(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            // Anything over 512KB is skipped outright, not read partway.
            // Some of these manifests are megabytes of mod list, and this
            // reads a file whole, so a huge one gets passed over and the
            // search moves on to the next file (or the folder name) rather
            // than pulling megabytes into a String at startup. Under the cap
            // the whole file is read, which at that size is nothing.
            long size = Files.size(file);
            if (size > 512_000) return null;
            String text = Files.readString(file);
            var json = JSON_NAME.matcher(text);
            if (json.find()) return json.group(1).trim();
            var ini = INI_NAME.matcher(text);
            if (ini.find()) return ini.group(1).trim();
        } catch (Exception e) {
            // Unreadable, wrong encoding, or locked by the launcher that is
            // very much still running. All of those mean the same thing here,
            // which is that this file isn't the answer, so move on to the next
            // one rather than making it somebody's crash report.
        }
        return null;
    }

    private PackDetection() {
    }
}
