import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternFactory;
import com.everythingrgbprofile.pattern.PatternParams;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Colour tuner: every biome and portal profile, on the real keyboard, with hue,
 * saturation and brightness on sliders.
 *
 * <p>Sibling to {@code PatternStudio}. That one tunes a pattern's <i>shape</i>
 * — radii, speeds, counts. This one tunes its <b>colour</b>, against the actual
 * profiles the game loads, on the actual hardware.
 *
 * <h2>Why this has to drive real LEDs</h2>
 * A monitor and a keyboard disagree about colour badly enough that tuning on
 * screen actively misleads. The specific failure this tool exists to fix:
 * colours that look correct as paint come out washed toward white on a
 * keyboard, because an RGB LED behind a diffuser mixes its three dies
 * additively. A "bright green" like {@code #56E063} carries R=86 and B=99
 * alongside G=224 — over a third of its output is white light, and that is
 * exactly what the eye sees. On screen it still looks green, so the problem is
 * invisible until it is on the board.
 *
 * <p>Hence the layout: the on-screen keyboard is for reading the
 * <i>animation</i>, and the numbers next to the sliders — saturation
 * especially — are for reading the colour. Trust the hardware and the numbers,
 * not the pixels.
 *
 * <h2>What it loads</h2>
 * The shipped defaults unioned with the live config, user copy winning, which
 * is precisely what {@code JsonProfileLoader} does in the mod. So the list here
 * is the list in the game, including any entry that has drifted from its
 * default — and the tuner reads that drift rather than hiding it.
 *
 * <p>It parses that JSON itself in about sixty lines rather than going through
 * the mod's loader, deliberately: {@code tuning/stubs} contains a fake Gson so
 * PatternStudio can compile without the real one, and a tool that silently got
 * the stub would show an empty profile list and no error worth reading.
 *
 * <h2>The portal entries</h2>
 * Entries prefixed {@code [portal]} come from {@code dimension_profiles.json}
 * and render the same {@code CoreEmitterPattern} field the portal charge and
 * arrival use, built through the mod's own {@code DimensionProfile} rather than
 * reconstructed here. They were missing for a long time, which meant the one
 * effect in the mod with a hand-fitted multi-stop gradient was the one effect
 * that could not be looked at on hardware — and a gradient is exactly the thing
 * a monitor lies about most.
 *
 * <p>They live in the same list as the biomes, behind a prefix, because the two
 * files collide: {@code minecraft:the_end} is both a biome and a dimension with
 * different colours and a different pattern, and merging them flat would
 * silently drop one.
 *
 * <p>A portal profile that authors its own {@code gradient} ignores the colour
 * sliders — that is what the mod does with it, not a limitation here, and the
 * status line says so rather than leaving anyone dragging a control that does
 * nothing. {@code copy JSON} emits a full dimension block with every other
 * authored field carried through, so pasting it back cannot quietly strip a
 * profile's geometry.
 *
 * <h2>The boss entries</h2>
 * Entries prefixed {@code [boss]} are the boss layers, and they come from two
 * places. The generic boss-bar pulse has one entry per
 * {@code boss_profiles.json} profile — that file was the one of the three the
 * tuner did not read, which left every boss colour in the mod unreachable from
 * the only tool that can judge a colour on hardware. The hand-built layers —
 * Wither, Naga, Ender Dragon, Slider, Sun Spirit, Valkyrie Queen, Warden emergence — keep their colours in the
 * TOML config instead, which is read where it can be found and otherwise falls
 * back to the shipped {@code ColorPalette} constants, with the status line
 * saying which of the two is on screen.
 *
 * <p>The Wither and the dragon each appear twice as a result, which is
 * correct: the mod really does have two layers for them, at different
 * priorities, in different files, with different colours. The dedicated one
 * wins in game.
 *
 * <p>These entries are not patterns. They are {@code EffectController}s, and
 * they draw nothing but their opening frame until something tells them what
 * the boss is doing — so {@code BossThemes} scripts that, and the <b>fight
 * progress</b> slider drives it. Without it the Wither's powered colour, the
 * dragon's death rings and every other state-gated colour in the mod could not
 * be looked at at all, on hardware or anywhere else. {@code copy} emits a TOML
 * table for the hand-built layers and a JSON block for the boss-bar ones,
 * matching where each of them actually lives.
 *
 * <h2>The effect entries</h2>
 * Entries prefixed {@code [effect]} are the rest of the effects with colours in
 * the TOML config — burning, drowning, rain, the moon, the death blood, the
 * Warden's presence and the vanilla menu. They are the same kind of thing as a
 * boss layer, an effect controller that has to be told the game's state, so
 * they run through the same theme machinery and every boss control applies to
 * them. The overlays among them are drawn over the last biome selected, as
 * they would be over a biome in game, or over black on request. See
 * {@code EffectThemes} and {@link #backdropBiome}.
 *
 * <h2>The log is the point</h2>
 * Sliders are for finding the colour; the log is for keeping it. Every entry
 * records where a colour started, where it ended, what moved in HSV terms, and
 * a free-text note — because "what a person actually wanted it to look like"
 * is the one thing no measurement in this project can recover after the fact.
 *
 * <pre>
 *   gradlew compileJava
 *   javac -cp build\classes\java\main -d tuning tuning\ColorTuner.java tuning\PatternStudio.java tuning\HardwareBridge.java
 *   java  -cp "build\classes\java\main;tuning;jna-5.14.0.jar" ColorTuner
 * </pre>
 *
 * JNA is only needed to tick "drive real keyboard"; without it everything else
 * still runs.
 *
 * <h2>The RGB wordmark</h2>
 * The last entry in the profile list is not a biome. It lights the keys that
 * spell "RGB" — one letter per colour channel — and exists to produce the
 * banner and mod-icon art from the same board renderer as everything else
 * here, rather than a hand-drawn mock-up that drifts from what the mod looks
 * like. It never drives the keyboard; "export PNG" writes the image to
 * {@code tuning/export/}, or run headless with {@code --wordmark}.
 */
public class ColorTuner {

    /** Maintainer-only options, read from the gitignored local.properties. */
    static final boolean EXPORTS = localFlag("tuner_exports");

    /** A true/false key from local.properties in the working directory; false if the file or key is missing. */
    static boolean localFlag(String key) {
        Path file = Paths.get("local.properties");
        if (!Files.isRegularFile(file)) return false;
        java.util.Properties props = new java.util.Properties();
        try (java.io.Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            return false;
        }
        return Boolean.parseBoolean(props.getProperty(key, "false").trim());
    }

    // ---------------------------------------------------------------
    // Where the profiles live
    // ---------------------------------------------------------------

    /** Shipped defaults, relative to the repo root. */
    private static final Path DEFAULTS =
            Paths.get("src/main/resources/everythingrgbprofile_defaults/biome_profiles.json");
    /**
     * The live config. Overridable with {@code -Dtuner.config=...} for a
     * different Minecraft instance.
     */
    private static final Path LIVE = Paths.get(System.getProperty("tuner.config",
            System.getenv("APPDATA") + "/ModrinthApp/profiles/Forge Everything/config/"
                    + "everythingrgbprofiles/biome_profiles.json"));

    /**
     * The portal profiles, unioned the same way.
     *
     * <p>Derived from whatever {@link #LIVE} turned out to be rather than
     * configured separately, so {@code -Dtuner.config=} pointing at another
     * instance moves both files together. Pointing one at instance A and the
     * other at instance B is not a thing anybody wants and is very much a thing
     * two independent properties would eventually do.
     */
    private static final Path DIM_DEFAULTS =
            Paths.get("src/main/resources/everythingrgbprofile_defaults/dimension_profiles.json");
    private static final Path DIM_LIVE = LIVE.resolveSibling("dimension_profiles.json");

    /** The generic boss-bar profiles, unioned the same way again. */
    private static final Path BOSS_DEFAULTS =
            Paths.get("src/main/resources/everythingrgbprofile_defaults/boss_profiles.json");
    private static final Path BOSS_LIVE = LIVE.resolveSibling("boss_profiles.json");

    /**
     * The mod's TOML config, which is where the dedicated boss layers keep
     * their colours — the Wither's three, the Naga's two, the dragon's three,
     * the Slider's three, the Sun Spirit's three, the Valkyrie Queen's three,
     * the Warden's one. Nothing
     * else the tuner shows lives in TOML.
     *
     * <p>It sits one level above the JSON profiles: those are in
     * {@code config/everythingrgbprofiles/}, this is
     * {@code config/everythingrgbprofiles-common.toml}, which is the file name
     * {@code RGBProfileMod} registers. Derived from {@link #LIVE} so
     * {@code -Dtuner.config=} moves all four files together.
     *
     * <p>Missing is normal and not an error — a fresh install has no config
     * until the game writes one — so the boss themes fall back to the shipped
     * {@code ColorPalette} constants and the status line says which of the two
     * it is showing. What would be a real problem is showing a stale colour
     * while implying it is live, which is why that distinction is on screen
     * rather than assumed.
     */
    private static final Path CONFIG_TOML = LIVE.getParent() == null
            ? Paths.get("everythingrgbprofiles-common.toml")
            : LIVE.getParent().resolveSibling("everythingrgbprofiles-common.toml");

    private static final Path LOG = Paths.get("tuning/color_tuning_log.md");

    /**
     * Prefix marking a portal entry in the picker.
     *
     * <p>Needed because the two files genuinely collide: {@code
     * minecraft:the_end} is both a biome and a dimension, with different
     * colours and different patterns, and a flat merge would silently drop one
     * of them. Sorts ahead of every biome id, so the portals group at the top
     * of the list rather than hiding among two hundred biomes.
     */
    static final String PORTAL_PREFIX = "[portal] ";

    /**
     * Prefix marking a boss entry, for the same collision reason as the
     * portals and one more: {@code minecraft:ender_dragon} and
     * {@code minecraft:wither} each name <i>two</i> different layers — the
     * generic boss-bar pulse from {@code boss_profiles.json}, and the
     * dedicated phase-aware effect that outranks it. Both are real, both are
     * tunable, and they are different colours in different files. Sorts ahead
     * of the biomes next to the portals.
     */
    static final String BOSS_PREFIX = "[boss] ";

    /**
     * Prefix marking a non-boss effect: burning, drowning, rain, the moon and
     * the rest. They go through the same theme machinery as the boss layers,
     * so everything the tuner does with a boss — the progress slider, TOML
     * copy, phase-looped GIFs — works on them unchanged. See {@code EffectThemes}.
     */
    static final String EFFECT_PREFIX = "[effect] ";

    /**
     * The biome overlay effects are drawn over until another is chosen. See
     * {@link #themePattern}.
     */
    static final String BACKDROP_BIOME = "minecraft:plains";

    /**
     * The biome overlay effects are currently drawn over: the last biome
     * selected in the picker, so picking a desert and then an effect shows the
     * effect over desert.
     *
     * <p>A fixed backdrop was misleading in one specific way. Plains is green,
     * and green showing above the flames reads as something the fire effect is
     * drawing rather than the biome showing through it. Letting the backdrop
     * be whatever was last looked at makes the two easy to tell apart, and
     * lets an overlay's colours be judged against the biome that actually
     * matters for them.
     *
     * <p>{@code -Dtuner.effect.backdrop=<biome id>} sets the starting one, which
     * is how a headless GIF export chooses it; {@code =black} starts with
     * {@link #overlaysOverBlack} on instead.
     */
    static volatile String backdropBiome = startingBackdrop();

    /**
     * Draw overlay effects over black instead of a biome, so what is on the
     * board is the effect's own output and nothing else. Keys the effect leaves
     * transparent then show as unlit.
     */
    static volatile boolean overlaysOverBlack =
            "black".equalsIgnoreCase(System.getProperty("tuner.effect.backdrop", ""));

    private static String startingBackdrop() {
        String chosen = System.getProperty("tuner.effect.backdrop", BACKDROP_BIOME).trim();
        return chosen.isEmpty() || "black".equalsIgnoreCase(chosen) ? BACKDROP_BIOME : chosen;
    }

    /**
     * One profile entry, as authored.
     *
     * @param extra a third colour, for the boss layers that have one. Null
     *              everywhere else, which is what the third slider reads to
     *              know whether it applies.
     * @param raw   the entry's own JSON, for the two kinds that come from a
     *              profile file with more in it than colours — a portal and a
     *              boss-bar boss. Carried raw rather than parsed into fields
     *              because the portal effect alone has thirty-odd knobs and
     *              this tool has no business knowing which ones exist (see
     *              {@link #buildDimensionProfile}, which hands them to the
     *              mod's own class and lets it decide), and because every key
     *              in it has to survive a round trip through
     *              {@link #profileBlock} untouched.
     * @param boss  the script that drives this entry's boss layer, or null if
     *              it is not one
     */
    record Prof(String id, String color, String accent, String extra,
                String pattern, String preset,
                Map<String, Object> raw, BossThemes.Theme boss) {

        /** A biome: two colours, a named pattern, an optional preset. */
        Prof(String id, String color, String accent, String pattern, String preset) {
            this(id, color, accent, null, pattern, preset, null, null);
        }

        /** A portal, carrying its raw dimension entry. */
        Prof(String id, String color, String accent, Map<String, Object> raw) {
            this(id, color, accent, null, null, null, raw, null);
        }

        /**
         * A boss layer: up to three colours, the script that drives it, and
         * for a boss-bar boss the JSON entry its colours came from.
         */
        Prof(String id, String color, String accent, String extra,
             Map<String, Object> raw, BossThemes.Theme boss) {
            this(id, color, accent, extra, null, null, raw, boss);
        }

        boolean isPortal() {
            return raw != null && boss == null;
        }

        /**
         * True for every entry driven by a theme script — the boss layers and
         * the {@code [effect]} entries alike, since the tuner handles the two
         * identically. {@link #isEffect} tells them apart where the words differ.
         */
        boolean isBoss() {
            return boss != null;
        }

        boolean isEffect() {
            return boss != null && id.startsWith(EFFECT_PREFIX);
        }
    }

    static final Map<String, Prof> PROFILES = new TreeMap<>();

    // ---------------------------------------------------------------
    // Live state
    // ---------------------------------------------------------------

    static Prof current;
    static Pattern pattern;
    static long clockStart = System.currentTimeMillis();

    /**
     * Working HSV for the two colour slots. Hue in degrees, sat/val 0..1.
     *
     * <p><b>final, and it matters.</b> The sliders, the swatches and the
     * readouts are all handed these arrays once when the window is built, and
     * they hold that reference forever. Reassigning either field on a profile
     * change — which is the obvious way to write {@link #selectProfile} — swaps
     * the array the renderer reads while leaving every control pointing at the
     * old one. The board then shows the newly selected profile while the panel
     * shows the previous profile's numbers, and dragging a slider writes into
     * an array nothing renders from.
     *
     * <p>So these are mutated in place and never replaced, and {@code final}
     * is here to make the compiler reject the regression rather than trusting
     * anyone to remember.
     */
    static final double[] baseHsv = {120, 0.8, 0.6};
    static final double[] accentHsv = {120, 0.9, 1.0};
    /**
     * A third slot, for the boss layers that genuinely have three colours: the
     * Wither's bone, eyes and powered, the dragon's void, accent and breath
     * fire. Same in-place rule as the two above, for the same reason.
     */
    static final double[] extraHsv = {0, 0.0, 1.0};
    static boolean hasAccent = true;
    static boolean hasExtra = false;

    /**
     * Where the selected boss theme is in its encounter: 0 at the start, 1 at
     * the end.
     *
     * <p>Means nothing for a biome or a portal, which have no state to be in.
     * For a boss it is the only way to reach most of the colours — the Wither's
     * powered colour does not exist above half health, and the dragon's death
     * rings do not exist until it is dying.
     */
    static volatile double bossProgress = clamp01(doubleProperty("tuner.progress", 0.25));

    static final KeyGrid GRID = PatternStudio.buildGrid();
    static volatile Map<KeyGrid.LedRef, RGBColor> frame = new HashMap<>();

    static HardwareBridge hardware;
    /**
     * The status line. A fixed-size wrapping text area rather than a label,
     * for the reason given at {@link #SIDE_WIDTH}.
     */
    static JTextArea status;

    /**
     * Width of the control panel, fixed.
     *
     * <p>The panel sits in the frame's east slot, which hands it its preferred
     * width, and a panel's preferred width is its widest child. An earlier
     * version left that to the children, and the widest was the status line,
     * which the fight-progress slider rewrites on every tick of a drag. So
     * each tick resized the panel and slid every control sideways, which moved
     * the slider out from under the mouse, which changed the value and the
     * text again: the slider jittered back and forth on its own.
     *
     * <p>The 430 every row is capped at, plus the panel's 12px border either
     * side.
     */
    static final int SIDE_WIDTH = 430 + 24 + 12;
    /** Every row in the sidebar is capped at this. */
    static final int ROW_WIDTH = 430;
    static final int SCROLLBAR_WIDTH = 12;
    static Board board;
    static JTextField noteField;
    static final List<JComponent> swatches = new ArrayList<>();
    /** Controls that edit or record a biome profile, and so mean nothing for the wordmark. */
    static final List<JComponent> tuningOnly = new ArrayList<>();
    static JButton exportBtn;
    /** Controls that only mean something to the key painter. */
    static final List<JComponent> paintOnly = new ArrayList<>();

    // ---------------------------------------------------------------
    // Colour helpers
    // ---------------------------------------------------------------

    static double[] toHsv(RGBColor c) {
        float[] hsb = Color.RGBtoHSB(c.r(), c.g(), c.b(), null);
        return new double[]{hsb[0] * 360.0, hsb[1], hsb[2]};
    }

    /** Writes a colour into an existing HSV array, preserving its identity. */
    static void setHsv(double[] target, RGBColor c) {
        double[] hsv = toHsv(c);
        // A fully desaturated colour reports hue 0, which would silently drag
        // the hue slider to red every time a grey profile is selected and lose
        // whatever hue was there. Keep the old hue when there is no real one to
        // read.
        if (hsv[1] > 0.001) target[0] = hsv[0];
        target[1] = hsv[1];
        target[2] = hsv[2];
    }

    static RGBColor fromHsv(double[] hsv) {
        Color c = Color.getHSBColor((float) (hsv[0] / 360.0), (float) hsv[1], (float) hsv[2]);
        return new RGBColor(c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * The fraction of a colour's output that is white.
     *
     * <p>The single most useful number on the panel. An LED's three dies always
     * sum additively, so whatever all three channels share is emitted as white
     * and dilutes the hue. 0.00 is a pure hue; 0.40 means nearly half of what
     * you see is white, which is what "vivid green" turns into on hardware if
     * it was brightened by raising every channel at once.
     */
    static double whiteFraction(RGBColor c) {
        int max = Math.max(c.r(), Math.max(c.g(), c.b()));
        if (max == 0) return 0;
        return Math.min(c.r(), Math.min(c.g(), c.b())) / (double) max;
    }

    // ---------------------------------------------------------------
    // Profile loading
    // ---------------------------------------------------------------

    /** Biome profiles {@link GameVersion} kept out on this branch, so {@code --check} can say so. */
    static final List<String> HIDDEN_BY_VERSION = new ArrayList<>();

    static void loadProfiles() {
        PROFILES.clear();
        HIDDEN_BY_VERSION.clear();

        union(LIVE, DEFAULTS).forEach((id, v) -> {
            if (!(v instanceof Map<?, ?> m)) return;
            // A biome from a newer game than this branch, or one drawn with a
            // pattern this branch doesn't have. The shipped defaults never
            // carry one, but a live config last written by a 26.2 instance
            // does, and it would show up here as a sulfur cave rendered as a
            // shimmer. Accurate to nothing.
            if (!GameVersion.hasBiome(id) || !GameVersion.hasPattern(str(m.get("pattern")))) {
                HIDDEN_BY_VERSION.add(id);
                return;
            }
            PROFILES.put(id, new Prof(id,
                    str(m.get("color")), str(m.get("accentColor")),
                    str(m.get("pattern")), str(m.get("preset"))));
        });

        // The portal effects, under a prefix so they cannot collide with a
        // biome of the same id. Colour and accent come from the same two keys
        // as a biome's, which is what lets the existing sliders drive them
        // without a second set of controls.
        union(DIM_LIVE, DIM_DEFAULTS).forEach((id, v) -> {
            if (!(v instanceof Map<?, ?> m)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) m;
            PROFILES.put(PORTAL_PREFIX + id, new Prof(PORTAL_PREFIX + id,
                    str(m.get("color")), str(m.get("accentColor")), raw));
        });

        loadBossProfiles();
        loadDedicatedBosses();
        loadTomlThemes(EFFECT_PREFIX, EffectThemes.all());
    }

    /**
     * The generic boss-bar layer, one entry per {@code boss_profiles.json}
     * profile.
     *
     * <p>This file was the one of the three the tuner did not read, which made
     * every boss colour in the mod unreachable from the one tool built for
     * judging colours on hardware. They are two colours and a threshold — the
     * same shape as a biome — so they need no special handling beyond the
     * effect that draws them.
     */
    static void loadBossProfiles() {
        union(BOSS_LIVE, BOSS_DEFAULTS).forEach((id, v) -> {
            if (!(v instanceof Map<?, ?> m)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) m;
            String enrage = str(m.get("enrageColor"));
            Integer threshold = m.get("enrageThresholdPercent") instanceof Number n
                    ? n.intValue() : null;
            // An enrage colour with no threshold never shifts, and a threshold
            // with no colour has nothing to shift to. The mod needs both, so
            // the tuner offers the second slider only when both are there.
            boolean hasEnrage = enrage != null && threshold != null;
            String key = BOSS_PREFIX + id;
            PROFILES.put(key, new Prof(key, str(m.get("color")),
                    hasEnrage ? enrage : null, null, raw,
                    BossThemes.bossBar(hasEnrage ? threshold : null)));
        });
    }

    /**
     * The hand-built boss layers, whose colours live in the TOML config.
     *
     * <p>Keyed by a name rather than an entity id, because that is what they
     * are: {@code [boss] naga} is the Naga serpent effect, not whatever entity
     * happens to be called a naga. The Wither and the dragon therefore appear
     * twice in the list, once here and once from the JSON above, which is
     * correct — the mod really does have two separate layers for each, at
     * different priorities and different colours.
     */
    static void loadDedicatedBosses() {
        loadTomlThemes(BOSS_PREFIX, BossThemes.dedicated());
    }

    /** Adds one entry per theme, with each colour read from the live TOML where it is set. */
    static void loadTomlThemes(String prefix, List<BossThemes.Theme> themes) {
        Map<String, Map<String, String>> toml = readToml(CONFIG_TOML);
        for (BossThemes.Theme theme : themes) {
            Map<String, String> section = toml.getOrDefault(theme.section(), Map.of());
            List<String> hex = new ArrayList<>();
            for (BossThemes.Slot slot : theme.slots()) {
                String live = section.get(slot.configKey());
                hex.add(live != null ? live : slot.shipped().toHex());
            }
            String key = prefix + theme.id();
            PROFILES.put(key, new Prof(key, hex.get(0),
                    hex.size() > 1 ? hex.get(1) : null,
                    hex.size() > 2 ? hex.get(2) : null,
                    null, theme));
        }
    }

    /**
     * The user's copy of a profile file over the shipped defaults, per entry.
     *
     * <p>{@code putIfAbsent}, exactly as {@code JsonProfileLoader} unions them:
     * the live config wins per entry, and defaults only fill in keys the config
     * has never seen. A live file that predates a new shipped entry therefore
     * still shows that entry, which is the case this tool most often gets
     * pointed at.
     */
    static Map<String, Object> union(Path live, Path defaults) {
        Map<String, Object> merged = new HashMap<>(readJsonObject(live));
        readJsonObject(defaults).forEach(merged::putIfAbsent);
        return merged;
    }

    /**
     * Turns a raw {@code dimension_profiles.json} entry into the mod's own
     * {@code DimensionProfile}, by field name.
     *
     * <p>Reflection, in a tool that otherwise avoids it, for a specific reason:
     * the profile carries thirty-odd optional fields and gains more whenever
     * the portal effect does. Listing them here would mean this tool silently
     * ignoring any field added after it was written — which is the exact class
     * of bug it is supposed to catch, since a field the tuner ignores is a field
     * you tune blind. Going by name means a new knob works here the day it
     * exists, and {@link #unknownKeys} reports anything the JSON names that the
     * class does not.
     *
     * <p>Note this is only the JSON-to-object step. Everything downstream —
     * the ramp, the settings merge, the defaults for anything unset — is the
     * mod's own code, so what renders here is what the game renders.
     */
    static Object buildDimensionProfile(Map<String, Object> raw) throws Exception {
        Class<?> type = Class.forName("com.everythingrgbprofile.profile.DimensionProfile");
        Object profile = type.getDeclaredConstructor().newInstance();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            java.lang.reflect.Field f;
            try {
                f = type.getField(e.getKey());
            } catch (NoSuchFieldException unknown) {
                continue; // reported by unknownKeys(); not worth failing a render over
            }
            Object value = coerce(f.getType(), e.getValue());
            if (value != null) f.set(profile, value);
        }
        return profile;
    }

    /**
     * Fits a parsed JSON value to a profile field's declared type.
     *
     * <p>The JSON reader here produces String, Double, Boolean and List and
     * nothing else, while the profile's numeric fields are boxed Double and
     * Integer precisely so null can mean "unset". So the only real work is
     * Double to Integer, and refusing anything that does not fit rather than
     * throwing — a hand-edited file with a string where a number goes should
     * cost that one field, not the whole profile.
     */
    static Object coerce(Class<?> target, Object value) {
        if (value == null) return null;
        if (target == String.class) return value instanceof String s ? s : null;
        if (target == Double.class) return value instanceof Number n ? n.doubleValue() : null;
        if (target == Integer.class) return value instanceof Number n ? (Integer) n.intValue() : null;
        if (target == Boolean.class) return value instanceof Boolean b ? b : null;
        if (target == List.class) {
            if (!(value instanceof List<?> l)) return null;
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) if (o instanceof String s) out.add(s);
            return out;
        }
        return null;
    }

    /** JSON keys in an entry that no field on {@code DimensionProfile} answers to. */
    static List<String> unknownKeys(Map<String, Object> raw) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> type = Class.forName("com.everythingrgbprofile.profile.DimensionProfile");
            for (String key : raw.keySet()) {
                try {
                    type.getField(key);
                } catch (NoSuchFieldException e) {
                    out.add(key);
                }
            }
        } catch (ClassNotFoundException e) {
            return out;
        }
        return out;
    }

    static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    // --- a small TOML reader, for the boss colours ---------------------

    /**
     * The config's string values, by table then key.
     *
     * <p>Twenty lines rather than a TOML library, and worth saying why given
     * the tuning tools already carry a stub Gson to avoid exactly this sort of
     * thing. What is needed here is quoted strings under {@code [table]}
     * headers, one per line, which is the shape NeoForge writes and the shape
     * every colour in that file has. Numbers, arrays, nesting, multi-line
     * strings and dotted keys are all skipped rather than half-parsed: nothing
     * the tuner reads from this file is any of those, and a reader that quietly
     * mangled them would be worse than one that ignores them.
     *
     * <p>Values are returned as written, without hex validation.
     * {@code RGBColor.fromHexOrDefault} is what the mod itself does with them,
     * and it is what the caller does here, so a malformed colour in the config
     * looks the same in the tuner as it does in game.
     */
    static Map<String, Map<String, String>> readToml(Path p) {
        Map<String, Map<String, String>> out = new java.util.LinkedHashMap<>();
        if (!Files.exists(p)) return out;
        try {
            String table = "";
            for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int close = line.indexOf(']');
                    // Array-of-tables headers start "[[" and none of ours do.
                    if (close > 1 && !line.startsWith("[[")) table = line.substring(1, close).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() < 2 || value.charAt(0) != '"') continue;
                int end = value.indexOf('"', 1);
                if (end < 1) continue;
                out.computeIfAbsent(table, k -> new java.util.LinkedHashMap<>())
                        .put(key, value.substring(1, end));
            }
        } catch (Exception e) {
            System.err.println("could not read " + p + ": " + e);
        }
        return out;
    }

    // --- a small JSON reader, sufficient for these files ---------------

    static Map<String, Object> readJsonObject(Path p) {
        try {
            if (!Files.exists(p)) return Map.of();
            return new Json(Files.readString(p, StandardCharsets.UTF_8)).object();
        } catch (Exception e) {
            System.err.println("could not read " + p + ": " + e);
            return Map.of();
        }
    }

    /** Recursive-descent JSON, values only as far as this tool needs them. */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Map<String, Object> object() {
            ws();
            expect('{');
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return out; }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                out.put(key, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') return out;
                if (c != ',') throw new IllegalStateException("expected , or } at " + i);
            }
        }

        Object value() {
            ws();
            char c = peek();
            if (c == '{') return object();
            if (c == '[') {
                i++;
                List<Object> out = new ArrayList<>();
                ws();
                if (peek() == ']') { i++; return out; }
                while (true) {
                    out.add(value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == ']') return out;
                    if (d != ',') throw new IllegalStateException("expected , or ] at " + i);
                }
            }
            if (c == '"') return string();
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String raw = s.substring(start, i);
            if (raw.equals("true")) return Boolean.TRUE;
            if (raw.equals("false")) return Boolean.FALSE;
            if (raw.equals("null")) return null;
            return Double.valueOf(raw);
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    b.append(switch (e) {
                        case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r';
                        case 'b' -> '\b'; case 'f' -> '\f';
                        case 'u' -> (char) Integer.parseInt(s.substring(i, i += 4), 16);
                        default -> e;
                    });
                } else b.append(c);
            }
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { return s.charAt(i); }
        void expect(char c) {
            ws();
            if (s.charAt(i++) != c) throw new IllegalStateException("expected " + c + " at " + i);
        }
    }

    // ---------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------

    static void selectProfile(String id) {
        wordmarkMode = WORDMARK.equals(id);
        paintMode = PAINT.equals(id);
        if (stillImage()) return;
        current = PROFILES.get(id);
        if (current == null) return;
        // A plain biome becomes what overlay effects are drawn over. See
        // backdropBiome.
        if (!current.isPortal() && !current.isBoss()) backdropBiome = id;
        // Restart the clock, so a stateful pattern re-seeds the way it does
        // when an effect re-activates in game rather than resuming mid-flight.
        clockStart = System.currentTimeMillis();

        RGBColor base = current.color() != null
                ? RGBColor.fromHexOrDefault(current.color(), RGBColor.WHITE) : RGBColor.WHITE;
        hasAccent = current.accent() != null;
        RGBColor accent = hasAccent
                ? RGBColor.fromHexOrDefault(current.accent(), base)
                : base.lightened(0.6);
        // Copied into the existing arrays rather than assigned over them. See
        // the note on the fields for what breaks otherwise.
        setHsv(baseHsv, base);
        setHsv(accentHsv, accent);

        hasExtra = current.extra() != null;
        if (hasExtra) setHsv(extraHsv, RGBColor.fromHexOrDefault(current.extra(), base));

        if (current.isBoss()) {
            // Built once and kept, unlike the portal field, which is rebuilt
            // per frame. A boss controller is a state machine carrying
            // animation state — the body position, the ritual, the pulse clock
            // — so rebuilding it on a colour change would restart the
            // animation on every nudge of a slider. The colours reach it live
            // instead, through sliderColors.
            pattern = livePattern(current.boss());
            return;
        }
        pattern = current.isPortal() ? null
                : PatternFactory.fromNameAndPreset(current.pattern(), current.preset());
    }

    /**
     * A theme as a pattern, with the slider colours, and drawn over
     * {@link #backdropBiome} when it is one of the overlay effects — unless
     * {@link #overlaysOverBlack} is on.
     *
     * <p>Over the biome's real pattern and its configured colours, freshly
     * built, so the rain falls on a biome that moves the way it moves in game.
     * Falls back to plains when the chosen biome is not in the list, and to
     * black when neither is.
     */
    static Pattern themePattern(BossThemes.Theme theme, BossThemes.ProgressSource progress) {
        BossThemes.Colors colors = sliderColors(theme);
        Prof biome = backdropProfile();
        if (!EffectThemes.isOverlay(theme) || overlaysOverBlack || biome == null || biome.pattern() == null) {
            return BossThemes.pattern(staged(theme), colors, progress);
        }
        RGBColor base = RGBColor.fromHexOrDefault(biome.color(), RGBColor.WHITE);
        RGBColor accent = biome.accent() != null ? RGBColor.fromHexOrDefault(biome.accent(), base) : null;
        return BossThemes.pattern(staged(theme), colors, progress, new BossThemes.Backdrop(
                PatternFactory.fromNameAndPreset(biome.pattern(), biome.preset()), base, accent));
    }

    /** The profile overlay effects are drawn over right now, or null if there is none to draw. */
    static Prof backdropProfile() {
        Prof chosen = PROFILES.get(backdropBiome);
        return chosen != null ? chosen : PROFILES.get(BACKDROP_BIOME);
    }

    /**
     * The slider colours, as a boss script's view of them.
     *
     * <p>Read through on every frame rather than captured, so moving a slider
     * recolours the boss without rebuilding it. Slots past the ones this theme
     * declares come back null, which is how a boss with no enrage colour tells
     * the effect it has none.
     */
    static BossThemes.Colors sliderColors(BossThemes.Theme theme) {
        int slots = theme.slots().size();
        return slot -> {
            if (slot >= slots) return null;
            return switch (slot) {
                case 0 -> fromHsv(baseHsv);
                case 1 -> fromHsv(accentHsv);
                default -> fromHsv(extraHsv);
            };
        };
    }

    /**
     * The portal field for the selected dimension, at the current slider
     * colours.
     *
     * <p>Rebuilt every frame rather than cached, which is exactly what
     * {@code PortalChargeEffect} documents as the wrong thing to do in game.
     * The difference is what the rebuild is for: there it would be pure
     * allocation churn on the render thread for a palette that has not changed,
     * here the palette changes the instant anyone touches a slider, and a
     * rebuild is the only way the board reflects it. {@code CoreEmitterPattern}
     * is a pure function of its settings and elapsed time, so there is no state
     * to lose. One small object per frame at 30Hz in a dev tool is not a cost
     * worth writing a cache invalidation bug over.
     *
     * <p>Colour resolution goes through the mod's own {@code resolvedRamp}, so a
     * profile with an authored {@code gradient} shows that gradient and the
     * sliders do nothing — which is not a bug in this tool, it is what the mod
     * does with that profile, and seeing it is the point. The status line says
     * so rather than leaving anyone dragging a dead slider.
     */
    static Pattern portalPattern(RGBColor base, RGBColor accent) {
        try {
            Object profile = buildDimensionProfile(current.raw());
            Class<?> pType = profile.getClass();
            RGBColor resolvedBase = (RGBColor) pType
                    .getMethod("resolvedColor", RGBColor.class).invoke(profile, base);
            RGBColor resolvedAccent = accent != null ? accent : (RGBColor) pType
                    .getMethod("resolvedAccentColor", RGBColor.class).invoke(profile, resolvedBase);

            Object settings = pType.getMethod("toSettings").invoke(profile);
            Object ramp = pType.getMethod("resolvedRamp", RGBColor.class, RGBColor.class)
                    .invoke(profile, resolvedBase, resolvedAccent);
            settings.getClass().getField("ramp").set(settings, ramp);

            Class<?> emitter = Class.forName(
                    "com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern");
            return (Pattern) emitter.getDeclaredConstructor(settings.getClass())
                    .newInstance(settings);
        } catch (Exception e) {
            System.err.println("could not build portal field for " + current.id() + ": " + e);
            return null;
        }
    }

    /** What the status line says about the selected entry. */
    static String describe(Prof p) {
        if (p.isBoss()) {
            String where = p.boss().section() == null
                    ? "colours from boss_profiles.json"
                    : Files.exists(CONFIG_TOML)
                    ? "colours from [" + p.boss().section() + "] in the live config"
                    : "colours are the shipped defaults - no config at " + CONFIG_TOML.getFileName();
            if (EffectThemes.isOverlay(p.boss())) {
                Prof biome = backdropProfile();
                where += overlaysOverBlack || biome == null ? ", drawn over black"
                        : ", drawn over " + biome.id() + " (select a biome to change it)";
            }
            return staged(p.boss()).describer().describe(bossProgress) + "  -  " + where;
        }
        if (!p.isPortal()) {
            return p.pattern() + (p.preset() != null ? " / " + p.preset() : "");
        }
        String mode = p.raw().get("emissionMode") instanceof String s ? s : "spiral";
        return "portal field / " + mode + (hasAuthoredGradient(p)
                ? " - gradient authored, colour sliders do not apply"
                : " - ramp derived from base and accent");
    }

    /** True when this portal profile authors its own ramp, and so ignores the colour sliders. */
    static boolean hasAuthoredGradient(Prof p) {
        return p.isPortal() && p.raw().get("gradient") instanceof List<?> l && !l.isEmpty();
    }

    static void renderFrame() {
        if (wordmarkMode) {
            frame = wordmarkFrame();
            // A still image, not a lighting theme: the keyboard is held dark
            // rather than left frozen on whichever biome was showing before.
            if (hardware != null && hardware.ok()) hardware.push(darkFrame());
            return;
        }
        if (paintMode) {
            frame = designFrame(WORDMARK_UNLIT);
            // Unlike the wordmark, the design DOES go to the keyboard. Seeing
            // your sketch on real LEDs is half the reason to sketch it here.
            if (hardware != null && hardware.ok()) hardware.push(designFrame(RGBColor.BLACK));
            return;
        }
        RGBColor base = fromHsv(baseHsv);
        // Null when the profile has no accent, so resolvedAccentColor() derives
        // one the same way the mod would. Passing our slider value regardless
        // would make accent-less profiles behave differently here than in game.
        RGBColor accent = hasAccent ? fromHsv(accentHsv) : null;

        Pattern active = current != null && current.isPortal()
                ? portalPattern(base, accent) : pattern;
        if (active == null) return;

        Map<KeyGrid.LedRef, RGBColor> f = frameAt(active, base, accent,
                System.currentTimeMillis() - clockStart);
        frame = f;
        if (hardware != null && hardware.ok()) hardware.push(f);
    }

    /**
     * One board frame from a pattern at a given point in its life.
     *
     * <p>Split out of {@link #renderFrame} so the GIF export can drive the same
     * code off its own clock rather than the wall clock.
     *
     * <p><b>Call this with a non-decreasing elapsed time on a given pattern
     * instance.</b> The particle engines are not pure functions of it — they
     * respawn in place off {@code elapsedMillis} and mutate their own list — so
     * sampling one backwards, or scrubbing about in it, leaves particles that
     * spawned in a future the render is no longer in. The live view only ever
     * goes forward; the export is written to go forward too, and the loop
     * search works off a recording rather than by seeking.
     */
    static Map<KeyGrid.LedRef, RGBColor> frameAt(Pattern p, RGBColor base, RGBColor accent,
                                                 long elapsedMillis) {
        PatternContext ctx = new PatternContext(GRID, null, base, accent, 0, PatternParams.EMPTY);
        Map<KeyGrid.LedRef, LayerPixel> px = p.render(ctx, elapsedMillis);
        Map<KeyGrid.LedRef, RGBColor> f = new HashMap<>();
        for (KeyGrid.LedPosition pos : GRID.allKeys()) {
            LayerPixel lp = px.get(pos.ref());
            // Composited over black, which is what a Tier 1 base does — so what
            // is on screen and on the board is what the compositor would emit.
            f.put(pos.ref(), lp == null ? RGBColor.BLACK : lp.over(RGBColor.BLACK));
        }
        return f;
    }

    // ---------------------------------------------------------------
    // The log
    // ---------------------------------------------------------------

    static void logTweak() {
        if (current == null) return;
        RGBColor base = fromHsv(baseHsv);
        RGBColor accent = fromHsv(accentHsv);
        RGBColor origBase = RGBColor.fromHexOrDefault(current.color(), RGBColor.WHITE);
        RGBColor origAccent = current.accent() != null
                ? RGBColor.fromHexOrDefault(current.accent(), origBase) : null;

        StringBuilder b = new StringBuilder();
        b.append("\n## ").append(current.id())
                .append("  _(").append(kindForLog());
        if (current.preset() != null) b.append(" / ").append(current.preset());
        b.append(")_  \n");
        b.append("_").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("_\n\n");
        // Boss slots are named after what they colour, so the log says "bone"
        // and "powered" rather than "base" and "accent" — which for a
        // three-colour layer is the difference between a legible record and
        // three lines nobody can match up to a config key later.
        b.append(line(slotName(0), origBase, base));
        if (origAccent != null) b.append(line(slotName(1), origAccent, accent));
        if (current.extra() != null) {
            b.append(line(slotName(2),
                    RGBColor.fromHexOrDefault(current.extra(), origBase), fromHsv(extraHsv)));
        }
        String note = noteField.getText().trim();
        if (!note.isEmpty()) b.append("\n> ").append(note).append("\n");
        b.append("\n```").append(current.isBoss() && current.boss().section() != null ? "toml" : "json")
                .append("\n").append(jsonLine()).append("\n```\n");

        try {
            Files.createDirectories(LOG.getParent());
            if (!Files.exists(LOG)) {
                Files.writeString(LOG, "# Colour tuning log\n\n"
                        + "Hand-tuned colours, recorded on real hardware. `white` is the fraction of\n"
                        + "each colour emitted as white light — the number that decides whether a\n"
                        + "bright colour reads as vivid or as pale. Lower is more vivid.\n",
                        StandardCharsets.UTF_8);
            }
            Files.writeString(LOG, b.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            status.setText("logged " + current.id() + " to " + LOG);
        } catch (IOException e) {
            status.setText("could not write log: " + e.getMessage());
        }
    }

    /**
     * What to call a colour slot: the boss layer's own word for it where there
     * is one, and the generic name otherwise.
     */
    static String slotName(int index) {
        if (current != null && current.isBoss() && index < current.boss().slots().size()) {
            return current.boss().slots().get(index).label();
        }
        return switch (index) {
            case 0 -> "base";
            case 1 -> "accent";
            default -> "third";
        };
    }

    /** What the log calls this entry's kind, where a biome would name its pattern. */
    static String kindForLog() {
        if (current.isBoss()) {
            return current.boss().section() == null ? "boss bar pulse"
                    : (current.isEffect() ? "effect layer / " : "boss layer / ") + current.boss().id();
        }
        if (current.isPortal()) return "portal field";
        return current.pattern();
    }

    static String line(String slot, RGBColor from, RGBColor to) {
        double[] a = toHsv(from), c = toHsv(to);
        return String.format(
                "- **%-6s** `%s` -> `%s`   H %.0f->%.0f   S %.2f->%.2f   V %.2f->%.2f   white %.2f->%.2f\n",
                slot, from.toHex(), to.toHex(), a[0], c[0], a[1], c[1], a[2], c[2],
                whiteFraction(from), whiteFraction(to));
    }

    /**
     * The selected entry as text to paste back into whichever file it came
     * from: a JSON line or block, or for the dedicated boss layers a TOML
     * table, since that is where those colours live.
     */
    static String jsonLine() {
        if (current.isPortal()) return profileBlock(PORTAL_PREFIX, "accentColor");
        if (current.isBoss()) {
            return current.boss().section() == null
                    ? profileBlock(BOSS_PREFIX, "enrageColor")
                    : tomlBlock();
        }
        StringBuilder b = new StringBuilder();
        b.append("  \"").append(current.id()).append("\": { \"color\": \"")
                .append(fromHsv(baseHsv).toHex()).append("\"");
        if (hasAccent) b.append(", \"accentColor\": \"").append(fromHsv(accentHsv).toHex()).append("\"");
        b.append(", \"pattern\": \"").append(current.pattern()).append("\"");
        if (current.preset() != null) b.append(", \"preset\": \"").append(current.preset()).append("\"");
        b.append(" },");
        return b.toString();
    }

    /**
     * The selected entry as a JSON block for its own profile file, with the
     * tuned colours and every other authored field carried through untouched.
     *
     * <p>Emitting only the colours would be the easy version and a trap.
     * Pasting that over an existing portal entry silently deletes its geometry
     * and the portal falls back to the fitted reference defaults without saying
     * so; pasting it over a boss entry deletes that boss's
     * {@code bossBarNamePattern}, which for a mod with no clean entity hook is
     * the only reason it was detected at all. Round-tripping everything means
     * paste is always safe.
     *
     * @param accentKey what the second colour is called in this file —
     *                  {@code accentColor} for a portal, {@code enrageColor}
     *                  for a boss. The two files disagree, and writing a
     *                  portal's key into a boss profile would produce a block
     *                  that parses cleanly and does nothing.
     */
    static String profileBlock(String prefix, String accentKey) {
        String id = current.id().substring(prefix.length());
        StringBuilder b = new StringBuilder();
        b.append("  \"").append(id).append("\": {\n");
        b.append("    \"color\": \"").append(fromHsv(baseHsv).toHex()).append("\"");
        if (hasAccent) {
            b.append(",\n    \"").append(accentKey).append("\": \"")
                    .append(fromHsv(accentHsv).toHex()).append("\"");
        }
        for (Map.Entry<String, Object> e : current.raw().entrySet()) {
            if (e.getKey().equals("color") || e.getKey().equals(accentKey)) continue;
            b.append(",\n    \"").append(e.getKey()).append("\": ").append(jsonValue(e.getValue()));
        }
        b.append("\n  },");
        return b.toString();
    }

    /**
     * The selected dedicated boss layer as a TOML table, ready to paste into
     * the mod's config.
     *
     * <p>Only the colours, and only the ones this theme has — unlike the JSON
     * blocks there is nothing else in the table to lose, because the rest of a
     * boss section is geometry and timing this tool never touched. Pasting it
     * over the existing table would drop those, so the comment says what to do
     * with it: the keys are named, and there are at most three.
     */
    static String tomlBlock() {
        BossThemes.Theme theme = current.boss();
        double[][] slots = {baseHsv, accentHsv, extraHsv};
        StringBuilder b = new StringBuilder();
        b.append("# ").append(theme.slots().size() == 1 ? "key" : "keys")
                .append(" for the [").append(theme.section()).append("] table\n");
        b.append("[").append(theme.section()).append("]\n");
        for (int i = 0; i < theme.slots().size(); i++) {
            b.append("    ").append(theme.slots().get(i).configKey())
                    .append(" = \"").append(fromHsv(slots[i]).toHex()).append("\"\n");
        }
        return b.toString();
    }

    /** Enough JSON writing for the value shapes a dimension profile actually holds. */
    static String jsonValue(Object v) {
        if (v instanceof String s) return "\"" + s + "\"";
        if (v instanceof Boolean bo) return bo.toString();
        if (v instanceof Number n) {
            double d = n.doubleValue();
            // Whole numbers come back out whole. An arm count of 1.0 is valid
            // JSON and parses fine, but it reads as a typo in a hand-edited file.
            return d == Math.rint(d) && Math.abs(d) < 1e9
                    ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (v instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(jsonValue(l.get(i)));
            }
            return b.append("]").toString();
        }
        return "null";
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

    static final Color BOARD_BG = new Color(14, 14, 16);
    /** Blank border around the board, in key widths. */
    static final double BOARD_MARGIN_KEYS = 0.66;

    static class Board extends JPanel {
        Board() {
            setBackground(BOARD_BG);
            setPreferredSize(new Dimension(1040, 320));
            // Deaf unless the key painter is selected. Dragging paints too, so
            // a whole row is one swipe instead of fourteen clicks.
            java.awt.event.MouseAdapter brush = new java.awt.event.MouseAdapter() {
                int button;

                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    button = e.getButton();
                    stroke(e);
                }

                @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                    // Picking a colour is a click, not a smear.
                    if (button != 2 && !e.isAltDown()) stroke(e);
                }

                void stroke(java.awt.event.MouseEvent e) {
                    if (!paintMode) return;
                    if (paintAt(e.getX(), e.getY(), getWidth(), getHeight(), button, e.isAltDown())) {
                        renderFrame();
                        repaint();
                    }
                }
            };
            addMouseListener(brush);
            addMouseMotionListener(brush);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            paintBoard((Graphics2D) g0, getWidth(), getHeight(), frame, paintMode && paintLabels);
        }
    }

    /** Width and height of the drawn layout, in key units. */
    static double[] layoutExtent() {
        double maxX = 0, maxY = 0;
        for (PatternStudio.Key k : PatternStudio.LAYOUT) {
            maxX = Math.max(maxX, k.x() + k.w());
            maxY = Math.max(maxY, k.y() + k.h());
        }
        return new double[]{maxX, maxY};
    }

    /**
     * Draws the keyboard, centred in a {@code width} x {@code height} area.
     * Shared by the window and the PNG export, so an exported image is exactly
     * what the window shows.
     */
    static void paintBoard(Graphics2D g, double width, double height, Map<KeyGrid.LedRef, RGBColor> f) {
        paintBoard(g, width, height, f, false);
    }

    /**
     * As above, optionally with each key's legend drawn on it. Only the key
     * painter asks for legends: a design is a guide for someone building a
     * pattern, and "the third key from the left on row four" is not a
     * coordinate system anybody enjoys.
     */
    static void paintBoard(Graphics2D g, double width, double height, Map<KeyGrid.LedRef, RGBColor> f,
                           boolean labels) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        List<PatternStudio.Key> layout = PatternStudio.LAYOUT;
        double[] ext = layoutExtent();
        double s = Math.min(width / (ext[0] + BOARD_MARGIN_KEYS), height / (ext[1] + BOARD_MARGIN_KEYS));
        double ox = (width - ext[0] * s) / 2, oy = (height - ext[1] * s) / 2;
        // Gap, corner radius and glow are fractions of a key rather than fixed
        // pixels, so a 3200px export looks like this window scaled up instead
        // of huge keys separated by hairline gaps.
        double gap = 0.067 * s, arc = 0.156 * s, glow = 0.089 * s, glowArc = 0.267 * s;
        g.setStroke(new BasicStroke((float) Math.max(1.0, s / 45)));
        for (int i = 0; i < layout.size(); i++) {
            PatternStudio.Key k = layout.get(i);
            RGBColor c = f.getOrDefault(new KeyGrid.LedRef("{studio}", i), RGBColor.BLACK);
            double x = ox + k.x() * s, y = oy + k.y() * s;
            double w = k.w() * s - gap, h = k.h() * s - gap;
            int lum = (c.r() + c.g() + c.b()) / 3;
            if (lum > 26) {
                g.setColor(new Color(c.r(), c.g(), c.b(), Math.min(90, lum / 2)));
                g.fill(new RoundRectangle2D.Double(x - glow, y - glow, w + 2 * glow, h + 2 * glow, glowArc, glowArc));
            }
            g.setColor(new Color(c.r(), c.g(), c.b()));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
            g.setColor(new Color(255, 255, 255, 22));
            g.draw(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
            if (labels) {
                // Dark text on bright keys, light on dark, same flip point as
                // PatternStudio, so a yellow key's legend doesn't vanish.
                g.setColor(lum > 128 ? new Color(0, 0, 0, 150) : new Color(255, 255, 255, 110));
                g.setFont(new Font("SansSerif", Font.PLAIN, (int) Math.max(8, s * 0.26)));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(k.label(), (float) (x + (w - fm.stringWidth(k.label())) / 2),
                        (float) (y + h / 2 + fm.getAscent() / 2.4));
            }
        }
    }

    // ---------------------------------------------------------------
    // RGB wordmark — a still image for the banner and the mod icon
    // ---------------------------------------------------------------

    /**
     * Picker entry for the wordmark. Not a biome profile, and never written to
     * any JSON: it is here so the banner art comes out of the same renderer as
     * everything else in this window.
     */
    static final String WORDMARK = "» RGB wordmark";
    static volatile boolean wordmarkMode;
    static Map<KeyGrid.LedRef, RGBColor> wordmarkCache;
    static Map<KeyGrid.LedRef, RGBColor> darkCache;

    /** One colour per letter: the three channels the name is about. */
    static final RGBColor[] WORDMARK_COLORS = {
            RGBColor.fromHex("#FF2A2A"), RGBColor.fromHex("#2AFF4A"), RGBColor.fromHex("#3D6DFF")};
    /**
     * Keys outside the letters. Dim rather than black, so the board still reads
     * as a keyboard when the transparent export sits on a light background —
     * but kept just under {@link #paintBoard}'s glow threshold, or every unlit
     * key picks up a grey halo that shows on anything lighter than the board.
     */
    static final RGBColor WORDMARK_UNLIT = RGBColor.fromHex("#18191E");

    static final int WORDMARK_EXPORT_WIDTH = 3200;

    /** Top edge of each row of the drawn layout, in key units. */
    static final double NUM = 1.5, QROW = 2.5, AROW = 3.5, ZROW = 4.5, BOTTOM = 5.5;

    /**
     * The letters, key by key: for each row, the row's top edge followed by the
     * x centre of every key that row lights, in key units on the drawn layout.
     *
     * <p>Drawn by hand because nothing automatic survives this resolution. An
     * earlier version stretched a bold font glyph over each letter's area and
     * lit every key the glyph mostly covered. On a board that is six rows tall,
     * staggered, and made of keys in five different widths, that produced
     * shapes nobody could read — the G came out as a V — because a glyph's
     * curves are thinner than a key and land wherever the stagger puts them.
     *
     * <p>All three letters use the same five rows, number row to bottom row, so
     * they share a baseline. R sits on the left of the main block, where the
     * wide Tab, Caps, Shift and Ctrl keys make a solid stem; G on the right,
     * clear of the spacebar, which cannot be part of any letter; B on the
     * numpad, which is the one clean four-by-five grid on the board.
     */
    static final double[][][] WORDMARK_KEYS = {
            { // R: stem down the left modifiers, bowl closed by S, leg down X and Alt
                    {NUM, 0.5, 1.5, 2.5, 3.5},
                    {QROW, 0.75, 4.0},
                    {AROW, 0.875, 2.25, 3.25},
                    {ZROW, 1.125, 3.75},
                    {BOTTOM, 0.625, 3.125}},
            { // G: open top-left, stem O-L-., crossbar ' and Enter
                    {NUM, 11.5, 12.5, 14.0},
                    {QROW, 10.0},
                    {AROW, 10.25, 12.25, 13.875},
                    {ZROW, 10.75, 13.625},
                    {BOTTOM, 11.875, 13.125, 14.375}},
            { // B: numpad, with + and Enter as the two bowls
                    {NUM, 19, 20, 21},
                    {QROW, 19, 22},
                    {AROW, 19, 20, 21},
                    {ZROW, 19, 22},
                    {BOTTOM, 19.5, 21}}};

    /** Which keys spell "RGB", and in which colour. */
    static Map<KeyGrid.LedRef, RGBColor> wordmarkFrame() {
        if (wordmarkCache != null) return wordmarkCache;
        List<PatternStudio.Key> layout = PatternStudio.LAYOUT;
        Map<KeyGrid.LedRef, RGBColor> f = new HashMap<>();
        for (int k = 0; k < layout.size(); k++) f.put(new KeyGrid.LedRef("{studio}", k), WORDMARK_UNLIT);

        for (int letter = 0; letter < WORDMARK_KEYS.length; letter++) {
            for (double[] row : WORDMARK_KEYS[letter]) {
                double y = row[0] + 0.5;
                for (int j = 1; j < row.length; j++) {
                    int hit = keyAt(layout, row[j], y);
                    // Loud on purpose: a point that misses means the drawn
                    // layout changed under the letters, and a silently missing
                    // key would ship as a broken letter in the banner.
                    if (hit < 0) {
                        throw new IllegalStateException(String.format(
                                "wordmark key at x=%.3f y=%.1f matches no key on the layout", row[j], row[0]));
                    }
                    f.put(new KeyGrid.LedRef("{studio}", hit), WORDMARK_COLORS[letter]);
                }
            }
        }
        wordmarkCache = f;
        return f;
    }

    /** Index of the key whose rectangle contains the point, or -1. */
    static int keyAt(List<PatternStudio.Key> layout, double x, double y) {
        for (int k = 0; k < layout.size(); k++) {
            PatternStudio.Key key = layout.get(k);
            if (x >= key.x() && x < key.x() + key.w() && y >= key.y() && y < key.y() + key.h()) return k;
        }
        return -1;
    }

    static Map<KeyGrid.LedRef, RGBColor> darkFrame() {
        if (darkCache == null) {
            Map<KeyGrid.LedRef, RGBColor> f = new HashMap<>();
            for (KeyGrid.LedPosition p : GRID.allKeys()) f.put(p.ref(), RGBColor.BLACK);
            darkCache = f;
        }
        return darkCache;
    }

    /** The board as an image. A null background leaves it transparent. */
    static BufferedImage renderBoardImage(Map<KeyGrid.LedRef, RGBColor> f, int width, Color background) {
        return renderBoardImage(f, width, background, false);
    }

    static BufferedImage renderBoardImage(Map<KeyGrid.LedRef, RGBColor> f, int width, Color background,
                                          boolean labels) {
        double[] ext = layoutExtent();
        int height = (int) Math.round(width * (ext[1] + BOARD_MARGIN_KEYS) / (ext[0] + BOARD_MARGIN_KEYS));
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        if (background != null) {
            g.setColor(background);
            g.fillRect(0, 0, width, height);
        }
        paintBoard(g, width, height, f, labels);
        g.dispose();
        return img;
    }

    /**
     * Writes the wordmark twice: on the board's own dark background, and on a
     * transparent one for laying over other art.
     */
    static String exportWordmark() {
        try {
            Path dir = Paths.get("tuning/export");
            Files.createDirectories(dir);
            Map<KeyGrid.LedRef, RGBColor> f = wordmarkFrame();
            Path dark = dir.resolve("rgb_wordmark.png");
            Path clear = dir.resolve("rgb_wordmark_transparent.png");
            ImageIO.write(renderBoardImage(f, WORDMARK_EXPORT_WIDTH, BOARD_BG), "png", dark.toFile());
            ImageIO.write(renderBoardImage(f, WORDMARK_EXPORT_WIDTH, null), "png", clear.toFile());
            return "exported " + dark + " and " + clear.getFileName();
        } catch (IOException e) {
            return "export failed: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------
    // Paint your own: click keys, get a picture
    // ---------------------------------------------------------------

    /**
     * Picker entry for the key painter. You click keys, they turn the brush
     * colour, you export a PNG. No animation, no pattern, no profile behind
     * it. It's a colouring book with a keyboard on it.
     *
     * <p>What it's for: sketching a still design before anyone writes the
     * pattern that makes it happen. Arguing about "a sort of ring round WASD
     * but warmer" in words goes nowhere. A picture of the actual keys in the
     * actual colours is a spec, and it comes out of the same renderer as
     * everything else here, so the sketch looks like the mod rather than like
     * somebody's MS Paint.
     *
     * <p>The brush is the base colour sliders. Reusing them means the brush
     * gets the {@code white} readout for free, which matters here as much as
     * anywhere: a design drawn in pale colours will look pale on the board too.
     */
    static final String PAINT = "» key painter";
    static volatile boolean paintMode;

    /**
     * Painted keys only. A key with no entry is unpainted, which draws as
     * {@link #WORDMARK_UNLIT} on screen and as black on the hardware, because a
     * "dim grey so the board still reads as a keyboard" is a screen problem.
     * Real LEDs showing #18191E just look like the board is haunted.
     *
     * <p>Kept for the life of the window, so hopping over to a biome to steal
     * its colour and coming back does not wipe the design. Only touched on the
     * event thread (the mouse, the buttons and the repaint timer all live
     * there), so a plain map is fine.
     */
    static final Map<KeyGrid.LedRef, RGBColor> design = new HashMap<>();

    /** Draw each key's legend on it. On screen and in the export alike. */
    static volatile boolean paintLabels = true;

    /**
     * Pushes a picked colour back into the brush sliders. Set by {@link #main}
     * because the sliders are built there; null until then.
     */
    static Runnable brushSync;

    static final int DESIGN_EXPORT_WIDTH = 3200;

    static boolean stillImage() {
        return wordmarkMode || paintMode;
    }

    /** The design as the board draws it: every key present, unpainted ones dim. */
    static Map<KeyGrid.LedRef, RGBColor> designFrame(RGBColor unpainted) {
        Map<KeyGrid.LedRef, RGBColor> f = new HashMap<>();
        for (KeyGrid.LedPosition p : GRID.allKeys()) f.put(p.ref(), design.getOrDefault(p.ref(), unpainted));
        return f;
    }

    /**
     * What happens when you click a key in paint mode. Left paints it with the
     * brush, right un-paints it, middle (or alt-left, for anyone whose mouse
     * wheel click is a war crime) picks the key's colour up into the brush.
     *
     * @param px    pointer position on the board, in pixels
     * @param width board size, so the hit test runs the same maths as
     *              {@link #paintBoard} did when it drew the thing you clicked
     * @return true if a key was hit and anything changed
     */
    static boolean paintAt(double px, double py, double width, double height, int button, boolean alt) {
        List<PatternStudio.Key> layout = PatternStudio.LAYOUT;
        double[] ext = layoutExtent();
        double s = Math.min(width / (ext[0] + BOARD_MARGIN_KEYS), height / (ext[1] + BOARD_MARGIN_KEYS));
        double ox = (width - ext[0] * s) / 2, oy = (height - ext[1] * s) / 2;
        // keyAt tests the full key rectangle, gap included, so clicking in
        // the gutter between two keys hits the one on its left or above. Near
        // enough. Nobody is aiming for the gutter.
        int hit = keyAt(layout, (px - ox) / s, (py - oy) / s);
        if (hit < 0) return false;
        KeyGrid.LedRef ref = new KeyGrid.LedRef("{studio}", hit);

        if (button == 2 || (button == 1 && alt)) {
            RGBColor picked = design.get(ref);
            if (picked == null) return false;
            setHsv(baseHsv, picked);
            if (brushSync != null) brushSync.run();
            status.setText("picked " + picked.toHex() + " from " + layout.get(hit).label());
            return true;
        }
        if (button == 3) return design.remove(ref) != null;
        RGBColor brush = fromHsv(baseHsv);
        return !brush.equals(design.put(ref, brush));
    }

    /** Paints every key the brush colour. For backgrounds, mostly. */
    static void fillDesign() {
        RGBColor brush = fromHsv(baseHsv);
        for (KeyGrid.LedPosition p : GRID.allKeys()) design.put(p.ref(), brush);
    }

    /**
     * Writes the design twice, like the wordmark: on the board's dark
     * background and on a transparent one. Timestamped names, so a second
     * export does not quietly flatten the first one you liked better.
     */
    static String exportDesign() {
        if (design.isEmpty()) return "nothing painted yet. click some keys first";
        try {
            Path dir = Paths.get("tuning/export");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path dark = dir.resolve("design_" + stamp + ".png");
            Path clear = dir.resolve("design_" + stamp + "_transparent.png");
            Map<KeyGrid.LedRef, RGBColor> f = designFrame(WORDMARK_UNLIT);
            ImageIO.write(renderBoardImage(f, DESIGN_EXPORT_WIDTH, BOARD_BG, paintLabels), "png", dark.toFile());
            ImageIO.write(renderBoardImage(f, DESIGN_EXPORT_WIDTH, null, paintLabels), "png", clear.toFile());
            return "exported " + dark + " and " + clear.getFileName();
        } catch (IOException e) {
            return "export failed: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------
    // GIF export
    // ---------------------------------------------------------------

    /**
     * Frame delay in hundredths of a second. 5 is 20fps.
     *
     * <p>Held in centiseconds rather than fps because that is the only unit GIF
     * has, and the sampling has to happen at exactly the interval the file will
     * play back at or the loop seam drifts. See {@link GifWriter}. Values that
     * divide 100 are the ones worth using: 10, 5, 4 and 2.
     */
    static final int GIF_CENTIS = Integer.getInteger("tuner.gif.centis", 5);
    static final int GIF_WIDTH = Integer.getInteger("tuner.gif.width", 640);
    /** How far to look for a loop. Longer finds more loops and costs proportionally. */
    static final int GIF_MAX_SECONDS = Integer.getInteger("tuner.gif.maxSeconds", 8);
    /**
     * Shortest acceptable loop, so a nearly-still theme does not export four
     * frames.
     *
     * <p>One second rather than two because the portal field's own period is
     * 1.42s, and a two-second floor pushed every portal onto the next multiple
     * of it — a 7.1s GIF where a 1.42s one carries exactly the same animation at
     * a fifth of the size.
     */
    static final int GIF_MIN_SECONDS = Integer.getInteger("tuner.gif.minSeconds", 1);
    /**
     * Consecutive frames compared when testing a candidate loop point.
     *
     * <p>One frame is not enough. Two moments in an animation can match closely
     * by coincidence while everything is travelling in different directions, and
     * a loop cut there jumps. Comparing a short run either side tests that the
     * <i>continuation</i> matches too, which is what a seam actually needs.
     */
    static final int GIF_LOOP_PROBE = 3;
    /**
     * Mean per-channel difference, 0-255, below which a seam is called clean.
     *
     * <p>Measured rather than guessed: the periodic patterns land between 0 and
     * about 1.5, and everything driven by a particle engine sits above 6 because
     * those never return to a previous arrangement at all. Nothing observed
     * falls in between, so the exact threshold does not matter much — what
     * matters is that the number gets reported either way instead of every GIF
     * claiming to loop.
     */
    static final double GIF_SEAM_TOLERANCE = 3.0;

    /**
     * Crossfade length in frames, used only when a theme has no natural loop.
     *
     * <p>Long enough to hide a seam, short enough not to read as a dissolve.
     * Set {@code -Dtuner.gif.crossfade=false} to get the raw recording and its
     * jump instead.
     */
    static final int GIF_FADE_FRAMES = Integer.getInteger("tuner.gif.fadeFrames", 12);
    /** Length used for a theme with no natural loop, where the cut point is arbitrary. */
    static final int GIF_FALLBACK_SECONDS = Integer.getInteger("tuner.gif.fallbackSeconds", 4);
    static final boolean GIF_CROSSFADE =
            !"false".equalsIgnoreCase(System.getProperty("tuner.gif.crossfade", "true"));

    // --- the loop a boss or effect export plays --------------------------

    /**
     * How a boss or effect export moves through its progress.
     *
     * <p>{@link #BOUNCE} by default, because a boss layer held at one health
     * is a still life: the whole point of these effects is what they do as the
     * fight goes, and a GIF that does not show that is not showing the theme.
     */
    enum LoopShape {
        /**
         * Start to end and back again. A ramp's loop point is the frame where a
         * dead boss becomes a fresh one, which reads as the recording glitching
         * rather than as anything the boss did; coming back the other way is
         * not something a boss does either, but it is continuous, and it shows
         * every state twice, once arriving and once leaving.
         */
        BOUNCE("there and back"),
        /**
         * Start to end, then straight back to the start. For a stretch that
         * only makes sense forwards — a summon charging, the Warden's envelope,
         * a death — where playing it backwards would show something that
         * never happens.
         */
        REPEAT("start to end, repeating"),
        /**
         * No movement: the single moment the progress slider is on, for as long
         * as the loop length. What to use when the thing being judged is one
         * state's colour, or a clock-driven event inside it such as the Sun
         * Spirit's freeze, rather than a sequence.
         */
        HOLD("hold at the slider");

        final String label;

        LoopShape(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        static LoopShape fromProperty() {
            // The older switch, kept working: it only ever meant "hold".
            if ("false".equalsIgnoreCase(System.getProperty("tuner.gif.bossSweep"))) return HOLD;
            String v = System.getProperty("tuner.gif.loop", "bounce").trim().toLowerCase();
            return switch (v) {
                case "repeat" -> REPEAT;
                case "hold" -> HOLD;
                default -> BOUNCE;
            };
        }
    }

    static volatile LoopShape loopShape = LoopShape.fromProperty();

    /** The loop choice that plays the whole encounter, 0 to 1. */
    static final String LOOP_WHOLE = "whole encounter";
    /** The loop choice that plays {@link #customFrom} to {@link #customTo}. */
    static final String LOOP_CUSTOM = "custom range";

    /**
     * Which stretch of progress the loop plays: {@link #LOOP_WHOLE},
     * {@link #LOOP_CUSTOM}, or the name of one of the selected theme's
     * {@link BossThemes.Phase phases}.
     *
     * <p>Held as a name rather than as the numbers it resolves to, and resolved
     * per theme by {@link #loopRange}. So a phase that several themes share —
     * "fight" is on every boss with a sleeping or summoning opening — carries
     * over when another theme is selected, lands on that theme's own
     * boundaries, and a theme without it plays its whole encounter. That is
     * also what makes {@code --gifs} with {@code -Dtuner.gif.phase=fight}
     * useful across every boss at once.
     */
    static volatile String loopPhase = startingLoopPhase();

    /** The stage choice that plays the theme's whole script, which is what it did before stages existed. */
    static final String STAGE_ALL = "everything";

    /**
     * Which {@link BossThemes.Stage stage} the selected theme is pinned to, or
     * {@link #STAGE_ALL}. Kept by name for the same reason as
     * {@link #loopPhase}: "fight" picked on the Wither is still "fight" on
     * the Slider, and "fire breath" on the Naga is just the Naga, since it
     * very much does not breathe fire.
     */
    static volatile String stage = System.getProperty("tuner.stage", STAGE_ALL).trim();

    /** The theme as the board should run it: pinned to {@link #stage} if it has that one. */
    static BossThemes.Theme staged(BossThemes.Theme theme) {
        return STAGE_ALL.equalsIgnoreCase(stage) ? theme : theme.staged(stage);
    }

    /** Whether {@link #stage} actually pins this theme, as opposed to being a name it doesn't have. */
    static boolean isStaged(BossThemes.Theme theme) {
        return staged(theme) != theme;
    }
    static volatile double customFrom = clamp01(doubleProperty("tuner.gif.from", 0));
    static volatile double customTo = clamp01(doubleProperty("tuner.gif.to", 1));

    /**
     * Millis for one loop: out and back for {@link LoopShape#BOUNCE}, one pass
     * for {@link LoopShape#REPEAT}, the length of the file for
     * {@link LoopShape#HOLD}.
     *
     * <p>Six seconds by default. Settable because several of the scripts run
     * things off the clock that six seconds never reaches: the Sun Spirit
     * freezes once every twenty, the Valkyrie Queen teleports every nine, and
     * the dragon cycles five poses at 2.6 seconds each. A loop of the fight
     * that is shorter than those shows the colours and misses the events.
     */
    static volatile int loopMillis = Integer.getInteger("tuner.gif.loopMillis",
            Integer.getInteger("tuner.gif.bossCycleMillis", 6000));

    /**
     * Whether the live board plays the export's loop rather than sitting where
     * the slider is, so the loop can be judged — on hardware too — before it
     * is written.
     */
    static volatile boolean previewLoop;
    /** The progress the live board last rendered at, for the readout while it plays the loop. */
    static volatile double shownProgress = bossProgress;

    /** A stretch of progress to loop over, resolved for one theme. */
    record LoopRange(String name, double from, double to) {
        boolean whole() {
            return from <= 0 && to >= 1;
        }

        String percent() {
            return String.format("%.0f-%.0f%%", from * 100, to * 100);
        }
    }

    private static String startingLoopPhase() {
        if (System.getProperty("tuner.gif.from") != null || System.getProperty("tuner.gif.to") != null) {
            return LOOP_CUSTOM;
        }
        String phase = System.getProperty("tuner.gif.phase", "").trim();
        return phase.isEmpty() ? LOOP_WHOLE : phase;
    }

    private static double doubleProperty(String key, double fallback) {
        try {
            String v = System.getProperty(key);
            return v == null ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("ignoring -D" + key + ": not a number");
            return fallback;
        }
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * The stretch of progress the loop plays on this theme.
     *
     * <p>A named phase comes back a slider step inside its boundaries — see
     * {@link BossThemes.Phase#EDGE} for the one-frame flash of the neighbouring
     * stage that avoids. A custom range comes back exactly as set, in order
     * whichever end was set first.
     */
    static LoopRange loopRange(BossThemes.Theme theme) {
        String chosen = loopPhase;
        if (LOOP_CUSTOM.equals(chosen)) {
            return new LoopRange(LOOP_CUSTOM,
                    Math.min(customFrom, customTo), Math.max(customFrom, customTo));
        }
        // A pinned stage has no phases, so a loop over one covers the stage.
        for (BossThemes.Phase phase : staged(theme).phases()) {
            if (phase.name().equalsIgnoreCase(chosen)) {
                return new LoopRange(phase.name(), phase.first(), phase.last());
            }
        }
        return new LoopRange(LOOP_WHOLE, 0, 1);
    }

    /** Where a loop of this shape over this range is, at a given point in it. */
    static double loopProgress(LoopShape shape, LoopRange range, long elapsedMillis, int cycleMillis) {
        double t = Math.floorMod(elapsedMillis, (long) cycleMillis) / (double) cycleMillis;
        double along = switch (shape) {
            case BOUNCE -> t < 0.5 ? t * 2 : 2 - t * 2;
            case REPEAT -> t;
            case HOLD -> 0;
        };
        return range.from() + (range.to() - range.from()) * along;
    }

    /**
     * The progress the live board renders at: the loop while it is playing,
     * the slider otherwise.
     *
     * <p>Reads the loop settings on every frame, so the board follows a new
     * phase, shape or length the moment it is chosen without rebuilding the
     * controller and restarting its animation.
     */
    static double liveProgress(BossThemes.Theme theme, long elapsedMillis) {
        LoopShape shape = loopShape;
        double p = previewLoop && shape != LoopShape.HOLD
                ? loopProgress(shape, loopRange(theme), elapsedMillis, loopMillis) : bossProgress;
        shownProgress = p;
        return p;
    }

    /** The selected theme as a live pattern, driven by {@link #liveProgress}. */
    static Pattern livePattern(BossThemes.Theme theme) {
        return themePattern(theme, elapsed -> liveProgress(theme, elapsed));
    }

    /** One line on what the loop is set to, for the panel and the export report. */
    static String loopText(BossThemes.Theme theme) {
        double seconds = loopMillis / 1000.0;
        String pinned = isStaged(theme) ? "stage " + stage + ", " : "";
        if (loopShape == LoopShape.HOLD) {
            return pinned + String.format("held at %.0f%% for %.1fs", bossProgress * 100, seconds);
        }
        LoopRange range = loopRange(theme);
        String what = range.whole() && LOOP_WHOLE.equals(range.name())
                ? LOOP_WHOLE : range.name() + " " + range.percent();
        return pinned + String.format("looping %s, %s, %.1fs", what, loopShape.label, seconds);
    }

    /**
     * The frame interval to record a given theme at.
     *
     * <p>Portals get 50fps rather than the default 20. Not for smoothness — for
     * arithmetic. The pillar field's period is the measured 1420ms, and a loop
     * can only be a whole number of frames, so the interval has to divide 1420.
     * Of the intervals GIF can express, which are the multiples of 10ms, only
     * 20ms does. At 50ms the nearest exact loop is five whole periods: a 7.1
     * second file carrying the same 1.42 seconds of animation five times over.
     *
     * <p>An explicit {@code -Dtuner.gif.centis} wins, so this is a better
     * default rather than a rule.
     */
    static int centisFor(Prof p) {
        if (System.getProperty("tuner.gif.centis") != null) return GIF_CENTIS;
        return p.isPortal() ? 2 : GIF_CENTIS;
    }

    /** A loop point: how many frames it spans, and how badly the seam matches. */
    record Loop(int frames, double residual) {
        boolean seamless() {
            return residual <= GIF_SEAM_TOLERANCE;
        }
    }

    /**
     * Forces a loop on a recording that does not have one, by crossfading.
     *
     * <p>Roughly half the themes in the mod genuinely cannot loop. The particle
     * engines respawn at random positions and never return to an arrangement
     * they have held before; a plain shimmer rolls a per-key phase and its keys
     * are on frequencies with no common period. For those, the choice is a GIF
     * that visibly jumps every few seconds or one that has been interfered with,
     * and for something going in a README the jump is the worse of the two.
     *
     * <p>The standard trick, and worth spelling out because the index
     * arithmetic looks wrong at a glance. The recording runs past the loop point
     * by the fade length. Output frame {@code i} in the fade region blends the
     * recording's <i>continuation</i> {@code r[frames + i]} into its
     * <i>opening</i> {@code r[i]}, weighted from all-continuation to all-opening
     * across the fade. So the first output frame is exactly {@code r[frames]} —
     * the frame that genuinely followed the last one — and the seam becomes an
     * ordinary step between consecutive frames. The fade sits at the start of
     * the loop rather than the end, which is the part that looks backwards and
     * is the whole reason it works.
     *
     * @return the blended frames, or the original list unchanged if the
     *         recording does not run far enough past the loop point to do it
     */
    static List<Map<KeyGrid.LedRef, RGBColor>> crossfade(
            List<Map<KeyGrid.LedRef, RGBColor>> recorded, int frames, int fade) {
        int usable = Math.min(fade, recorded.size() - frames);
        if (usable < 2) return recorded.subList(0, frames);

        List<Map<KeyGrid.LedRef, RGBColor>> out = new ArrayList<>(frames);
        for (int i = 0; i < frames; i++) {
            if (i >= usable) {
                out.add(recorded.get(i));
                continue;
            }
            double w = i / (double) usable;
            Map<KeyGrid.LedRef, RGBColor> tail = recorded.get(frames + i);
            Map<KeyGrid.LedRef, RGBColor> head = recorded.get(i);
            Map<KeyGrid.LedRef, RGBColor> blended = new HashMap<>();
            for (KeyGrid.LedPosition p : GRID.allKeys()) {
                RGBColor a = tail.get(p.ref()), b = head.get(p.ref());
                if (a == null || b == null) continue;
                blended.put(p.ref(), a.lerp(b, w));
            }
            out.add(blended);
        }
        return out;
    }

    /**
     * A fresh instance of the selected theme's pattern.
     *
     * <p>Fresh, not the live one, for two reasons: the particle engines carry
     * state that has been running since the profile was selected, so exporting
     * from the live instance would start the recording mid-flight; and
     * {@code Pattern} is explicit that instances are not to be shared, which the
     * live view and an export running off the same object would be doing.
     *
     * <p>Takes the slider colours rather than re-reading the profile, so what is
     * exported is what is on the board — including tweaks not yet saved.
     */
    static Pattern freshPattern(RGBColor base, RGBColor accent, int cycleMillis) {
        if (current.isBoss()) {
            BossThemes.Theme theme = current.boss();
            if (loopShape == LoopShape.HOLD) {
                double held = bossProgress;
                return themePattern(theme, elapsed -> held);
            }
            // The loop settings are read once, here, rather than live as the
            // board reads them: a phase changed on the panel while a GIF is
            // rendering on its worker thread would otherwise switch stretches
            // halfway through the file.
            LoopShape shape = loopShape;
            LoopRange range = loopRange(theme);
            return themePattern(theme, elapsed -> loopProgress(shape, range, elapsed, cycleMillis));
        }
        return current.isPortal() ? portalPattern(base, accent)
                : PatternFactory.fromNameAndPreset(current.pattern(), current.preset());
    }

    /**
     * Records the selected theme forward from its first frame.
     *
     * <p>A recording rather than a series of seeks, because the particle engines
     * mutate as they are rendered — see {@link #frameAt}. It also means the loop
     * search below costs one pass rather than one pass per candidate.
     *
     * @param cycleMillis the length of one boss or effect loop, already a whole
     *                    number of frames; ignored for anything else
     */
    static List<Map<KeyGrid.LedRef, RGBColor>> sweep(int frames, int frameMillis, int cycleMillis) {
        RGBColor base = fromHsv(baseHsv);
        RGBColor accent = hasAccent ? fromHsv(accentHsv) : null;
        Pattern p = freshPattern(base, accent, cycleMillis);
        List<Map<KeyGrid.LedRef, RGBColor>> out = new ArrayList<>(frames);
        if (p == null) return out;
        for (int i = 0; i < frames; i++) out.add(frameAt(p, base, accent, (long) i * frameMillis));
        return out;
    }

    /**
     * The best place to cut a recording so it loops.
     *
     * <p>Only whole frames are candidates, which is not a compromise: the file
     * can only hold whole frames, so a loop of 3.47 seconds is not available
     * however periodic the underlying animation is. Searching frame counts
     * directly finds the best loop that GIF can actually represent.
     */
    static Loop findLoop(List<Map<KeyGrid.LedRef, RGBColor>> sweep, int minFrames) {
        int last = sweep.size() - GIF_LOOP_PROBE;
        if (last < minFrames) return new Loop(Math.max(1, sweep.size()), Double.MAX_VALUE);

        double[] err = new double[last + 1];
        double bestErr = Double.MAX_VALUE;
        for (int n = minFrames; n <= last; n++) {
            err[n] = seamAt(sweep, n);
            bestErr = Math.min(bestErr, err[n]);
        }

        // The shortest loop that is as good as the best one, rather than the
        // best one outright. A periodic animation matches at every multiple of
        // its period and the deepest minimum is often the third or fourth of
        // them, so taking the best literally exports four copies of the same
        // loop — four times the frames, four times the file, nothing else
        // different. The margin is absolute rather than proportional so that a
        // perfect zero at one multiple does not disqualify a 0.2 at an earlier
        // one, which is a difference no eye will ever find.
        double acceptable = bestErr + 0.5;
        for (int n = minFrames; n <= last; n++) {
            if (err[n] <= acceptable) return new Loop(Math.max(1, n), err[n]);
        }
        return new Loop(Math.max(1, last), bestErr);
    }

    /**
     * How badly a recording would jump if it were cut to {@code n} frames.
     *
     * <p>Averaged over a short run rather than measured on the single frame
     * pair, for the reason {@link #GIF_LOOP_PROBE} exists: two moments can
     * match by coincidence while everything in them is travelling in different
     * directions.
     */
    static double seamAt(List<Map<KeyGrid.LedRef, RGBColor>> recorded, int n) {
        double e = 0;
        for (int k = 0; k < GIF_LOOP_PROBE; k++) {
            e += difference(recorded.get(k), recorded.get(n + k));
        }
        return e / GIF_LOOP_PROBE;
    }

    /** Mean per-channel difference between two board frames, in 0-255 units. */
    static double difference(Map<KeyGrid.LedRef, RGBColor> a, Map<KeyGrid.LedRef, RGBColor> b) {
        long sum = 0;
        int n = 0;
        for (KeyGrid.LedPosition p : GRID.allKeys()) {
            RGBColor x = a.get(p.ref()), y = b.get(p.ref());
            if (x == null || y == null) continue;
            sum += Math.abs(x.r() - y.r()) + Math.abs(x.g() - y.g()) + Math.abs(x.b() - y.b());
            n += 3;
        }
        return n == 0 ? 0 : sum / (double) n;
    }

    /**
     * {@code minecraft:sunflower_plains} to {@code minecraft_sunflower_plains},
     * with a suffix naming the loop where there is one.
     */
    static String gifFileName(String id, String suffix) {
        String name = id.startsWith(PORTAL_PREFIX)
                ? "portal_" + id.substring(PORTAL_PREFIX.length()) : id;
        return (name + " " + suffix).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "") + ".gif";
    }

    /**
     * What a boss or effect GIF's file name adds for the loop it plays.
     *
     * <p>Empty for the default, the whole encounter there and back, so a plain
     * {@code --gifs} run writes the same names it always has. Anything else is
     * named, because the reason to loop one phase is usually to have several of
     * them side by side, and an earlier version wrote every one of them over
     * the same file.
     */
    static String loopSuffix(BossThemes.Theme theme) {
        // The stage goes first, so boss_ender_dragon_fire_breath_at_50.gif
        // sits next to its siblings in a file listing instead of scattering.
        String pinned = isStaged(theme) ? stage + " " : "";
        if (loopShape == LoopShape.HOLD) {
            return pinned + String.format("at %.0f", bossProgress * 100);
        }
        LoopRange range = loopRange(theme);
        String part = LOOP_CUSTOM.equals(range.name())
                ? String.format("%.0f to %.0f", range.from() * 100, range.to() * 100)
                : LOOP_WHOLE.equals(range.name()) ? "" : range.name();
        return pinned + (loopShape == LoopShape.REPEAT ? part + " repeating" : part);
    }

    /**
     * Writes the selected theme as a looping GIF.
     *
     * @return a one-line report, including the seam residual — which is the
     *         number worth reading, since a GIF that does not loop cleanly looks
     *         identical to one that does until the moment it wraps
     */
    static String exportGif() {
        if (current == null) return "nothing selected";
        if (stillImage()) return "this is a still image; use export PNG";
        try {
            Path dir = Paths.get("tuning/export");
            Files.createDirectories(dir);

            int centis = centisFor(current);
            int frameMillis = centis * 10;
            int minFrames = Math.max(1, GIF_MIN_SECONDS * 1000 / frameMillis);

            // The loop length, snapped to whole frames. The progress script
            // is timed off the snapped length too, or a 6.03s loop recorded as
            // 6.00s of frames would restart its script three hundredths early
            // and leave a seam in the one place a known period should not have
            // one.
            boolean boss = current.isBoss();
            int loopFrames = Math.max(1, loopMillis / frameMillis);
            int cycleMillis = loopFrames * frameMillis;

            // A looping boss or effect has a period that is known rather than
            // searched for: the loop itself. A held one is searched, but only
            // up to the loop length, which is the most the file was asked to
            // be. Everything else is recorded for as long as the search covers.
            int period = boss && loopShape != LoopShape.HOLD ? loopFrames : 0;
            int maxFrames = Math.max(2, boss
                    ? loopFrames + GIF_FADE_FRAMES + GIF_LOOP_PROBE
                    : GIF_MAX_SECONDS * 1000 / frameMillis);

            List<Map<KeyGrid.LedRef, RGBColor>> recorded = sweep(maxFrames, frameMillis, cycleMillis);
            if (recorded.isEmpty()) return "nothing rendered for " + current.id();
            // Measured at the known period rather than searched for, when there
            // is one. Searching a bouncing loop finds half-cycle cuts: it runs
            // out and back, so the frame at half a cycle holds the same colours
            // as the frame at zero and scores as a clean seam while being the
            // far end of the loop. The script repeats on its cycle and nowhere
            // else, so that is where to cut.
            Loop loop = period > 0
                    ? new Loop(period, seamAt(recorded, period))
                    : findLoop(boss
                    ? recorded.subList(0, Math.min(recorded.size(), loopFrames + GIF_LOOP_PROBE))
                    : recorded, Math.min(minFrames, loopFrames));

            List<Map<KeyGrid.LedRef, RGBColor>> cut;
            String how;
            if (loop.seamless()) {
                cut = recorded.subList(0, loop.frames());
                how = String.format("seam %.2f", loop.residual());
            } else {
                // No natural loop, so the cut point carries no meaning — the
                // residual curve for these is noise, and its lowest point is
                // wherever the noise happened to dip. Taking a fixed length
                // instead keeps the files a predictable size rather than
                // anywhere between one and eight seconds by luck.
                //
                // Except for a boss or effect, which cuts on its loop length
                // even when the seam is rough: the length there is not
                // arbitrary, and a shorter file would stop partway through the
                // loop, or before the clock-driven event it was lengthened to
                // catch. The crossfade then blends two frames that are at the
                // same point in the loop and differ only in the theme's own
                // motion, which is about the easiest seam it will ever be given.
                int fallback = boss ? loopFrames
                        : Math.min(GIF_FALLBACK_SECONDS * 1000 / frameMillis,
                        recorded.size() - GIF_FADE_FRAMES);
                fallback = boss ? fallback : Math.max(minFrames, fallback);
                if (GIF_CROSSFADE) {
                    cut = crossfade(recorded, fallback, GIF_FADE_FRAMES);
                    how = String.format("no loop (%.1f), crossfaded %.1fs", loop.residual(),
                            Math.min(GIF_FADE_FRAMES, recorded.size() - fallback)
                                    * frameMillis / 1000.0);
                } else {
                    cut = recorded.subList(0, fallback);
                    how = String.format("no loop (%.1f), JUMPS", loop.residual());
                }
            }

            List<BufferedImage> images = new ArrayList<>(cut.size());
            for (Map<KeyGrid.LedRef, RGBColor> f : cut) {
                images.add(renderBoardImage(f, GIF_WIDTH, BOARD_BG));
            }

            Path out = dir.resolve(gifFileName(current.id(), boss ? loopSuffix(current.boss()) : ""));
            GifWriter.write(out, images, centis, GifWriter.LOOP_FOREVER);

            double seconds = cut.size() * frameMillis / 1000.0;
            // Which stretch of a boss fight is in the file is not something you
            // can tell by looking at it afterwards, so the report says.
            String state = boss ? ", " + loopText(current.boss()) : "";
            return String.format("%-40s %.2fs, %3d frames @ %dfps, %s%s",
                    out.getFileName(), seconds, cut.size(), 100 / centis, how, state);
        } catch (Exception e) {
            return "GIF export failed: " + e;
        }
    }

    /**
     * Every theme to {@code tuning/export/}, for media use.
     *
     * <p>Headless, because it is minutes of work and a frozen window for the
     * duration would look like a hang. Takes an optional substring filter, which
     * is mostly for iterating on one theme without re-exporting eighty.
     */
    static void exportAllGifs(String filter) {
        System.out.println("frame " + GIF_CENTIS + " centis (" + (100 / GIF_CENTIS)
                + " fps), width " + GIF_WIDTH + ", searching up to " + GIF_MAX_SECONDS + "s");
        System.out.println("override with -Dtuner.gif.centis / .width / .maxSeconds / .minSeconds");
        System.out.println("boss and effect themes: " + (loopShape == LoopShape.HOLD
                ? String.format("held at %.0f%%", bossProgress * 100)
                : "phase " + loopPhase + ", " + loopShape.label)
                + String.format(", %.1fs", loopMillis / 1000.0)
                + (STAGE_ALL.equalsIgnoreCase(stage) ? "" : ", stage " + stage + " where there is one"));
        System.out.println("override with -Dtuner.gif.loop=bounce|repeat|hold / .phase / .from / .to"
                + " / .loopMillis, -Dtuner.progress for hold, and -Dtuner.stage to pin a stage");
        System.out.println();

        int done = 0, rough = 0;
        List<String> notSeamless = new ArrayList<>();
        for (String id : PROFILES.keySet()) {
            if (filter != null && !id.contains(filter)) continue;
            selectProfile(id);
            String report = exportGif();
            System.out.println("  " + report);
            done++;
            if (report.contains("crossfaded") || report.contains("JUMPS")) {
                rough++;
                notSeamless.add(id);
            }
        }
        System.out.println();
        System.out.println("exported : " + done + " gif(s) to tuning/export/");
        System.out.println("natural  : " + (done - rough) + " loop on their own period");
        if (rough > 0) {
            // Not a failure, and worth naming rather than burying. These
            // animations have no period to find: the particle engines respawn at
            // random and never return to an arrangement they have held, and a
            // plain shimmer puts every key on its own frequency. They loop
            // because they were crossfaded into looping, which is a different
            // claim from the ones above and should read as one.
            System.out.println("faded    : " + rough + " have no natural loop and were crossfaded:");
            for (String id : notSeamless) System.out.println("             " + id);
        }
    }

    /** What the category picker above the profile list offers. */
    static final String[] CATEGORIES = {"all", "biomes", "portals", "bosses", "effects", "tools"};

    /**
     * The profiles in one category, then the painter (and, with exports on,
     * the wordmark) last so neither is ever the default.
     */
    static String[] pickerItems(String category) {
        String cat = category == null ? "all" : category;
        List<String> items = new ArrayList<>();
        for (String id : PROFILES.keySet()) {
            String kind = id.startsWith(PORTAL_PREFIX) ? "portals"
                    : id.startsWith(BOSS_PREFIX) ? "bosses"
                    : id.startsWith(EFFECT_PREFIX) ? "effects"
                    : "biomes";
            if (cat.equals("all") || cat.equals(kind)) items.add(id);
        }
        if (cat.equals("all") || cat.equals("tools")) {
            items.add(PAINT);
            // The wordmark exists to be exported. Without exports it is a
            // fixed picture with nothing to do, so it is not offered.
            if (EXPORTS) items.add(WORDMARK);
        }
        return items.toArray(new String[0]);
    }

    /** One HSV slider row, wired to a slot of one of the colour arrays. */
    static JPanel hsvSlider(String name, double[] target, int index, double max, JLabel readout) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(430, 26));
        JLabel l = new JLabel(name);
        l.setPreferredSize(new Dimension(34, 20));
        l.setForeground(new Color(200, 200, 208));
        l.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JSlider sl = new JSlider(0, 1000, (int) (target[index] / max * 1000));
        sl.setOpaque(false);
        sl.addChangeListener(e -> {
            target[index] = sl.getValue() / 1000.0 * max;
            readout.setText(readoutText(target));
            repaintSwatches();
        });
        // Re-sync when the profile changes underneath us.
        sl.putClientProperty("sync", (Runnable) () ->
                sl.setValue((int) (target[index] / max * 1000)));
        p.add(l, BorderLayout.WEST);
        p.add(sl, BorderLayout.CENTER);
        return p;
    }

    /**
     * The fight-progress slider.
     *
     * <p>Its own helper rather than a fourth {@link #hsvSlider}, because it
     * writes one field instead of a slot of an array and its readout is the
     * boss layer's own description of where the encounter is, not a colour.
     * Updating the status line as it moves is the point of it: the whole reason
     * this control exists is to see which state you are in.
     */
    static JPanel progressSlider(JLabel readout) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(430, 26));
        // No inline name: the heading above the row says what it is and the
        // readout below says where it is, and the 34px the colour rows give to
        // "hue" does not fit a word for this one. Kept as an empty spacer so
        // the slider still lines up with the three above it.
        JLabel l = new JLabel("");
        l.setPreferredSize(new Dimension(34, 20));
        JSlider sl = new JSlider(0, 1000, (int) (bossProgress * 1000));
        sl.setOpaque(false);
        sl.addChangeListener(e -> {
            bossProgress = sl.getValue() / 1000.0;
            readout.setText(progressText());
            if (status != null && current != null && current.isBoss()) {
                status.setText(describe(current));
            }
        });
        sl.putClientProperty("sync", (Runnable) () -> sl.setValue((int) (bossProgress * 1000)));
        p.add(l, BorderLayout.WEST);
        p.add(sl, BorderLayout.CENTER);
        return p;
    }

    static String progressText() {
        return String.format("%3.0f%% through the encounter", bossProgress * 100);
    }

    static String readoutText(double[] hsv) {
        RGBColor c = fromHsv(hsv);
        return String.format("%s   H %3.0f  S %.2f  V %.2f   white %.2f",
                c.toHex(), hsv[0], hsv[1], hsv[2], whiteFraction(c));
    }

    static void repaintSwatches() {
        for (JComponent c : swatches) c.repaint();
    }

    static JComponent swatch(double[] hsv) {
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                RGBColor col = fromHsv(hsv);
                g.setColor(new Color(col.r(), col.g(), col.b()));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
            }
        };
        c.setPreferredSize(new Dimension(ROW_WIDTH, 20));
        c.setMaximumSize(new Dimension(ROW_WIDTH, 20));
        swatches.add(c);
        return c;
    }

    public static void main(String[] args) {
        loadProfiles();
        // Said up front, because the symptom is an entry that simply isn't in
        // the list, and nobody goes looking for an absence.
        for (String problem : GameVersion.problems()) System.err.println("WARNING " + problem);
        // --check: a headless sanity pass. Proves the paths resolve, the JSON
        // parses and every profile can actually build and render its pattern,
        // without opening a window. Run it first when something looks wrong;
        // a bad config path is otherwise indistinguishable from an empty list.
        if (args.length > 0 && args[0].equals("--check")) {
            selfCheck();
            return;
        }
        if (!EXPORTS && args.length > 0 && (args[0].equals("--gifs") || args[0].equals("--wordmark"))) {
            System.err.println("Unknown option: " + args[0]);
            return;
        }
        if (args.length > 0 && args[0].equals("--gifs")) {
            // Optional substring filter, for re-exporting one theme rather than
            // all of them while tuning it.
            exportAllGifs(args.length > 1 ? args[1] : null);
            return;
        }
        if (args.length > 0 && args[0].equals("--probe")) {
            // Read-only: connects, builds the mapping, prints it. Never pushes
            // a frame, so it cannot disturb whatever the board is showing.
            HardwareBridge hb = new HardwareBridge();
            System.out.println(hb.connect());
            if (hb.ok()) {
                System.out.println(hb.prepare(GRID));
                System.out.println(hb.mappingReport(GRID));
            }
            hb.close();
            return;
        }
        if (args.length > 0 && args[0].equals("--wordmark")) {
            System.out.println(exportWordmark());
            return;
        }
        if (PROFILES.isEmpty()) {
            System.err.println("No profiles found.\n  defaults: " + DEFAULTS.toAbsolutePath()
                    + "\n  live:     " + LIVE.toAbsolutePath()
                    + "\nRun from the repo root, or pass -Dtuner.config=<path>.");
            return;
        }

        installLook();
        JFrame f = new JFrame("RGB Profiles — Colour Tuner");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // The sidebar is three stacked parts. What you are looking at sits at
        // the top and the actions and status sit at the bottom, both fixed, so
        // neither scrolls away. Everything in between scrolls, and every
        // section in it hides outright when it does not apply to the selection
        // rather than sitting there greyed out: a biome never shows the boss
        // controls, a boss never shows the painter, and so on.

        // ---- top: the picker ------------------------------------------------
        JComboBox<String> category = new JComboBox<>(CATEGORIES);
        category.setToolTipText("narrow the list to one kind of profile");
        category.setPreferredSize(new Dimension(104, 28));
        JComboBox<String> picker = new JComboBox<>(pickerItems(CATEGORIES[0]));
        picker.setMaximumRowCount(24);
        JPanel pickRow = new JPanel(new BorderLayout(6, 0));
        pickRow.setOpaque(false);
        pickRow.setMaximumSize(new Dimension(ROW_WIDTH, 28));
        pickRow.add(category, BorderLayout.WEST);
        pickRow.add(picker, BorderLayout.CENTER);

        JPanel top = column();
        top.setOpaque(true);
        top.setBackground(SIDE_BG);
        top.setBorder(new EmptyBorder(12, 12, 4, 12));
        put(top, heading("Profile"));
        put(top, pickRow);

        // ---- middle: everything that depends on the selection ---------------
        SideContent content = new SideContent();

        JLabel baseRead = mono();
        JLabel accentRead = mono();
        JLabel extraRead = mono();

        // Colours. Relabelled per entry: a boss layer's colours have names of
        // their own — bone, eyes, powered — and calling the Wither's eye colour
        // "accent" leaves you matching sliders to config keys by guesswork.
        JPanel colours = section("Colours");
        JLabel baseLabel = cardTitle("base colour");
        List<JPanel> baseRows = List.of(
                hsvSlider("hue", baseHsv, 0, 360, baseRead),
                hsvSlider("sat", baseHsv, 1, 1, baseRead),
                hsvSlider("val", baseHsv, 2, 1, baseRead));
        JPanel baseCard = colourCard(baseLabel, baseHsv, baseRead, baseRows);
        put(colours, baseCard);

        // A profile without an accentColor hands the pattern a null, and
        // PatternContext derives one by lightening the base. Leaving the accent
        // sliders live in that state gives you three controls that visibly do
        // nothing, so the checkbox says whether this profile has an accent at
        // all and greys them out when it does not. Ticking it adds one.
        JCheckBox accentOn = new JCheckBox("accent colour");
        accentOn.setFont(CARD_FONT);
        accentOn.setForeground(TEXT);
        accentOn.setOpaque(false);
        accentOn.setToolTipText("untick to let the pattern derive its accent from the base colour");
        List<JPanel> accentRows = List.of(
                hsvSlider("hue", accentHsv, 0, 360, accentRead),
                hsvSlider("sat", accentHsv, 1, 1, accentRead),
                hsvSlider("val", accentHsv, 2, 1, accentRead));
        JPanel accentCard = colourCard(accentOn, accentHsv, accentRead, accentRows);
        put(colours, accentCard);

        // The third slot. Only the boss layers with three colours have one, so
        // it is built once and hidden for everything else.
        JLabel extraLabel = cardTitle("third colour");
        List<JPanel> extraRows = List.of(
                hsvSlider("hue", extraHsv, 0, 360, extraRead),
                hsvSlider("sat", extraHsv, 1, 1, extraRead),
                hsvSlider("val", extraHsv, 2, 1, extraRead));
        JPanel extraCard = colourCard(extraLabel, extraHsv, extraRead, extraRows);
        put(colours, extraCard);
        put(content, colours);

        // Encounter: where a boss or effect layer is in its script, and the
        // loop that plays a stretch of it. Biomes and portals never see this.
        JPanel encounter = section("Encounter");

        // The stage picker: one thing the boss does, on repeat, or the whole
        // script. Display strings and the stage name each stands for, index for
        // index, same arrangement as the phase picker below.
        JLabel stageLabel = cardTitle("stage");
        put(encounter, stageLabel);
        List<String> stageKeys = new ArrayList<>();
        JComboBox<String> stagePick = new JComboBox<>();
        stagePick.setToolTipText("pin the board to one thing the boss does, on repeat");
        stagePick.setMaximumSize(new Dimension(ROW_WIDTH, 28));
        put(encounter, stagePick);
        put(encounter, Box.createVerticalStrut(6));
        JLabel progressLabel = cardTitle("progress");
        put(encounter, progressLabel);

        JLabel progressRead = mono();
        JPanel progressRow = progressSlider(progressRead);
        put(encounter, progressRow);
        put(encounter, progressRead);

        JCheckBox previewOn = smallCheck("play the loop on the board");
        previewOn.setToolTipText("play the loop live, on screen and on the keyboard, instead of holding the slider");
        JCheckBox overBlack = smallCheck("draw over black");
        overBlack.setSelected(overlaysOverBlack);
        overBlack.setToolTipText("draw this overlay over black; off draws it over the last biome you selected");
        JPanel toggleRow = new JPanel(new GridLayout(1, 2, 6, 0));
        toggleRow.setOpaque(false);
        toggleRow.setMaximumSize(new Dimension(ROW_WIDTH, 24));
        toggleRow.add(previewOn);
        toggleRow.add(overBlack);
        put(encounter, toggleRow);

        put(encounter, Box.createVerticalStrut(6));
        JLabel loopLabel = cardTitle(EXPORTS ? "loop (preview and GIF)" : "loop");
        put(encounter, loopLabel);

        // Display strings in the phase picker, and the loopPhase value each
        // one stands for, index for index.
        List<String> phaseKeys = new ArrayList<>();
        JComboBox<String> phasePick = new JComboBox<>();
        phasePick.setToolTipText("the stretch of progress the loop covers");
        JComboBox<LoopShape> shapePick = new JComboBox<>(LoopShape.values());
        shapePick.setSelectedItem(loopShape);
        shapePick.setToolTipText("how the loop moves through that stretch");
        JPanel loopRow = new JPanel(new GridLayout(1, 2, 6, 0));
        loopRow.setOpaque(false);
        loopRow.setMaximumSize(new Dimension(ROW_WIDTH, 28));
        loopRow.add(phasePick);
        loopRow.add(shapePick);
        put(encounter, loopRow);

        JButton startBtn = new JButton("start here");
        startBtn.setToolTipText("start a custom loop where the progress slider is");
        JButton endBtn = new JButton("end here");
        endBtn.setToolTipText("end a custom loop where the progress slider is");
        JSpinner lengthSpin = new JSpinner(new SpinnerNumberModel(
                Math.max(0.5, Math.min(120, loopMillis / 1000.0)), 0.5, 120.0, 0.5));
        lengthSpin.setEditor(new JSpinner.NumberEditor(lengthSpin, "0.0 's'"));
        lengthSpin.setToolTipText("seconds per loop: out and back, one pass, or the whole held file");
        JPanel rangeRow = new JPanel(new GridLayout(1, 3, 6, 0));
        rangeRow.setOpaque(false);
        rangeRow.setMaximumSize(new Dimension(ROW_WIDTH, 28));
        rangeRow.add(startBtn);
        rangeRow.add(endBtn);
        rangeRow.add(lengthSpin);
        put(encounter, Box.createVerticalStrut(4));
        put(encounter, rangeRow);
        JLabel loopRead = mono();
        put(encounter, loopRead);
        put(content, encounter);

        overBlack.addActionListener(e -> {
            overlaysOverBlack = overBlack.isSelected();
            if (current != null && current.isBoss() && EffectThemes.isOverlay(current.boss())) {
                // Only the pattern is rebuilt, not the whole selection, so
                // colours tuned but not yet saved stay on the sliders.
                pattern = livePattern(current.boss());
                status.setText(describe(current));
            }
        });
        previewOn.addActionListener(e -> {
            previewLoop = previewOn.isSelected();
            if (current != null && current.isBoss()) {
                // A fresh controller on a fresh clock, so the loop starts from
                // its first frame as the export does, rather than wherever the
                // clock since selection happens to fall in the cycle.
                clockStart = System.currentTimeMillis();
                pattern = livePattern(current.boss());
            }
            if (!previewLoop) progressRead.setText(progressText());
        });

        // Rebuilds the phase picker for the selected theme and refreshes
        // everything the loop settings show. Listeners are muted across the
        // rebuild, because repopulating a combo box fires selection events
        // that would otherwise write the first item back over loopPhase.
        Runnable refreshLoop = () -> {
            boolean boss = !stillImage() && current != null && current.isBoss();
            java.awt.event.ActionListener[] muted = phasePick.getActionListeners();
            for (java.awt.event.ActionListener l : muted) phasePick.removeActionListener(l);
            phasePick.removeAllItems();
            phaseKeys.clear();
            phaseKeys.add(LOOP_WHOLE);
            phasePick.addItem(LOOP_WHOLE);
            if (boss) {
                for (BossThemes.Phase phase : staged(current.boss()).phases()) {
                    phaseKeys.add(phase.name());
                    phasePick.addItem(String.format("%s  %.0f-%.0f%%",
                            phase.name(), phase.from() * 100, phase.to() * 100));
                }
            }
            phaseKeys.add(LOOP_CUSTOM);
            phasePick.addItem(String.format("custom  %.0f-%.0f%%",
                    Math.min(customFrom, customTo) * 100, Math.max(customFrom, customTo) * 100));
            String shown = boss ? loopRange(current.boss()).name() : LOOP_WHOLE;
            int index = Math.max(0, phaseKeys.indexOf(shown));
            phasePick.setSelectedIndex(index);
            for (java.awt.event.ActionListener l : muted) phasePick.addActionListener(l);

            boolean ranged = boss && loopShape != LoopShape.HOLD;
            phasePick.setEnabled(ranged);
            shapePick.setEnabled(boss);
            startBtn.setEnabled(ranged);
            endBtn.setEnabled(ranged);
            lengthSpin.setEnabled(boss);
            previewOn.setEnabled(ranged);
            loopRead.setText(boss ? loopText(current.boss()) : " ");
            if (!(previewLoop && ranged)) progressRead.setText(progressText());
        };
        // A held loop's readout names the slider's value, so it follows the
        // slider. Only the text: rebuilding the phase picker on every tick of
        // a drag would be wasted work.
        for (Component c : progressRow.getComponents()) {
            if (c instanceof JSlider s) {
                s.addChangeListener(e -> {
                    if (!stillImage() && current != null && current.isBoss()) {
                        loopRead.setText(loopText(current.boss()));
                    }
                });
            }
        }

        phasePick.addActionListener(e -> {
            int i = phasePick.getSelectedIndex();
            if (i < 0 || i >= phaseKeys.size()) return;
            loopPhase = phaseKeys.get(i);
            refreshLoop.run();
        });
        shapePick.addActionListener(e -> {
            if (shapePick.getSelectedItem() instanceof LoopShape s) loopShape = s;
            refreshLoop.run();
        });
        lengthSpin.addChangeListener(e -> {
            loopMillis = (int) Math.round(((Number) lengthSpin.getValue()).doubleValue() * 1000);
            refreshLoop.run();
        });
        // Setting one end of a custom range from inside a named phase keeps
        // the other end where that phase had it, so narrowing a phase is one
        // click rather than two.
        java.util.function.Consumer<Boolean> setEnd = start -> {
            if (current == null || !current.isBoss()) return;
            if (!LOOP_CUSTOM.equals(loopPhase)) {
                LoopRange was = loopRange(current.boss());
                customFrom = was.from();
                customTo = was.to();
            }
            if (start) customFrom = bossProgress;
            else customTo = bossProgress;
            loopPhase = LOOP_CUSTOM;
            refreshLoop.run();
        };
        startBtn.addActionListener(e -> setEnd.accept(true));
        endBtn.addActionListener(e -> setEnd.accept(false));

        // Rebuilds the stage picker for the selected theme, listeners muted for
        // the same reason as refreshLoop's. A theme with no stages (the
        // Warden's envelope, rain) hides the row rather than offering a
        // dropdown with exactly one option in it, which is just a label with
        // delusions of grandeur.
        Runnable refreshStage = () -> {
            boolean boss = !stillImage() && current != null && current.isBoss();
            List<BossThemes.Stage> stages = boss ? current.boss().stages() : List.of();
            java.awt.event.ActionListener[] muted = stagePick.getActionListeners();
            for (java.awt.event.ActionListener l : muted) stagePick.removeActionListener(l);
            stagePick.removeAllItems();
            stageKeys.clear();
            stageKeys.add(STAGE_ALL);
            stagePick.addItem(STAGE_ALL + " (the whole script)");
            int index = 0;
            for (BossThemes.Stage s : stages) {
                if (s.name().equalsIgnoreCase(stage)) index = stageKeys.size();
                stageKeys.add(s.name());
                stagePick.addItem(s.name());
            }
            stagePick.setSelectedIndex(index);
            for (java.awt.event.ActionListener l : muted) stagePick.addActionListener(l);
            stageLabel.setVisible(!stages.isEmpty());
            stagePick.setVisible(!stages.isEmpty());
        };
        stagePick.addActionListener(e -> {
            int i = stagePick.getSelectedIndex();
            if (i < 0 || i >= stageKeys.size()) return;
            stage = stageKeys.get(i);
            if (current != null && current.isBoss()) {
                // A fresh controller on a fresh clock. Swapping the script
                // under a running one would carry over whatever it was mid-way
                // through: half a ritual, a pose easing out of the last stage.
                clockStart = System.currentTimeMillis();
                pattern = livePattern(current.boss());
                status.setText(describe(current));
            }
            // The phase picker's list depends on the stage, so it goes too.
            refreshLoop.run();
        });

        // The key painter's own controls. Only there in paint mode.
        JPanel painter = section("Key painter");
        put(painter, hint("left-click paints, right-click erases, middle or alt-click picks a key's colour"));
        JPanel paintRow = new JPanel(new GridLayout(1, 3, 6, 0));
        paintRow.setOpaque(false);
        paintRow.setMaximumSize(new Dimension(ROW_WIDTH, 28));
        JButton fillBtn = new JButton("fill all");
        fillBtn.setToolTipText("paint every key the brush colour");
        fillBtn.addActionListener(e -> {
            fillDesign();
            status.setText("filled every key with " + fromHsv(baseHsv).toHex());
        });
        JButton clearBtn = new JButton("clear");
        clearBtn.setToolTipText("un-paint every key");
        clearBtn.addActionListener(e -> {
            design.clear();
            status.setText("cleared. blank canvas, infinite possibilities, etc.");
        });
        JCheckBox labelsOn = smallCheck("key labels");
        labelsOn.setSelected(paintLabels);
        labelsOn.setToolTipText("draw each key's legend on the board");
        labelsOn.addActionListener(e -> paintLabels = labelsOn.isSelected());
        paintRow.add(fillBtn);
        paintRow.add(clearBtn);
        paintRow.add(labelsOn);
        put(painter, paintRow);
        put(content, painter);
        paintOnly.addAll(List.of(fillBtn, clearBtn, labelsOn));

        // The hardware.
        JPanel keyboard = section("Keyboard");
        JCheckBox hw = smallCheck("drive my real keyboard");
        hw.setFont(CARD_FONT);
        hw.setToolTipText("show the board on your actual keyboard while ticked");
        hw.addActionListener(e -> {
            if (hw.isSelected()) {
                try {
                    hardware = new HardwareBridge();
                    String msg = hardware.connect();
                    if (hardware.ok()) msg = hardware.prepare(GRID);
                    status.setText(msg);
                    if (!hardware.ok()) hw.setSelected(false);
                } catch (Throwable t) {
                    // Almost always a missing JNA jar; naming it beats a bare
                    // NoClassDefFoundError, which tells you nothing actionable.
                    status.setText("hardware unavailable: " + t);
                    hw.setSelected(false);
                }
            } else if (hardware != null) {
                hardware.close();
                hardware = null;
                status.setText("hardware released");
            }
        });
        put(keyboard, hw);
        put(content, keyboard);

        // Notes: what the board should look like, logged with the colours.
        JPanel notes = section("Tuning log");
        put(notes, hint("what it should look like, saved with the current colours"));
        noteField = new JTextField();
        JButton logBtn = new JButton("log tweak");
        logBtn.setToolTipText("append the note and these colours to tuning/color_tuning_log.md");
        logBtn.addActionListener(e -> logTweak());
        noteField.addActionListener(e -> logTweak());
        JPanel noteRow = new JPanel(new BorderLayout(6, 0));
        noteRow.setOpaque(false);
        noteRow.setMaximumSize(new Dimension(ROW_WIDTH, 28));
        noteRow.add(noteField, BorderLayout.CENTER);
        noteRow.add(logBtn, BorderLayout.EAST);
        put(notes, noteRow);
        put(content, notes);

        // Maintainer-only; see EXPORTS.
        // One button: a still image (the painter, the wordmark) exports as a
        // PNG and everything else as a looping GIF, so it is never showing an
        // option that does not apply.
        if (EXPORTS) {
            JPanel exports = section("Export");
            put(exports, hint("writes to tuning/export/, at the colours on the sliders"));
            exportBtn = new JButton("export");
            exportBtn.setMaximumSize(new Dimension(ROW_WIDTH, 28));
            exportBtn.addActionListener(e -> {
                if (stillImage()) {
                    status.setText(paintMode ? exportDesign() : exportWordmark());
                    return;
                }
                // On a worker, because a long theme is a second or two of
                // rendering and doing that on the event thread paints the
                // window grey.
                status.setText("rendering GIF...");
                new Thread(() -> {
                    String report = exportGif();
                    SwingUtilities.invokeLater(() -> status.setText(report));
                }, "gif-export").start();
            });
            put(exports, exportBtn);
            put(content, exports);
        }

        // ---- bottom: actions and the status line ------------------------------
        JButton copyBtn = new JButton("copy JSON");
        copyBtn.setToolTipText("copy this profile's line, at the current colours, for pasting into the config");
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(jsonLine()), null);
            status.setText("JSON copied");
        });
        JButton resetBtn = new JButton("reset");
        resetBtn.setToolTipText("throw away slider changes and go back to the profile's colours");
        JButton reloadBtn = new JButton("reload");
        reloadBtn.setToolTipText("re-read the profile files from disk");
        JPanel buttons = new JPanel(new GridLayout(1, 3, 6, 0));
        buttons.setOpaque(false);
        buttons.setMaximumSize(new Dimension(ROW_WIDTH, 30));
        buttons.add(copyBtn);
        buttons.add(resetBtn);
        buttons.add(reloadBtn);
        tuningOnly.addAll(List.of(logBtn, copyBtn, resetBtn, noteField));

        // Three lines at a fixed size, wrapping, so a long description is read
        // in full rather than cut off at the panel edge, and a description that
        // changes length changes nothing else on screen.
        status = new JTextArea(3, 1);
        status.setEditable(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setBackground(STATUS_BG);
        status.setBorder(null);
        status.setForeground(TEXT);
        status.setFont(new Font("Monospaced", Font.PLAIN, 11));
        Dimension statusSize = new Dimension(ROW_WIDTH, status.getFontMetrics(status.getFont()).getHeight() * 3);
        status.setPreferredSize(statusSize);
        status.setMinimumSize(statusSize);
        status.setMaximumSize(statusSize);
        status.setText("loaded " + PROFILES.size() + " profiles");

        JPanel bottom = column();
        bottom.setOpaque(true);
        bottom.setBackground(STATUS_BG);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, RULE),
                new EmptyBorder(10, 12, 10, 12)));
        put(bottom, buttons);
        put(bottom, Box.createVerticalStrut(8));
        put(bottom, status);

        Runnable resync = () -> {
            for (JPanel row : baseRows) syncRow(row);
            for (JPanel row : accentRows) syncRow(row);
            for (JPanel row : extraRows) syncRow(row);
            syncRow(progressRow);
            baseRead.setText(readoutText(baseHsv));
            accentRead.setText(readoutText(accentHsv) + (hasAccent ? "" : "   (derived)"));
            extraRead.setText(readoutText(extraHsv));
            progressRead.setText(progressText());
            accentOn.setSelected(hasAccent);

            boolean boss = !stillImage() && current != null && current.isBoss();
            boolean tuning = !stillImage();
            setTitle(colours, paintMode ? "Brush" : "Colours");
            baseLabel.setText(paintMode ? "brush colour" : boss ? slotName(0) + " colour" : "base colour");
            extraLabel.setText(boss && hasExtra ? slotName(2) + " colour" : "third colour");
            // The accent checkbox asks whether a profile has a second colour at
            // all, which is a real question for a biome and not one for a boss:
            // the boss layers take a fixed number of colours and would get a
            // null where they expect one. So for a boss the slot count decides
            // and the checkbox is read-only.
            accentOn.setText(boss ? slotName(1) + " colour" : "accent colour");

            // Hide what does not apply rather than greying it out. The
            // wordmark has fixed colours and no profile behind it; the painter
            // keeps the base sliders, because they ARE the brush.
            colours.setVisible(!wordmarkMode);
            accentCard.setVisible(tuning);
            extraCard.setVisible(tuning && hasExtra);
            encounter.setVisible(boss);
            overBlack.setVisible(boss && EffectThemes.isOverlay(current.boss()));
            painter.setVisible(paintMode);
            notes.setVisible(tuning);

            for (JPanel row : accentRows) setRowEnabled(row, tuning && hasAccent);
            for (JPanel row : extraRows) setRowEnabled(row, tuning && hasExtra);
            refreshStage.run();
            refreshLoop.run();
            accentOn.setEnabled(tuning && !boss);
            for (JComponent c : tuningOnly) c.setEnabled(tuning);
            if (exportBtn != null) {
                exportBtn.setText(stillImage() ? "export PNG" : "export GIF");
                exportBtn.setToolTipText(stillImage() ? "writes the board as an image"
                        : "writes this theme as a looping GIF");
            }
            for (JComponent c : paintOnly) c.setEnabled(paintMode);
            repaintSwatches();
            content.revalidate();
            content.repaint();
        };
        brushSync = () -> {
            for (JPanel row : baseRows) syncRow(row);
            baseRead.setText(readoutText(baseHsv));
            repaintSwatches();
        };
        accentOn.addActionListener(e -> {
            hasAccent = accentOn.isSelected();
            for (JPanel row : accentRows) setRowEnabled(row, hasAccent);
            accentRead.setText(readoutText(accentHsv) + (hasAccent ? "" : "   (derived)"));
        });

        Runnable choose = () -> {
            selectProfile((String) picker.getSelectedItem());
            resync.run();
            status.setText(wordmarkMode
                    ? "wordmark: press export PNG (the keyboard is held dark)"
                    : paintMode
                    ? "key painter: drag to paint a run of keys" + (EXPORTS ? ", export PNG when done" : "")
                    : describe(current));
        };
        picker.addActionListener(e -> choose.run());

        // Swaps the picker's list without its own listener firing for every
        // intermediate selection, then keeps the chosen entry if it is still
        // in the list.
        java.util.function.Consumer<String> refill = keep -> {
            java.awt.event.ActionListener[] muted = picker.getActionListeners();
            for (java.awt.event.ActionListener l : muted) picker.removeActionListener(l);
            picker.setModel(new DefaultComboBoxModel<>(pickerItems((String) category.getSelectedItem())));
            if (keep != null) picker.setSelectedItem(keep);
            for (java.awt.event.ActionListener l : muted) picker.addActionListener(l);
        };
        category.addActionListener(e -> {
            refill.accept((String) picker.getSelectedItem());
            choose.run();
        });
        reloadBtn.addActionListener(e -> {
            loadProfiles();
            refill.accept((String) picker.getSelectedItem());
            selectProfile((String) picker.getSelectedItem());
            resync.run();
            status.setText("reloaded " + PROFILES.size() + " profiles from disk");
        });
        resetBtn.addActionListener(e -> {
            selectProfile((String) picker.getSelectedItem());
            resync.run();
            status.setText("reset to profile values");
        });

        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(SIDE_BG);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(SCROLLBAR_WIDTH, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(SIDE_WIDTH, 560));

        // Width pinned; see SIDE_WIDTH for the jitter this prevents.
        JPanel side = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(SIDE_WIDTH, super.getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(SIDE_WIDTH, super.getMinimumSize().height);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(SIDE_WIDTH, super.getMaximumSize().height);
            }
        };
        side.setBackground(SIDE_BG);
        side.add(top, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.add(bottom, BorderLayout.SOUTH);

        board = new Board();
        f.setLayout(new BorderLayout());
        f.add(board, BorderLayout.CENTER);
        f.add(side, BorderLayout.EAST);
        f.pack();
        f.setMinimumSize(new Dimension(900, 560));
        f.setLocationRelativeTo(null);

        // -Dtuner.select=<profile id> opens on that profile instead of the
        // first in the list, for coming back to the one you were tuning.
        String startOn = System.getProperty("tuner.select");
        if (startOn != null) picker.setSelectedItem(startOn);
        selectProfile((String) picker.getSelectedItem());
        resync.run();
        f.setVisible(true);
        // Focus landing on a control inside the scroll pane scrolls it into
        // view, which opened the window part way down the colours.
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));

        new Timer(33, e -> {
            renderFrame();
            board.repaint();
            // While the board plays the loop, the readout under the slider
            // follows it, so which stage is on the board can be read off the
            // number rather than guessed. The status line is left alone: it
            // is where an export's report lands, and rewriting it thirty
            // times a second would wipe the report before it could be read.
            if (previewLoop && loopShape != LoopShape.HOLD
                    && !stillImage() && current != null && current.isBoss()) {
                progressRead.setText(String.format("%3.0f%% through the encounter (playing the loop)",
                        shownProgress * 100));
            }
        }).start();
    }

    /**
     * Did this profile's pattern name actually resolve?
     *
     * <p>{@code PatternFactory} answers an unknown name with a shimmer rather
     * than an error — a deliberate choice, so a typo in a config gives you a
     * living board instead of a dead one. The cost is that a misspelled
     * pattern is invisible: it renders, it looks fine, it is simply not the
     * pattern the profile names. Worth naming explicitly here, because "69
     * rendered, 0 failed" would otherwise be perfectly true and completely
     * misleading.
     */
    static boolean fellBackToShimmer(Prof p) {
        if (p.isPortal()) return false; // portals do not go through PatternFactory at all
        if (p.pattern() == null || p.pattern().equalsIgnoreCase("shimmer")) return false;
        Pattern built = PatternFactory.fromNameAndPreset(p.pattern(), p.preset());
        return built.getClass().getSimpleName().equals("ShimmerPattern");
    }

    static void selfCheck() {
        System.out.println("game     : Minecraft " + GameVersion.describe());
        if (!HIDDEN_BY_VERSION.isEmpty()) {
            System.out.println("           hidden as too new for this branch: " + String.join(", ", HIDDEN_BY_VERSION));
        }
        for (String problem : GameVersion.problems()) System.out.println("  WARNING " + problem);
        System.out.println("defaults : " + DEFAULTS.toAbsolutePath()
                + (Files.exists(DEFAULTS) ? "  [ok]" : "  [MISSING]"));
        System.out.println("live cfg : " + LIVE.toAbsolutePath()
                + (Files.exists(LIVE) ? "  [ok]" : "  [MISSING - defaults only]"));
        System.out.println("portals  : " + DIM_DEFAULTS.toAbsolutePath()
                + (Files.exists(DIM_DEFAULTS) ? "  [ok]" : "  [MISSING]"));
        System.out.println("portal cfg: " + DIM_LIVE.toAbsolutePath()
                + (Files.exists(DIM_LIVE) ? "  [ok]" : "  [MISSING - defaults only]"));
        System.out.println("bosses   : " + BOSS_DEFAULTS.toAbsolutePath()
                + (Files.exists(BOSS_DEFAULTS) ? "  [ok]" : "  [MISSING]"));
        System.out.println("boss cfg : " + BOSS_LIVE.toAbsolutePath()
                + (Files.exists(BOSS_LIVE) ? "  [ok]" : "  [MISSING - defaults only]"));
        // Not an error when absent: the boss layers then show the shipped
        // ColorPalette colours, which is what the mod itself falls back to.
        System.out.println("boss toml: " + CONFIG_TOML.toAbsolutePath()
                + (Files.exists(CONFIG_TOML)
                ? "  [ok - " + readToml(CONFIG_TOML).size() + " tables]"
                : "  [MISSING - shipped boss colours]"));

        int portals = 0, bosses = 0, effects = 0;
        for (Prof p : PROFILES.values()) {
            if (p.isPortal()) portals++;
            else if (p.isEffect()) effects++;
            else if (p.isBoss()) bosses++;
        }
        System.out.println("profiles : " + PROFILES.size()
                + " (" + (PROFILES.size() - portals - bosses - effects) + " biome, "
                + portals + " portal, " + bosses + " boss, " + effects + " effect)");
        if (PROFILES.isEmpty()) return;

        // A field name the profile class does not answer to parses fine, loads
        // fine, and does nothing — the failure the mod's own docs warn about and
        // the one thing reading the JSON cannot show you. Worth its own line.
        List<String> strayKeys = new ArrayList<>();
        for (Prof p : PROFILES.values()) {
            if (!p.isPortal()) continue;
            for (String key : unknownKeys(p.raw())) {
                strayKeys.add(p.id() + " -> \"" + key + "\"");
            }
        }
        if (strayKeys.isEmpty()) {
            System.out.println("portal   : every field name resolves to a DimensionProfile field");
        } else {
            System.out.println("portal   : " + strayKeys.size()
                    + " FIELD NAME(S) NOT RECOGNISED - these are read and then ignored:");
            for (String s : strayKeys) System.out.println("             " + s);
        }

        // Which of the two sources each dedicated boss colour came from. The
        // same reasoning as the stray-field line above: a colour read from the
        // shipped defaults when the user has tuned theirs looks exactly like a
        // colour read from their config, and tuning against the wrong one is
        // invisible until it reaches hardware.
        Map<String, Map<String, String>> toml = readToml(CONFIG_TOML);
        List<BossThemes.Theme> tomlThemes = new ArrayList<>(BossThemes.dedicated());
        tomlThemes.addAll(EffectThemes.all());
        for (BossThemes.Theme theme : tomlThemes) {
            Map<String, String> section = toml.getOrDefault(theme.section(), Map.of());
            StringBuilder b = new StringBuilder();
            for (BossThemes.Slot slot : theme.slots()) {
                String live = section.get(slot.configKey());
                if (b.length() > 0) b.append("  ");
                b.append(slot.label()).append(' ')
                        .append(live != null ? live : slot.shipped().toHex())
                        .append(live != null ? "" : " (shipped)");
            }
            System.out.printf("  %-18s [%s]  %s%n", theme.id(), theme.section(), b);
        }

        List<String> unresolved = new ArrayList<>();
        int rendered = 0, failed = 0, litTotal = 0;
        for (Prof p : PROFILES.values()) {
            if (fellBackToShimmer(p)) unresolved.add(p.id() + " -> \"" + p.pattern() + "\"");
            try {
                selectProfile(p.id());
                renderFrameHeadless();
                int lit = 0;
                for (RGBColor c : frame.values()) if (c.r() + c.g() + c.b() > 12) lit++;
                litTotal += lit;
                rendered++;
                if (lit == 0) System.out.println("  WARNING no lit keys: " + p.id());
            } catch (Throwable t) {
                failed++;
                System.out.println("  FAILED " + p.id() + ": " + t);
            }
        }
        System.out.printf("rendered : %d ok, %d failed, %.0f lit keys on average%n",
                rendered, failed, litTotal / (double) Math.max(1, rendered));

        // Every stage of every theme, for six seconds each from a fresh
        // controller. Six, because the slowest stage to show anything is the
        // dragon's landing and the frozen sun, and a single frame of a stage
        // that starts with an ease-in would call a perfectly good stage dark.
        int stagesOk = 0, stagesFailed = 0;
        for (Prof p : PROFILES.values()) {
            if (!p.isBoss()) continue;
            for (BossThemes.Stage s : p.boss().stages()) {
                try {
                    selectProfile(p.id());
                    Pattern pinned = BossThemes.pattern(p.boss().staged(s.name()),
                            sliderColors(p.boss()), elapsed -> 0.5);
                    int lit = 0;
                    for (long t = 0; t <= 6000; t += 50) {
                        for (RGBColor c : frameAt(pinned, RGBColor.WHITE, null, t).values()) {
                            if (c.r() + c.g() + c.b() > 12) lit++;
                        }
                    }
                    stagesOk++;
                    if (lit == 0) System.out.println("  WARNING stage never lit a key: " + p.id() + " / " + s.name());
                } catch (Throwable t) {
                    stagesFailed++;
                    System.out.println("  FAILED stage " + p.id() + " / " + s.name() + ": " + t);
                }
            }
        }
        System.out.printf("stages   : %d ok, %d failed%n", stagesOk, stagesFailed);
        if (unresolved.isEmpty()) {
            System.out.println("patterns : all names resolved (no silent shimmer fallbacks)");
        } else {
            System.out.println("patterns : " + unresolved.size()
                    + " NAME(S) NOT RECOGNISED - these render as a plain shimmer:");
            for (String u : unresolved) System.out.println("             " + u);
        }

        // The number this whole tool exists for, across every profile as it
        // currently stands. Anything up near 0.4 is emitting nearly half its
        // light as white and will read washed out on hardware.
        checkProfileSwitchUpdatesControls();

        System.out.println();
        System.out.println("highest white-fraction colours (most washed out):");
        List<String> rows = new ArrayList<>();
        for (Prof p : PROFILES.values()) {
            // The third slot is in here because that is where a boss keeps the
            // colours most at risk of washing out — the Wither's powered red
            // and the dragon's breath fire are both bright and both saturated
            // by hand.
            for (String slot : new String[]{"color", "accent", "third"}) {
                String hex = switch (slot) {
                    case "color" -> p.color();
                    case "accent" -> p.accent();
                    default -> p.extra();
                };
                if (hex == null) continue;
                RGBColor c = RGBColor.fromHexOrDefault(hex, null);
                if (c == null || c.r() + c.g() + c.b() < 90) continue;
                rows.add(String.format("%.2f|  %-6s %-8s %-38s S %.2f", whiteFraction(c),
                        slot, hex, p.id(), toHsv(c)[1]));
            }
        }
        rows.sort(java.util.Comparator.reverseOrder());
        rows.stream().limit(12).forEach(r -> System.out.println("  " + r.substring(r.indexOf('|') + 1)
                + "   white " + r.substring(0, 4)));
    }

    /**
     * Regression check for the stale-array bug.
     *
     * <p>Switching profiles used to update the board but not the panel, because
     * {@code selectProfile} replaced the HSV arrays while the sliders kept the
     * originals. Nothing about that is visible in a compile or a render — the
     * board looked perfect — so it gets an explicit test: the arrays must keep
     * their identity across a switch, and must actually carry the newly
     * selected profile's colour.
     */
    static void checkProfileSwitchUpdatesControls() {
        List<Prof> withColour = new ArrayList<>();
        for (Prof p : PROFILES.values()) {
            if (p.color() != null) withColour.add(p);
            if (withColour.size() == 2 && !withColour.get(0).color().equals(withColour.get(1).color())) break;
            if (withColour.size() > 1 && withColour.get(0).color().equals(withColour.get(1).color())) {
                withColour.remove(1);
            }
        }
        if (withColour.size() < 2) {
            System.out.println("switch check : skipped (need two differently-coloured profiles)");
            return;
        }
        Prof a = withColour.get(0), b = withColour.get(1);
        int idBase = System.identityHashCode(baseHsv);
        int idAccent = System.identityHashCode(accentHsv);

        selectProfile(a.id());
        String seenA = fromHsv(baseHsv).toHex();
        selectProfile(b.id());
        String seenB = fromHsv(baseHsv).toHex();

        boolean identityHeld = System.identityHashCode(baseHsv) == idBase
                && System.identityHashCode(accentHsv) == idAccent;
        // Compared through fromHsv rather than to the raw hex, because an 8-bit
        // colour does not always survive a round trip through HSB exactly; what
        // matters is that the value tracked the switch.
        String wantA = fromHsv(toHsv(RGBColor.fromHexOrDefault(a.color(), RGBColor.WHITE))).toHex();
        String wantB = fromHsv(toHsv(RGBColor.fromHexOrDefault(b.color(), RGBColor.WHITE))).toHex();
        boolean tracked = seenA.equals(wantA) && seenB.equals(wantB) && !seenA.equals(seenB);

        System.out.printf("switch check : arrays kept identity %s | values tracked %s%n",
                identityHeld ? "yes" : "NO - sliders will not follow the profile",
                tracked ? "yes" : "NO");
        System.out.printf("               %s -> %s   then   %s -> %s%n",
                a.id(), seenA, b.id(), seenB);

        // The third slot gets the same check, because it is the same bug
        // waiting to happen and it has one extra way to go wrong: the flag
        // that says whether it applies. A boss with three colours followed by
        // one with two has to switch the group off, or the panel offers a
        // slider wired to a colour the selected theme does not have.
        Prof three = null, fewer = null;
        for (Prof p : PROFILES.values()) {
            if (p.extra() != null && three == null) three = p;
            if (p.extra() == null && p.color() != null && fewer == null) fewer = p;
        }
        if (three == null || fewer == null) {
            System.out.println("third slot   : skipped (no three-colour theme loaded)");
            return;
        }
        int idExtra = System.identityHashCode(extraHsv);
        selectProfile(three.id());
        boolean onForThree = hasExtra;
        String seenThird = fromHsv(extraHsv).toHex();
        selectProfile(fewer.id());
        boolean offForFewer = !hasExtra;
        String wantThird = fromHsv(toHsv(
                RGBColor.fromHexOrDefault(three.extra(), RGBColor.WHITE))).toHex();
        System.out.printf("third slot   : identity %s | tracked %s | switches off %s  (%s -> %s)%n",
                System.identityHashCode(extraHsv) == idExtra ? "yes" : "NO",
                seenThird.equals(wantThird) ? "yes" : "NO",
                onForThree && offForFewer ? "yes" : "NO",
                three.id(), seenThird);
    }

    /** renderFrame() without the hardware push, for the self-check. */
    static void renderFrameHeadless() {
        HardwareBridge saved = hardware;
        hardware = null;
        try {
            renderFrame();
        } finally {
            hardware = saved;
        }
    }

    static void setRowEnabled(JPanel row, boolean on) {
        for (Component c : row.getComponents()) c.setEnabled(on);
    }

    static void syncRow(JPanel row) {
        for (Component c : row.getComponents()) {
            if (c instanceof JSlider s && s.getClientProperty("sync") instanceof Runnable r) r.run();
        }
    }

    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(150, 150, 160));
        l.setFont(new Font("SansSerif", Font.PLAIN, 11));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    static JLabel mono() {
        JLabel l = new JLabel(" ");
        l.setForeground(new Color(200, 200, 210));
        l.setFont(new Font("Monospaced", Font.PLAIN, 11));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setMaximumSize(new Dimension(430, 20));
        return l;
    }

    // ---------------------------------------------------------------
    // Sidebar look and building blocks
    // ---------------------------------------------------------------

    static final Color SIDE_BG = new Color(24, 25, 30);
    static final Color STATUS_BG = new Color(19, 20, 24);
    static final Color RULE = new Color(52, 54, 64);
    static final Color TEXT = new Color(210, 211, 218);
    static final Color MUTED = new Color(135, 137, 150);
    static final Color HEADING = new Color(140, 165, 215);
    static final Font CARD_FONT = new Font("SansSerif", Font.PLAIN, 12);

    /**
     * A dark Nimbus, so the combo boxes, buttons and spinners match the panel
     * they sit on instead of being light grey slabs on a dark background.
     * Falls back to whatever Swing defaults to if Nimbus is missing.
     */
    static void installLook() {
        try {
            UIManager.put("control", new Color(34, 35, 42));
            UIManager.put("info", new Color(44, 46, 54));
            UIManager.put("nimbusBase", new Color(30, 32, 40));
            UIManager.put("nimbusBlueGrey", new Color(62, 64, 76));
            UIManager.put("nimbusLightBackground", new Color(28, 29, 35));
            UIManager.put("nimbusFocus", new Color(95, 125, 180));
            UIManager.put("nimbusSelectionBackground", new Color(64, 92, 145));
            UIManager.put("nimbusSelectedText", Color.WHITE);
            UIManager.put("nimbusDisabledText", new Color(128, 130, 142));
            UIManager.put("text", TEXT);
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // Cosmetic. The default look still works.
        }
    }

    /** A transparent vertical stack. */
    static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    /**
     * Adds to a stack, left-aligned. Every child goes through here, because
     * BoxLayout lines children up by their alignmentX and Swing's default is
     * centre for some components and left for others. Mixing the two is what
     * made rows in the old sidebar drift off to the right and get cut off.
     */
    static <T extends Component> T put(JPanel parent, T c) {
        if (c instanceof JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(c);
        return c;
    }

    /** A titled section: heading, a rule under it, then whatever is put in. */
    static JPanel section(String title) {
        JPanel s = column();
        s.setBorder(new EmptyBorder(14, 0, 0, 0));
        JLabel h = heading(title);
        s.putClientProperty("title", h);
        put(s, h);
        JSeparator rule = new JSeparator();
        rule.setForeground(RULE);
        rule.setBackground(SIDE_BG);
        rule.setMaximumSize(new Dimension(ROW_WIDTH, 2));
        put(s, Box.createVerticalStrut(3));
        put(s, rule);
        put(s, Box.createVerticalStrut(6));
        return s;
    }

    static void setTitle(JPanel section, String title) {
        if (section.getClientProperty("title") instanceof JLabel h) h.setText(title.toUpperCase());
    }

    static JLabel heading(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setForeground(HEADING);
        l.setFont(new Font("SansSerif", Font.BOLD, 11));
        return l;
    }

    static JLabel cardTitle(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(TEXT);
        l.setFont(CARD_FONT);
        return l;
    }

    /** One line of small grey explanation under a heading. */
    static JLabel hint(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        l.setFont(new Font("SansSerif", Font.PLAIN, 11));
        l.setMaximumSize(new Dimension(ROW_WIDTH, 18));
        return l;
    }

    static JCheckBox smallCheck(String text) {
        JCheckBox c = new JCheckBox(text);
        c.setForeground(TEXT);
        c.setFont(new Font("SansSerif", Font.PLAIN, 11));
        c.setOpaque(false);
        return c;
    }

    /** One colour: its name (or checkbox), the swatch, the readout, and its three sliders. */
    static JPanel colourCard(JComponent title, double[] hsv, JLabel readout, List<JPanel> rows) {
        JPanel card = column();
        card.setBorder(new EmptyBorder(0, 0, 10, 0));
        put(card, title);
        put(card, Box.createVerticalStrut(3));
        put(card, swatch(hsv));
        put(card, Box.createVerticalStrut(2));
        put(card, readout);
        for (JPanel row : rows) put(card, row);
        return card;
    }

    /** The scrolling middle of the sidebar: fits the viewport's width, scrolls its height. */
    static final class SideContent extends JPanel implements Scrollable {
        SideContent() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(SIDE_BG);
            setBorder(new EmptyBorder(0, 12, 14, 12));
        }

        @Override
        public Component add(Component c) {
            if (c instanceof JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);
            return super.add(c);
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return Math.max(16, r.height - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
