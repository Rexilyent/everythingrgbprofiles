import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Which Minecraft version the checked-out branch is for, and which tuner
 * entries that rules in or out.
 *
 * <p>The tuner is one gitignored folder shared by every version branch, so it
 * sees whatever branch happens to be checked out. Most of the mod is the same
 * on all of them. The bits that aren't are content the game itself added in
 * a later version: the sulfur caves arrived in 26.2, so on 1.21.1 there is no
 * {@code SulfurCavePattern}, no sulfur palette, and no sulfur caves biome to
 * be standing in. Linking against any of that directly would mean the tuner
 * stops compiling the moment you check out an older branch, which is a fun
 * way to find out what branch you're on.
 *
 * <p>So everything version-bound goes through here twice. The version decides
 * whether the entry should exist; the class lookup decides whether it can.
 * The version comes from {@code gradle.properties}, the same file the build
 * reads, so the answer is always "the branch you're on" and never "whatever
 * got compiled last". If those two disagree (a 26.2 branch whose
 * {@code build/classes} is still left over from 1.21.1) the entry stays out
 * and {@link #problems} says why, rather than the sulfur theme quietly not
 * being there and you wondering whether you imagined it.
 *
 * <p>{@code -Dtuner.mcVersion=26.2} overrides the file, for pointing the tuner
 * at a build of some other branch.
 */
final class GameVersion {

    /** Minecraft 26.2: the sulfur caves, and a sulfur cave on the title screen. */
    static final String SULFUR = "26.2";

    /**
     * Patterns that only exist from a given version, by the name profiles use
     * for them. A biome profile naming one of these on an older branch would
     * otherwise fall through to {@code PatternFactory}'s shimmer and look
     * fine while being completely wrong.
     */
    private static final Map<String, String> PATTERN_SINCE = Map.of(
            "sulfur-cave", SULFUR);

    /** Biomes the game only has from a given version. */
    private static final Map<String, String> BIOME_SINCE = Map.of(
            "minecraft:sulfur_caves", SULFUR);

    private static final Path PROPERTIES = Paths.get("gradle.properties");

    /** The branch's Minecraft version, or null if neither the property nor the file says. */
    static final String CURRENT = read();

    private static final List<String> PROBLEMS = new ArrayList<>();

    private GameVersion() {
    }

    private static String read() {
        String forced = System.getProperty("tuner.mcVersion");
        if (forced != null && !forced.isBlank()) return forced.trim();
        if (!Files.exists(PROPERTIES)) return null;
        try (Reader r = Files.newBufferedReader(PROPERTIES)) {
            Properties p = new Properties();
            p.load(r);
            String v = p.getProperty("minecraft_version");
            return v == null || v.isBlank() ? null : v.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Whether the branch is on {@code version} or later.
     *
     * <p>An unreadable version counts as old. Showing a 26.2 entry on a
     * branch that might be 1.21.1 is the failure this class exists to stop,
     * and hiding one costs a {@code -Dtuner.mcVersion} at worst.
     */
    static boolean atLeast(String version) {
        return CURRENT != null && compare(CURRENT, version) >= 0;
    }

    /**
     * Numeric, part by part, so 1.21.10 is after 1.21.9 and 26.2 is after
     * 1.21.11. Comparing these as strings gets both of those wrong, which for
     * a class whose whole job is version comparison would be a bit much.
     * Anything non-numeric in a part (a "-pre1") is ignored from there on.
     */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(part(x, i), part(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) return 0;
        String digits = parts[i].replaceAll("[^0-9].*$", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /** Whether a profile naming this pattern belongs on this branch. */
    static boolean hasPattern(String pattern) {
        if (pattern == null) return true;
        String since = PATTERN_SINCE.get(pattern.trim().toLowerCase(java.util.Locale.ROOT));
        return since == null || atLeast(since);
    }

    /** Whether this biome exists in the branch's version of the game. */
    static boolean hasBiome(String id) {
        String since = BIOME_SINCE.get(id);
        return since == null || atLeast(since);
    }

    /**
     * A class the branch's version should have, or null with the reason
     * recorded when the build doesn't have it. Only call this for things the
     * version check already said should exist; on an older branch, a missing
     * class is the expected answer and not worth a word.
     */
    static Class<?> required(String className, String feature) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException | LinkageError e) {
            PROBLEMS.add(feature + ": this branch is Minecraft " + CURRENT + ", but "
                    + className.substring(className.lastIndexOf('.') + 1)
                    + " isn't in build/classes. Stale build from another branch? Run gradlew compileJava.");
            return null;
        }
    }

    /** Everything {@link #required} couldn't find, for {@code --check} and startup. */
    static List<String> problems() {
        return List.copyOf(PROBLEMS);
    }

    /** One line for {@code --check}. */
    static String describe() {
        return CURRENT == null
                ? "unknown (no minecraft_version in gradle.properties); version-bound entries hidden"
                : CURRENT + (atLeast(SULFUR) ? ", sulfur caves on" : ", sulfur caves off (they arrive in " + SULFUR + ")");
    }
}
