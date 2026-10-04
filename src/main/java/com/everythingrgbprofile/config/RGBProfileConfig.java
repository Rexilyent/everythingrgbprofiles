package com.everythingrgbprofile.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Every knob, in one long static initialiser. This is what generates
 * {@code everythingrgbprofiles-common.toml}, section by section.
 *
 * <h2>Why COMMON and not CLIENT</h2>
 * An earlier version had an optional server-side sculk relay that read a
 * couple of these toggles ({@code sculkAlertEnabled}) to decide whether
 * listening was worth the bother, and COMMON was what let it. That relay is
 * gone — sculk detection reads block state on the client, see
 * {@code SculkBlockWatcher} — so nothing reads this file server-side any
 * more. It stays COMMON regardless: all CLIENT would change is that a
 * dedicated server stops loading a file it already ignores, and a server
 * reading {@code portalDurationMillis} and doing nothing with it is harmless.
 * Everything SDK- and lighting-related is strictly client-only in
 * <i>behaviour</i> either way.
 *
 * <h2>Reading this file</h2>
 * Field declarations at the top, all the actual definitions in one static
 * block below. That's the shape {@code ModConfigSpec} imposes — the builder's
 * {@code push}/{@code pop} calls are what create the TOML section headers, so
 * the definitions must run in document order and every {@code push} needs its
 * matching {@code pop} or the sections nest in ways nobody intended.
 *
 * <h2>The comment strings are the actual documentation</h2>
 * The {@code b.comment(...)} text gets written into the generated TOML, which
 * is the only documentation most users will ever see. That's why several of
 * them run to a dozen lines and read like essays — a config comment that says
 * "sets the portal duration" is worthless next to one that says what the
 * number interacts with and what breaks if you get it wrong. If you add a
 * setting here, write the comment for the person editing the file at midnight,
 * not for the person who already knows the answer.
 *
 * <p>Every numeric setting uses {@code defineInRange}, so an out-of-range
 * value is clamped and reported by NeoForge rather than silently producing
 * something absurd.
 */
public final class RGBProfileConfig {


    // --- Device classes -----------------------------------------------------
    public static final ModConfigSpec.BooleanValue DEVICE_KEYBOARD_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MOUSE_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MOUSEMAT_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_HEADSET_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MEMORY_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_COOLING_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MOTHERBOARD_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_OTHER_ENABLED;

    public static final ModConfigSpec.BooleanValue PORTAL_CHARGE_UP_ENABLED;
    public static final ModConfigSpec.IntValue PORTAL_CHARGE_RAMP_MILLIS;
    public static final ModConfigSpec.IntValue PORTAL_SUPPRESSION_FLOOR;
    public static final ModConfigSpec.DoubleValue PORTAL_CHARGE_INTENSITY_FLOOR;
    public static final ModConfigSpec.BooleanValue PORTAL_DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_TRANSITIONS;
    public static final ModConfigSpec.IntValue DEBUG_HEALTH_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue DEBUG_CHAT_NOTICES;

    public static final ModConfigSpec SPEC;

    // [general]
    public static final ModConfigSpec.BooleanValue GENERAL_ENABLED;

    // [features]
    public static final ModConfigSpec.BooleanValue HEALTH_FLASH_ENABLED;
    public static final ModConfigSpec.BooleanValue NIGHT_INDICATOR_ENABLED;
    public static final ModConfigSpec.BooleanValue BIOME_COLORS_ENABLED;
    public static final ModConfigSpec.BooleanValue BOSS_EVENTS_ENABLED;
    public static final ModConfigSpec.BooleanValue HUNGER_WARNING_ENABLED;
    public static final ModConfigSpec.BooleanValue DEATH_FLASH_ENABLED;
    public static final ModConfigSpec.BooleanValue PROGRESSION_EFFECTS_ENABLED;
    public static final ModConfigSpec.BooleanValue RAIN_THUNDERSTORM_ENABLED;
    public static final ModConfigSpec.BooleanValue TOUGH_AS_NAILS_ENABLED;
    public static final ModConfigSpec.BooleanValue AETHER_ENABLED;
    public static final ModConfigSpec.BooleanValue TWILIGHT_FOREST_ENABLED;
    public static final ModConfigSpec.BooleanValue BUMBLEZONE_ENABLED;
    public static final ModConfigSpec.BooleanValue UNDERGARDEN_ENABLED;
    public static final ModConfigSpec.BooleanValue PORTAL_TRANSITION_ENABLED;
    public static final ModConfigSpec.BooleanValue RAID_WARNING_ENABLED;
    public static final ModConfigSpec.BooleanValue SCULK_ALERT_ENABLED;
    public static final ModConfigSpec.BooleanValue SLEEP_WAKE_ENABLED;
    public static final ModConfigSpec.BooleanValue WARDEN_ENCOUNTER_ENABLED;

    // [healthFlash]
    public static final ModConfigSpec.IntValue HEALTH_THRESHOLD_PERCENT;
    public static final ModConfigSpec.ConfigValue<String> HEALTH_FLASH_KEY;

    // [hungerWarning]
    public static final ModConfigSpec.IntValue HUNGER_THRESHOLD_PERCENT;
    public static final ModConfigSpec.ConfigValue<String> HUNGER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> HUNGER_FLASH_KEY;

    // [deathFlash]
    public static final ModConfigSpec.IntValue DEATH_FLASH_DURATION_MILLIS;
    public static final ModConfigSpec.ConfigValue<String> DEATH_FLASH_COLOR;
    public static final ModConfigSpec.BooleanValue DEATH_BLOOD_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> DEATH_BLOOD_SOAK_COLOR;
    public static final ModConfigSpec.ConfigValue<String> DEATH_BLOOD_COLOR;
    public static final ModConfigSpec.IntValue DEATH_BLOOD_DRIP_COUNT;
    public static final ModConfigSpec.IntValue DEATH_BLOOD_DRIP_INTERVAL_MILLIS;

    // [hardware], then the boss rooms, [enderDragon], [drowning], [burning]
    // and [nightIndicator], in that order
    public static final ModConfigSpec.ConfigValue<String> LIGHTING_BACKEND;
    public static final ModConfigSpec.ConfigValue<String> OPENRGB_HOST;
    public static final ModConfigSpec.IntValue OPENRGB_PORT;
    public static final ModConfigSpec.BooleanValue WITHER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> WITHER_BONE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> WITHER_EYE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> WITHER_POWERED_COLOR;
    public static final ModConfigSpec.IntValue WITHER_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue WITHER_GRACE_MILLIS;
    public static final ModConfigSpec.BooleanValue NAGA_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> NAGA_SCALE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> NAGA_HIGHLIGHT_COLOR;
    public static final ModConfigSpec.IntValue NAGA_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue NAGA_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue NAGA_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue SLIDER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SLIDER_STONE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SLIDER_RUNE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SLIDER_CRITICAL_COLOR;
    public static final ModConfigSpec.IntValue SLIDER_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue SLIDER_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue SLIDER_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue SUN_SPIRIT_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SUN_SPIRIT_SUN_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SUN_SPIRIT_FLAME_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SUN_SPIRIT_ICE_COLOR;
    public static final ModConfigSpec.IntValue SUN_SPIRIT_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue SUN_SPIRIT_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue SUN_SPIRIT_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue VALKYRIE_QUEEN_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> VALKYRIE_QUEEN_SILVER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> VALKYRIE_QUEEN_GOLD_COLOR;
    public static final ModConfigSpec.ConfigValue<String> VALKYRIE_QUEEN_LIGHTNING_COLOR;
    public static final ModConfigSpec.IntValue VALKYRIE_QUEEN_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue VALKYRIE_QUEEN_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue VALKYRIE_QUEEN_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue ELDER_GUARDIAN_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> ELDER_GUARDIAN_WATER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> ELDER_GUARDIAN_EYE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> ELDER_GUARDIAN_BEAM_COLOR;
    public static final ModConfigSpec.IntValue ELDER_GUARDIAN_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue ELDER_GUARDIAN_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue ELDER_GUARDIAN_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue END_DRAGON_ENABLED;
    public static final ModConfigSpec.BooleanValue END_RITUAL_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> END_VOID_COLOR;
    public static final ModConfigSpec.ConfigValue<String> END_ACCENT_COLOR;
    public static final ModConfigSpec.ConfigValue<String> END_BREATH_FIRE_COLOR;
    public static final ModConfigSpec.BooleanValue END_TRACE_RITUAL;
    public static final ModConfigSpec.BooleanValue DROWNING_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> DROWNING_DEEP_COLOR;
    public static final ModConfigSpec.ConfigValue<String> DROWNING_SURFACE_COLOR;
    public static final ModConfigSpec.DoubleValue DROWNING_PANIC_THRESHOLD;
    public static final ModConfigSpec.BooleanValue BURNING_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BURNING_CORE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> BURNING_TIP_COLOR;
    public static final ModConfigSpec.DoubleValue BURNING_PANIC_THRESHOLD;
    public static final ModConfigSpec.ConfigValue<String> NIGHT_INDICATOR_COLOR;
    public static final ModConfigSpec.BooleanValue NIGHT_STAND_DOWN_FOR_ENCOUNTERS;

    // [progressionEffects]
    public static final ModConfigSpec.BooleanValue LEVEL_UP_ENABLED;
    public static final ModConfigSpec.BooleanValue ADVANCEMENT_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> LEVEL_UP_COLOR;
    public static final ModConfigSpec.ConfigValue<String> LEVEL_UP_BAR_COLOR;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_COLOR;

    // [portalTransition]
    public static final ModConfigSpec.BooleanValue PORTAL_FALLBACK_TO_DERIVED_COLOR;
    public static final ModConfigSpec.IntValue PORTAL_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue PORTAL_HOLD_FRACTION;
    public static final ModConfigSpec.BooleanValue PORTAL_HOLD_UNTIL_WORLD_READY;
    public static final ModConfigSpec.IntValue PORTAL_POST_ARRIVAL_HOLD_MILLIS;
    public static final ModConfigSpec.IntValue PORTAL_MAX_DURATION_MILLIS;

    // [raidWarning]
    public static final ModConfigSpec.ConfigValue<String> RAID_COLOR;
    public static final ModConfigSpec.ConfigValue<String> RAID_RAIDER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> RAID_VICTORY_COLOR;

    // [sculkAlert]
    public static final ModConfigSpec.BooleanValue SCULK_SENSOR_ENABLED;
    public static final ModConfigSpec.BooleanValue SCULK_SHRIEKER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SCULK_SENSOR_COLOR;
    public static final ModConfigSpec.IntValue SCULK_SENSOR_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue SCULK_SENSOR_RING_THICKNESS_KEYS;
    public static final ModConfigSpec.ConfigValue<String> SCULK_SHRIEKER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SCULK_SHRIEKER_PEAK_COLOR;
    public static final ModConfigSpec.IntValue SCULK_SHRIEKER_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue SCULK_SHRIEKER_RING_THICKNESS_KEYS;
    public static final ModConfigSpec.IntValue SCULK_SHRIEKER_MAX_ESCALATION_LEVEL;
    public static final ModConfigSpec.IntValue SCULK_SHRIEKER_ESCALATION_WINDOW_MILLIS;
    public static final ModConfigSpec.IntValue SCULK_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue SCULK_SCAN_INTERVAL_TICKS;

    // [sleepWake]
    public static final ModConfigSpec.ConfigValue<String> SLEEP_WAKE_COLOR;
    public static final ModConfigSpec.IntValue SLEEP_WAKE_DURATION_MILLIS;

    // [biomeColors]
    public static final ModConfigSpec.BooleanValue BIOME_FALLBACK_TO_DERIVED_COLOR;

    // [bossEvents]
    public static final ModConfigSpec.BooleanValue BOSS_FALLBACK_TO_BOSSBAR_COLOR;
    public static final ModConfigSpec.BooleanValue BOSS_ENRAGE_INTENSITY_SCALING;
    public static final ModConfigSpec.BooleanValue BOSS_PROXIMITY_ENABLED;
    public static final ModConfigSpec.BooleanValue BOSS_USE_BOSSES_TAG;
    public static final ModConfigSpec.IntValue BOSS_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue BOSS_GRACE_MILLIS;
    public static final ModConfigSpec.ConfigValue<String> BOSS_EXCLUDED;

    // [rainThunderstorm]
    public static final ModConfigSpec.ConfigValue<String> RAIN_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SNOW_COLOR;
    public static final ModConfigSpec.IntValue RAIN_PARTICLE_COUNT;
    public static final ModConfigSpec.IntValue RAIN_PARTICLE_LIFESPAN_MILLIS;
    public static final ModConfigSpec.DoubleValue RAIN_LAYER_OPACITY;
    public static final ModConfigSpec.IntValue RAIN_SHELTER_GRACE_MILLIS;
    public static final ModConfigSpec.BooleanValue RAIN_REQUIRE_SKY_EXPOSURE;
    public static final ModConfigSpec.ConfigValue<String> LIGHTNING_COLOR;
    public static final ModConfigSpec.IntValue LIGHTNING_FLASH_DURATION_MILLIS;
    public static final ModConfigSpec.IntValue LIGHTNING_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue LIGHTNING_FALLBACK_INTERVAL_MILLIS;

    // [toughAsNails]
    public static final ModConfigSpec.IntValue THIRST_THRESHOLD_PERCENT;
    public static final ModConfigSpec.ConfigValue<String> THIRST_COLOR;
    public static final ModConfigSpec.ConfigValue<String> THIRST_FLASH_KEY;
    public static final ModConfigSpec.ConfigValue<String> TEMPERATURE_FLASH_KEY;
    public static final ModConfigSpec.ConfigValue<String> OVERHEATING_COLOR;
    public static final ModConfigSpec.ConfigValue<String> FREEZING_COLOR;

    // [wardenEncounter]
    public static final ModConfigSpec.IntValue WARDEN_DETECTION_RADIUS;
    public static final ModConfigSpec.ConfigValue<String> WARDEN_EMERGENCE_COLOR;
    public static final ModConfigSpec.BooleanValue WARDEN_EMERGENCE_HOLD_UNTIL_RISEN;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_PULSE_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_MIN_HOLD_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_FADE_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_MAX_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_NOMINAL_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_SUPPRESSION_FLOOR;
    public static final ModConfigSpec.ConfigValue<String> WARDEN_PRESENCE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> WARDEN_PRESENCE_GLINT_COLOR;
    public static final ModConfigSpec.IntValue WARDEN_ACTIVE_TWINKLE_COUNT;
    public static final ModConfigSpec.IntValue WARDEN_ACTIVE_TWINKLE_INTERVAL_MILLIS;

    // [menuTheme]
    public static final ModConfigSpec.BooleanValue MENU_THEME_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MENU_STYLE;
    public static final ModConfigSpec.ConfigValue<String> MENU_PACK_PATTERNS;
    public static final ModConfigSpec.ConfigValue<String> MENU_VANILLA_SKY_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_VANILLA_GRASS_COLOR;
    public static final ModConfigSpec.IntValue MENU_VANILLA_CLOUD_COUNT;
    public static final ModConfigSpec.ConfigValue<String> MENU_BASE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_EMBER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_ARCANE_COLOR;
    public static final ModConfigSpec.IntValue MENU_SPARK_COUNT;
    public static final ModConfigSpec.IntValue MENU_SPARK_INTERVAL_MILLIS;
    public static final ModConfigSpec.IntValue MENU_ORE_GLINT_COUNT;
    public static final ModConfigSpec.IntValue MENU_ORE_GLINT_INTERVAL_MILLIS;
    public static final ModConfigSpec.IntValue MENU_TORCH_COUNT;

    // [advanced]
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue ANIMATION_FRAME_RATE_HZ;
    public static final ModConfigSpec.IntValue DEBOUNCE_MILLIS;
    public static final ModConfigSpec.IntValue BIOME_SAMPLE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue BIOME_CROSSFADE_MILLIS;

    static {
        // ONE builder, threaded through every section in order. push() opens a
        // TOML table, pop() closes it — miss a pop and everything after it
        // ends up nested inside the previous section, which the game will load
        // perfectly happily and which will confuse everyone forever.
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        GENERAL_ENABLED = b.comment("Master kill switch for the whole mod.").define("enabled", true);
        b.pop();

        // Per-feature switches. All default true, because somebody who installs
        // a reactive-lighting mod presumably wants the reactive lighting. They
        // exist so that one specific effect which clashes with someone's setup
        // can be switched off without losing everything else with it.
        b.push("features");
        HEALTH_FLASH_ENABLED = b.comment(
                        "Pulse a key red when your health drops below healthFlash.thresholdPercent.")
                .define("healthFlashEnabled", true);
        NIGHT_INDICATOR_ENABLED = b.comment(
                        "Track the sun and moon across the board so you can tell how much daylight is",
                        "left without opening anything. Draws over the biome colour rather than",
                        "replacing it.")
                .define("nightIndicatorEnabled", true);
        BIOME_COLORS_ENABLED = b.comment(
                        "Colour the board to match the biome you are standing in. This is the base",
                        "layer almost everything else draws on top of, so turning it off leaves the",
                        "board dark whenever nothing more urgent is happening.")
                .define("biomeColorsEnabled", true);
        BOSS_EVENTS_ENABLED = b.comment(
                        "Master switch for boss lighting, including the generic boss pulse that",
                        "covers modded bosses. The dedicated boss animations (Wither, Ender Dragon,",
                        "Elder Guardian and friends) have their own switches in their own sections.")
                .define("bossEventsEnabled", true);
        HUNGER_WARNING_ENABLED = b.comment(
                        "Pulse a key when your food bar drops below hungerWarning.thresholdPercent.")
                .define("hungerWarningEnabled", true);
        DEATH_FLASH_ENABLED = b.comment(
                        "Light the board when you die. The flash itself is the whole of it unless",
                        "deathFlash.bloodDripEnabled is also on, which holds the board until you",
                        "respawn.")
                .define("deathFlashEnabled", true);
        PROGRESSION_EFFECTS_ENABLED = b.comment(
                        "Master switch for level-ups and advancements. Both have their own switches",
                        "under [progressionEffects]; this turns off the pair of them at once.")
                .define("progressionEffectsEnabled", true);
        RAIN_THUNDERSTORM_ENABLED = b.comment(
                        "Rain running down the board while you are out in the weather (snow instead, in",
                        "biomes where it snows), and a flash on thunder. Shelter is detected from sky",
                        "access, so standing under trees counts; see rainThunderstorm.shelterGraceMillis.")
                .define("rainThunderstormEnabled", true);
        TOUGH_AS_NAILS_ENABLED = b.comment(Feature.TOUGH_AS_NAILS.configComment(
                        "Master switch for the Tough As Nails thirst/temperature effects; no-op if that mod isn't installed."))
                .define("toughAsNailsEnabled", true);

        // One switch per dimension mod with dedicated support, each covering
        // everything this mod does for it -- the portal effect for its
        // dimensions and any boss rooms it has -- so a pack that does not want
        // a mod's lighting turns it off in one place instead of hunting down
        // four sections. The switches are read through Feature, which is where
        // the dimension-namespace mapping lives.
        //
        // There is no vanilla equivalent on purpose. The Overworld, the Nether
        // and the End follow portalTransitionEnabled and nothing else.
        AETHER_ENABLED = b.comment(
                        "Master switch for everything this mod does for the Aether: the portal effect on",
                        "the way in and out, and the Slider, Sun Spirit and Valkyrie Queen boss rooms.",
                        "",
                        "Off means the Aether is treated as a dimension this mod has never heard of. Its",
                        "portal lights nothing -- not even a generic derived colour, because with a portal",
                        "there is no lesser version to fall back TO, the effect is the whole feature. Its",
                        "bosses do keep the generic boss pulse, which comes from the 'c:bosses' tag",
                        "rather than from here, and only lose their dedicated rooms.",
                        "",
                        "The Overworld portal you walk into to GET to the Aether is vanilla and is not",
                        "gated, so with this off the trip in still lights the board on the way out and",
                        "then stops on arrival. Coming home, the Aether side lights nothing and the",
                        "Overworld arrival plays normally.",
                        "",
                        "No-op if the Aether isn't installed.")
                .define("aetherEnabled", true);
        TWILIGHT_FOREST_ENABLED = b.comment(
                        "Master switch for everything this mod does for Twilight Forest: the portal effect",
                        "on the way in and out, and the Naga's serpent animation. Its other eight bosses",
                        "are covered by the 'c:bosses' tag and keep the generic pulse either way.",
                        "See aetherEnabled for what 'off' means in full. No-op if the mod isn't installed.")
                .define("twilightForestEnabled", true);
        BUMBLEZONE_ENABLED = b.comment(
                        "Master switch for everything this mod does for the Bumblezone, which is currently",
                        "its portal effect -- the ring-shaped field it gets in place of the usual spiral,",
                        "and the dwell that fires off its teleport screen rather than a portal block, since",
                        "it is entered by throwing an ender pearl at a beehive (or using certain items on",
                        "one), with no portal block anywhere.",
                        "See aetherEnabled for what 'off' means in full. No-op if the mod isn't installed.")
                .define("bumblezoneEnabled", true);
        UNDERGARDEN_ENABLED = b.comment(
                        "Master switch for everything this mod does for the Undergarden, which is currently",
                        "its portal effect.",
                        "See aetherEnabled for what 'off' means in full. No-op if the mod isn't installed.")
                .define("undergardenEnabled", true);

        PORTAL_TRANSITION_ENABLED = b.comment(
                        "Master switch for the whole portal sequence: the spiral that builds while you",
                        "stand in a portal, and the arrival in the new dimension. Everything under",
                        "[portalTransition] is dead without this, including chargeUpEnabled.")
                .define("portalTransitionEnabled", true);
        RAID_WARNING_ENABLED = b.comment(
                        "Light the board during a village raid: the Raid Omen counting down, the horde",
                        "marching across the bottom rows, and fireworks or a defeat if you win or lose.")
                .define("raidWarningEnabled", true);
        SCULK_ALERT_ENABLED = b.comment(
                        "Master switch for sculk sensors and shriekers. Each has its own switch under",
                        "[sculkAlert]; this turns off both and stops the block scan entirely, so it is",
                        "also the setting to use if you want the cost back rather than just the light.")
                .define("sculkAlertEnabled", true);
        SLEEP_WAKE_ENABLED = b.comment(
                        "Fade the board out when you get into a bed and back in when you get up.")
                .define("sleepWakeEnabled", true);
        WARDEN_ENCOUNTER_ENABLED = b.comment(
                        "Light the board while a Warden is near you, including the moment one digs its",
                        "way out of the floor. Separate from the sculk switches above: this is about",
                        "the Warden itself rather than the blocks that summon it.")
                .define("wardenEncounterEnabled", true);
        b.pop();

        b.push("healthFlash");
        HEALTH_THRESHOLD_PERCENT = b.comment(
                        "Health percentage below which the warning starts. 25 is five health points, or",
                        "two and a half hearts out of ten, which is about where the game starts being",
                        "genuinely dangerous.")
                .defineInRange("thresholdPercent", 25, 1, 99);
        HEALTH_FLASH_KEY = b.comment(
                        "Which key carries the warning, by its printed label.",
                        "",
                        "If the label is not on your keyboard the effect lights the WHOLE board instead",
                        "and says so in the log, which is deliberate: a typo that silently lit one",
                        "unrelated key would be far harder to notice. Note that Logitech G HUB and",
                        "SteelSeries GG do not report where keys are, so on those this always falls",
                        "back to the whole board no matter what you put here.")
                .define("flashKey", "H");
        b.pop();

        b.push("hungerWarning");
        HUNGER_THRESHOLD_PERCENT = b.comment(
                        "Food percentage below which the warning starts. 30 is 6 food points out of 20,",
                        "and at 6 or less you can no longer sprint, so with the default the warning comes",
                        "on one point after sprinting stops. (Natural healing needs 18 or more, so that",
                        "has stopped long before either.)")
                .defineInRange("thresholdPercent", 30, 1, 99);
        HUNGER_COLOR = b.comment("The warning colour. Amber, kept clear of the health red on purpose.")
                .define("color", "#FFA500");
        HUNGER_FLASH_KEY = b.comment(
                        "Which key carries the warning. Same rules as healthFlash.flashKey: an unknown",
                        "label falls back to the whole board and logs why.")
                .define("flashKey", "F");
        b.pop();

        b.push("deathFlash");
        DEATH_FLASH_DURATION_MILLIS = b.comment(
                        "How long the whole-board flash at the moment of death lasts. If bloodDripEnabled",
                        "is on, the drips take over when this finishes and hold until you respawn.")
                .defineInRange("durationMillis", 800, 50, 10000);
        DEATH_FLASH_COLOR = b.comment("The momentary whole-board flash at the moment of death.")
                .define("flashColor", "#FF1A1A");
        DEATH_BLOOD_ENABLED = b.comment(
                        "After the flash, hold blood running down the board until you respawn or quit. "
                                + "While it is up nothing else draws at all -- not the biome, not rain, "
                                + "not the moon -- because a moon calmly tracking across a death "
                                + "screen is absurd.")
                .define("bloodDripEnabled", true);
        DEATH_BLOOD_SOAK_COLOR = b.comment("The soaked board under the drips.")
                .define("bloodSoakColor", "#2E0709");
        DEATH_BLOOD_COLOR = b.comment("The drips themselves, and the streaks they leave behind.")
                .define("bloodColor", "#C81020");
        DEATH_BLOOD_DRIP_COUNT = b.comment("Drips running at once.")
                .defineInRange("bloodDripCount", 7, 1, 48);
        DEATH_BLOOD_DRIP_INTERVAL_MILLIS = b.comment("Average gap before a spent drip slot starts another.")
                .defineInRange("bloodDripIntervalMillis", 900, 80, 10000);
        b.pop();

        b.push("hardware");
        LIGHTING_BACKEND = b.comment(
                        "Which lighting software to drive. Nothing extra needs installing: each option "
                                + "uses the software that already came with your hardware.",
                        "  auto        - connect to everything that answers (default). The first one "
                                + "with a keyboard leads; the rest show the same effects.",
                        "  corsair     - Corsair iCUE",
                        "  razer       - Razer Synapse, with its Chroma module installed",
                        "  logitech    - Logitech G HUB",
                        "  steelseries - SteelSeries GG (GameSense)",
                        "  openrgb     - OpenRGB's SDK server, for hardware none of the above cover "
                                + "(ASUS, MSI, and a long tail of others).",
                        "Pick one explicitly if auto chooses the wrong device. An unrecognised value "
                                + "falls back to auto with a warning rather than refusing to start.")
                .define("lightingBackend", "auto");
        OPENRGB_HOST = b.comment("Where the OpenRGB SDK server is listening. Enable it in OpenRGB under SDK Server.")
                .define("openRgbHost", "127.0.0.1");
        OPENRGB_PORT = b.comment("OpenRGB SDK server port.")
                .defineInRange("openRgbPort", 6742, 1, 65535);
        b.pop();

        b.push("wither");
        WITHER_ENABLED = b.comment(
                        "Three skulls over a spine, tracking separately and spitting. Includes the "
                                + "eleven-second summon charge, and reddens when it armours up below "
                                + "half health.")
                .define("enabled", true);
        WITHER_BONE_COLOR = b.comment("Skull and spine.").define("boneColor", "#4A4652");
        WITHER_EYE_COLOR = b.comment("Eyes, the summon charge, and the skulls it throws.")
                .define("eyeColor", "#A8DCF5");
        WITHER_POWERED_COLOR = b.comment("Blended in below half health, when the armour goes on.")
                .define("poweredColor", "#B02436");
        WITHER_DETECTION_RADIUS = b.comment("Blocks. It flies, and it retreats when hurt.")
                .defineInRange("detectionRadius", 96, 16, 256);
        WITHER_GRACE_MILLIS = b.comment("How long the board is held after it goes out of sight.")
                .defineInRange("graceMillis", 5000, 0, 60000);
        b.pop();

        b.push("naga");
        NAGA_ENABLED = b.comment(Feature.NAGA.configComment(
                        "Twilight Forest's Naga gets a serpent drawn on the keys instead of a generic "
                                + "boss pulse, and it sheds body segments as you damage it — the same "
                                + "way the real one does. Costs nothing when Twilight Forest is absent. "
                                + "Off whatever this says if twilightForestEnabled in [features] is off."))
                .define("enabled", true);
        NAGA_SCALE_COLOR = b.comment("The body.").define("scaleColor", "#2F8F3A");
        NAGA_HIGHLIGHT_COLOR = b.comment("The head, so you can tell which end is coming at you.")
                .define("highlightColor", "#B6F04A");
        NAGA_DETECTION_RADIUS = b.comment(
                        "How far the Naga can be and still own the board. Its courtyard is large and it "
                                + "roams the whole thing, so a tight radius drops the effect while you "
                                + "are very much still in the fight. 128 is about the limit of what the "
                                + "client tracks anyway.")
                .defineInRange("detectionRadius", 128, 16, 256);
        NAGA_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the Naga stops being visible. Covers it "
                                + "ducking behind the hedge or briefly leaving entity tracking, neither "
                                + "of which means the fight is over.")
                .defineInRange("graceMillis", 5000, 0, 60000);
        NAGA_FADE_MILLIS = b.comment("How long the snake takes to dissolve back into the biome once it really is gone.")
                .defineInRange("fadeOutMillis", 1400, 100, 10000);
        b.pop();

        b.push("slider");
        SLIDER_ENABLED = b.comment(Feature.SLIDER.configComment(
                        "The Aether's Slider gets its room drawn on the keys instead of a generic boss "
                                + "pulse: the board is the boss room seen from above with north at the "
                                + "top, the cube slides and slams where the real one does, and a gold "
                                + "dot marks where you are standing. Costs nothing when the Aether is absent. "
                                + "Off whatever this says if aetherEnabled in [features] is off."))
                .define("enabled", true);
        SLIDER_STONE_COLOR = b.comment("The cube, the dust it throws up, and the dungeon floor.")
                .define("stoneColor", "#6F7B8C");
        SLIDER_RUNE_COLOR = b.comment("The runes on the cube while it is awake.")
                .define("runeColor", "#3A86FF");
        SLIDER_CRITICAL_COLOR = b.comment("The runes below a quarter health, where the real one turns red and speeds up.")
                .define("criticalColor", "#FF3308");
        SLIDER_DETECTION_RADIUS = b.comment(
                        "Blocks. The same as the generic boss detector's radius by default, so that "
                                + "anywhere a sleeping Slider would start the generic pulse, this "
                                + "takes the board instead.")
                .defineInRange("detectionRadius", 48, 8, 128);
        SLIDER_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the Slider drops out of range. Short, "
                                + "because the Slider never leaves its room: losing sight of it means "
                                + "you left, not that it did.")
                .defineInRange("graceMillis", 3000, 0, 60000);
        SLIDER_FADE_MILLIS = b.comment("How long the room takes to dissolve back into the biome once it is gone.")
                .defineInRange("fadeOutMillis", 1200, 100, 10000);
        b.pop();

        b.push("sunSpirit");
        SUN_SPIRIT_ENABLED = b.comment(Feature.SUN_SPIRIT.configComment(
                        "The Aether's Sun Spirit gets its boss room drawn on the keys instead of a generic "
                                + "boss pulse: the board is the room seen from above with north at the top, "
                                + "the spirit is a burning sun where the real one flies, and the fire on the "
                                + "floor and the crystals it throws are drawn where they really are. When "
                                + "an ice crystal freezes it, it turns blue and a ring counts down the time "
                                + "you have to hit it. Costs nothing when the Aether is absent. "
                                + "Off whatever this says if aetherEnabled in [features] is off."))
                .define("enabled", true);
        SUN_SPIRIT_SUN_COLOR = b.comment("The spirit's body.").define("sunColor", "#FFA012");
        SUN_SPIRIT_FLAME_COLOR = b.comment("Its corona, the fire on the floor, the fire crystals, and the room's glow.")
                .define("flameColor", "#E0460F");
        SUN_SPIRIT_ICE_COLOR = b.comment("Frozen, the countdown ring, and the ice crystals.")
                .define("iceColor", "#3FB4F0");
        SUN_SPIRIT_DETECTION_RADIUS = b.comment(
                        "Blocks. The same as the generic boss detector's radius by default, so this "
                                + "always takes the board where the generic pulse would have.")
                .defineInRange("detectionRadius", 48, 8, 128);
        SUN_SPIRIT_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the spirit drops out of range. Short, because "
                                + "it never leaves its room.")
                .defineInRange("graceMillis", 3000, 0, 60000);
        SUN_SPIRIT_FADE_MILLIS = b.comment("How long the room takes to dissolve back into the biome once it is gone.")
                .defineInRange("fadeOutMillis", 1500, 100, 10000);
        b.pop();

        b.push("valkyrieQueen");
        VALKYRIE_QUEEN_ENABLED = b.comment(Feature.VALKYRIE_QUEEN.configComment(
                        "The Aether's Valkyrie Queen gets her throne room drawn on the keys instead of a "
                                + "generic boss pulse: the board is the room seen from above with north at the "
                                + "top, and she is a winged figure where the real one is, turned toward you, "
                                + "with her wings folded, open or swept into a lunge. Her teleports, the "
                                + "thunder crystals she throws and the lightning they become are drawn where "
                                + "they happen, and each crystal's crackle speeds up as its fuse runs out. "
                                + "Costs nothing when the Aether is absent. Off whatever this says if "
                                + "aetherEnabled in [features] is off."))
                .define("enabled", true);
        VALKYRIE_QUEEN_SILVER_COLOR = b.comment("Her body and wings, and the puff she leaves when she teleports.")
                .define("silverColor", "#B8C4FF");
        VALKYRIE_QUEEN_GOLD_COLOR = b.comment("The room's glow, the fight starting, and the room opening when she is beaten.")
                .define("goldColor", "#FFB12E");
        VALKYRIE_QUEEN_LIGHTNING_COLOR = b.comment("Thunder crystals, the reach of their lightning, and the strikes themselves.")
                .define("lightningColor", "#1FD8FF");
        VALKYRIE_QUEEN_DETECTION_RADIUS = b.comment(
                        "Blocks. The same as the generic boss detector's radius by default, so this "
                                + "always takes the board where the generic pulse would have.")
                .defineInRange("detectionRadius", 48, 8, 128);
        VALKYRIE_QUEEN_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after she drops out of range. Short, because she "
                                + "never leaves her room.")
                .defineInRange("graceMillis", 3000, 0, 60000);
        VALKYRIE_QUEEN_FADE_MILLIS = b.comment("How long the room takes to dissolve back into the biome once she is gone.")
                .defineInRange("fadeOutMillis", 1500, 100, 10000);
        b.pop();

        b.push("elderGuardian");
        ELDER_GUARDIAN_ENABLED = b.comment(
                        "Elder Guardians get an eye drawn on the keys instead of a generic boss pulse: "
                                + "the body with its crown of spikes, and the laser winding up. The "
                                + "wind-up is the useful part — it runs for a full three seconds, which "
                                + "is your window to break line of sight, and the board is in step with "
                                + "the real one rather than guessing.")
                .define("enabled", true);
        ELDER_GUARDIAN_WATER_COLOR = b.comment("The body, its spikes, and the monument water behind them.")
                .define("waterColor", "#2E8F87");
        ELDER_GUARDIAN_EYE_COLOR = b.comment("The eye at rest.").define("eyeColor", "#F2A23C");
        ELDER_GUARDIAN_BEAM_COLOR = b.comment(
                        "The laser. The eye runs from its own colour into this one and then into white "
                                + "across the wind-up, so this is what 'about to fire' looks like.")
                .define("beamColor", "#B453E8");
        ELDER_GUARDIAN_DETECTION_RADIUS = b.comment(
                        "How far one can be and still own the board. 48 covers a monument room and "
                                + "roughly matches the 50 blocks from which it curses you, so the board "
                                + "is showing a Guardian whenever one can actually reach you.")
                .defineInRange("detectionRadius", 48, 8, 128);
        ELDER_GUARDIAN_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the last one drops out of range. Monuments "
                                + "are full of walls, and swimming behind one does not end the fight.")
                .defineInRange("graceMillis", 4000, 0, 60000);
        ELDER_GUARDIAN_FADE_MILLIS = b.comment("How long the eye takes to dissolve back into the biome once they really are gone.")
                .defineInRange("fadeOutMillis", 1600, 100, 10000);
        b.pop();

        b.push("enderDragon");
        END_DRAGON_ENABLED = b.comment(
                        "Lighting for the Ender Dragon: the summoning ritual, the fight itself (phase "
                                + "aware, not just health), and the death sequence.")
                .define("enabled", true);
        END_RITUAL_ENABLED = b.comment(
                        "Show YUNG's Better End Island's staged pillar ritual as rays converging on the "
                                + "middle of the board: one ray per tower once its crystal lights, plus a "
                                + "sweeping ray following whichever tower is being activated. Only with that "
                                + "mod installed. Vanilla's own respawn is deliberately left dark, because "
                                + "in a vanilla End the crystals are the dragon's healing and should only "
                                + "ever mean that.")
                .define("ritualEnabled", true);
        END_VOID_COLOR = b.comment("Resting colour for the whole End sequence.")
                .define("voidColor", "#5B2C8F");
        END_ACCENT_COLOR = b.comment("Beams, breath and the death rings.")
                .define("accentColor", "#D34DFF");
        END_BREATH_FIRE_COLOR = b.comment(
                        "Dragon's breath burning along the bottom of the board. This is the colour at "
                                + "the flame TIPS; the base is a lightened version of it, because fire "
                                + "cools as it rises and that gradient is what makes the ragged top edge "
                                + "read as tongues.")
                .define("breathFireColor", "#8B2FE0");
        END_TRACE_RITUAL = b.comment(
                        "Writes a transcript of the summoning ritual to the log: every end crystal, "
                                + "where it is (distance and bearing from origin, which identifies which "
                                + "pillar), when its beam turns on and off, and where the beam points. "
                                + "The ritual lighting was fitted to transcripts like this one; turn it "
                                + "on for one respawn if the rays look out of step with what you see, "
                                + "and include the log when reporting it. Also follows the master "
                                + "debug switch. Off by default: it is noisy on purpose.")
                .define("traceRitual", false);
        b.pop();

        b.push("drowning");
        DROWNING_ENABLED = b.comment(
                        "Floods the keyboard from the bottom up as your breath runs out, and drains it "
                                + "again when you surface. Overlays the biome rather than replacing it, "
                                + "so the waterline reads as a level.")
                .define("enabled", true);
        DROWNING_DEEP_COLOR = b.comment("Deep water, at the bottom of the board.")
                .define("deepColor", "#0E3FA8");
        DROWNING_SURFACE_COLOR = b.comment("The waterline itself, and the shallows just under it.")
                .define("surfaceColor", "#5FC8E8");
        DROWNING_PANIC_THRESHOLD = b.comment(
                        "How full the board gets before the water starts pulsing. 0.85 means the last "
                                + "15% of your air. Set to 1.0 for no pulse at all.")
                .defineInRange("panicThreshold", 0.85, 0.0, 1.0);
        b.pop();

        b.push("burning");
        BURNING_ENABLED = b.comment(Feature.BURNING.configComment(
                        "Sets the keyboard alight from the bottom up while you are on fire, and lets it die "
                                + "down when the fire goes out. The flames are low if Fire Resistance is "
                                + "protecting you, about half the board while you burn, most of it while you "
                                + "stand in fire, and all of it in lava. Overlays the biome rather than "
                                + "replacing it, like the drowning water."))
                .define("enabled", true);
        BURNING_CORE_COLOR = b.comment("The base of the flames, where they are hottest.")
                .define("coreColor", "#FFB01F");
        BURNING_TIP_COLOR = b.comment("The tips of the flames.")
                .define("tipColor", "#E8350C");
        BURNING_PANIC_THRESHOLD = b.comment(
                        "How high the flames get before the whole fire starts surging. The default is "
                                + "only reached in lava. Set to 1.0 for no surge at all.")
                .defineInRange("panicThreshold", 0.9, 0.0, 1.0);
        b.pop();

        b.push("nightIndicator");
        NIGHT_STAND_DOWN_FOR_ENCOUNTERS = b.comment(
                        "Hide the moon while a boss or encounter layer owns the board. On by default: "
                                + "those layers draw a whole scene, and a moon calmly tracking sunset "
                                + "across a dragon reads as a bug rather than as a second layer.",
                        "Health, hunger and drowning warnings are unaffected — a boss fight is exactly "
                                + "when you want those.")
                .define("standDownForEncounters", true);
        NIGHT_INDICATOR_COLOR = b.comment(
                        "Moonlight. Pale blue-white, and deliberately cool so it reads as night against",
                        "whatever the biome layer is doing underneath it.")
                .define("color", "#C8D6FF");
        b.pop();

        b.push("progressionEffects");
        LEVEL_UP_ENABLED = b.comment(
                        "Flash on gaining an experience level. Also needs features.progressionEffectsEnabled.")
                .define("levelUpEnabled", true);
        ADVANCEMENT_ENABLED = b.comment(
                        "Flash when an advancement toast appears. Also needs",
                        "features.progressionEffectsEnabled. Fires for every advancement the game shows",
                        "you a toast for, which in a large pack is more often than you might expect.")
                .define("advancementEnabled", true);
        LEVEL_UP_COLOR = b.comment("The level-up burst: the gold wave and the sparkles.")
                .define("levelUpColor", "#FFD700");
        LEVEL_UP_BAR_COLOR = b.comment("The experience bar that fills along the bottom of the board before it bursts.")
                .define("levelUpBarColor", "#80FF20");
        ADVANCEMENT_COLOR = b.comment(
                        "The advancement flash colour. Nudged off pure cyan so it cannot be confused",
                        "with the sculk sensor ping, which is a very different kind of news.")
                .define("advancementColor", "#00E5FF");
        b.pop();

        b.push("portalTransition");
        PORTAL_FALLBACK_TO_DERIVED_COLOR = b.comment(
                        "What to do about a dimension with no entry in dimension_profiles.json.",
                        "",
                        "On, it invents a stable colour from the dimension's id, so an obscure modded",
                        "dimension still gets a consistent identity of its own instead of nothing. The",
                        "same id always produces the same colour, on every machine, forever.",
                        "",
                        "Off makes almost no difference: an unlisted dimension still gets the full portal",
                        "effect, in that same invented colour, when you arrive. The one change is the",
                        "charge-up while you stand in the portal, which uses a fixed purple instead.")
                .define("fallbackToDerivedColor", true);
        // One full rotation of the fitted portal field is 1420ms. The arrival
        // no longer contains a flash of any kind: it holds the spinning field
        // and then crossfades down into the biome layer. With
        // holdUntilWorldReady on (the default) durationMillis/holdFraction
        // only set the MINIMUM hold and the fade length -- the fade itself is
        // anchored to the end of the terrain load, not to a wall clock.
        PORTAL_DURATION_MILLIS = b.comment(
                        "Baseline arrival length, minimum hold plus fade-out. One rotation of the portal",
                        "spiral is 1420ms, so values below ~1500 will not show a complete turn.",
                        "With holdUntilWorldReady = true this is a floor, not a total: the hold is",
                        "extended for as long as the terrain load actually takes.")
                .defineInRange("durationMillis", 4200, 100, 30000);
        PORTAL_HOLD_FRACTION = b.comment(
                        "Split of durationMillis between hold and fade. At the default 4200ms and 0.5",
                        "that is a 2100ms minimum hold (about 1.5 rotations) and a 2100ms dissolve.",
                        "Raise this to shorten the dissolve, lower it to lengthen it.",
                        "Replaces the old spiralPhaseFraction, which gated an arrival flash that no",
                        "longer exists -- delete that key from your config, it is ignored.")
                .defineInRange("holdFraction", 0.5, 0.0, 0.95);
        PORTAL_HOLD_UNTIL_WORLD_READY = b.comment(
                        "Keep the field at full until the destination has actually finished loading,",
                        "instead of running a fixed stopwatch from the moment of transfer.",
                        "",
                        "The dimension change is detected as early as it can be seen, which is before",
                        "the client spends one to several seconds rebuilding chunks and shaders. The",
                        "lighting runs on its own thread, so a fixed budget was being spent entirely",
                        "inside that stall: the board was already dark by the time \"Downloading terrain\"",
                        "cleared and the player could see anything. There is no good fixed number for",
                        "this -- the load is however long the pack makes it.",
                        "",
                        "On means: hold through the load, then hold postArrivalHoldMillis longer once",
                        "you are in the world, then dissolve into the biome layer. Off restores the old",
                        "fixed-stopwatch behaviour.")
                .define("holdUntilWorldReady", true);
        PORTAL_POST_ARRIVAL_HOLD_MILLIS = b.comment(
                        "How long the field stays at full AFTER the terrain screen clears, before the",
                        "dissolve starts. This is the part of the arrival the player actually watches,",
                        "so it is worth more than it looks. Ignored when holdUntilWorldReady is false.")
                .defineInRange("postArrivalHoldMillis", 1200, 0, 15000);
        PORTAL_MAX_DURATION_MILLIS = b.comment(
                        "Hard ceiling on the whole arrival, so a load that never completes cannot leave",
                        "the spiral spinning forever. Only reached if the terrain screen never clears;",
                        "30000 matches vanilla's own timeout on that screen. The fade is never",
                        "truncated by this -- the hold is shortened to make room for it.")
                .defineInRange("maxDurationMillis", 30000, 1000, 120000);
        PORTAL_CHARGE_UP_ENABLED = b.comment(
                        "Light the board while you stand IN a portal, before the transfer completes.",
                        "Without this you only get the arrival, once the transfer completes.",
                        "Set false for arrival-only behaviour.")
                .define("chargeUpEnabled", true);
        PORTAL_CHARGE_RAMP_MILLIS = b.comment(
                        "How long the spiral takes to ease from chargeIntensityFloor up to full strength",
                        "while you stand in a portal. Keep this well under the shortest portal dwell you",
                        "care about: a nether portal gives about 4000ms, but an Aether portal was measured",
                        "at 382ms, and anything slower than the dwell means the spiral is still fading in",
                        "when the dimension changes.")
                .defineInRange("chargeRampMillis", 300, 50, 20000);
        PORTAL_CHARGE_INTENSITY_FLOOR = b.comment(
                        "Field strength on the very first frame of the dwell, before the ramp has done",
                        "anything. This also sets how much of the board the dwell owns on that frame:",
                        "below it the biome layer reads through. 0.35 was too low to be legible on the",
                        "short transfers that most modded portals actually have -- the Aether and",
                        "similar were measured at 160-420ms from hitbox contact to dimension change,",
                        "so the ramp never got anywhere and walking in looked like nothing happened.",
                        "Set 0 for a pure fade from the biome; the trade-off is exactly that.")
                .defineInRange("chargeIntensityFloor", 0.6, 0.0, 1.0);
        PORTAL_SUPPRESSION_FLOOR = b.comment(
                        "For the whole portal sequence -- dwell, arrival and fade-out -- Tier 3 flashes",
                        "below this priority are held off instead of blanking the board. Reference",
                        "priorities: death 100, shrieker 90, lightning 85, level up / advancement 40,",
                        "sculk ping 20, sleep 10. The default of 96 lets nothing but",
                        "death interrupt a portal; 86 would also let shrieker alerts through. Set 0",
                        "to let every flash interrupt. A Warden emerging is drawn as an overlay, not",
                        "a flash, so it shows alongside the portal whatever this is set to.")
                .defineInRange("tier3SuppressionFloor", 96, 0, 100);
        PORTAL_DEBUG_LOGGING = b.comment(
                        "Log a timestamped line when portal intersection is detected and when the dimension",
                        "actually changes. The gap between the two tells you whether the effect is firing late",
                        "or firing on time and being drawn over.")
                .define("debugLogging", false);
        b.pop();

        b.push("debug");
        DEBUG_CHAT_NOTICES = b.comment(
                        "Say in chat, once, when no lighting connected, when a connection drops mid-game, or",
                        "when the mod hits an error, naming the command that explains it. Whatever this is set",
                        "to, /rgbprofiles status explains the lighting, /rgbprofiles test checks the keyboard",
                        "shows the right colours in the right places, and /rgbprofiles report writes a file",
                        "with everything needed to help with a problem.")
                .define("chatNotices", true);
        DEBUG_ENABLED = b.comment(
                        "Master switch for diagnostic logging. Off costs one boolean read per frame.",
                        "For effect problems rather than hardware ones: it logs every effect activation with",
                        "who owns the board at that moment, which distinguishes 'my effect never fired' from",
                        "'my effect fired and something drew over it'. Hardware problems are always logged,",
                        "and /rgbprofiles report covers them without this.")
                .define("enabled", false);
        DEBUG_LOG_TRANSITIONS = b.comment(
                        "Log a line whenever any effect becomes active or inactive.")
                .define("logEffectTransitions", true);
        DEBUG_HEALTH_INTERVAL_SECONDS = b.comment(
                        "How often to log render-thread health: achieved fps, frame cost, job queue depth.",
                        "The worker runs independently of the client tick, so this shows whether animation",
                        "is actually stalling during world generation or only appearing to.")
                .defineInRange("healthIntervalSeconds", 10, 1, 600);
        b.pop();

        b.push("raidWarning");
        RAID_COLOR = b.comment(
                        "A raid, drawn like a Terraria invasion: while the Raid Omen counts down, a "
                                + "quickening heartbeat and an army marching onto the board; during the raid, "
                                + "the horde glowing along the bottom rows with the army marching across it "
                                + "and a flash each time the raid horn blows; fireworks when it is won, and "
                                + "the army jumping in celebration when it is lost.",
                        "This colour is the horde, its glow, and the raiders' bodies.")
                .define("color", "#B22222");
        RAID_RAIDER_COLOR = b.comment("The raiders' heads.").define("raiderColor", "#B8D0A0");
        RAID_VICTORY_COLOR = b.comment("The victory fireworks.").define("victoryColor", "#44FF44");
        b.pop();

        b.push("sculkAlert");
        SCULK_SENSOR_ENABLED = b.comment(
                        "Ring on the board when a nearby sculk sensor picks something up. Sensors in a",
                        "Deep Dark fire constantly, so this is the noisier half of the pair.")
                .define("sensorEnabled", true);
        SCULK_SHRIEKER_ENABLED = b.comment(
                        "Ring on the board when a nearby shrieker actually shrieks. This is the half",
                        "worth keeping if you only want one, since shrieks are what summon the Warden.")
                .define("shriekerEnabled", true);
        SCULK_SENSOR_COLOR = b.comment("The sensor ring. Part of the sculk family, so it stays in the teals.")
                .define("sensorColor", "#4FC3C3");
        SCULK_SENSOR_DURATION_MILLIS = b.comment("How long a sensor ring takes to expand and fade.")
                .defineInRange("sensorDurationMillis", 700, 50, 10000);
        SCULK_SENSOR_RING_THICKNESS_KEYS = b.comment(
                        "How thick the expanding ring is, in rough key widths. The key width here is",
                        "estimated from the board's overall size, so it is about right on a full-size",
                        "board and comes out chunkier on smaller ones.")
                .defineInRange("sensorRingThicknessKeys", 1.0, 0.25, 5.0);
        SCULK_SHRIEKER_COLOR = b.comment(
                        "The shrieker ring at its calmest, i.e. the first shriek. It climbs from here",
                        "toward shriekerPeakColor as shrieks accumulate.")
                .define("shriekerColor", "#1F8C7A");
        SCULK_SHRIEKER_PEAK_COLOR = b.comment(
                        "Colour at maximum escalation. Wants to be BRIGHTER than shriekerColor, not "
                                + "darker — the ramp used to end near black, so the more shrieks you "
                                + "set off the less you could see the warning.")
                .define("shriekerPeakColor", "#5FF5DC");
        SCULK_SHRIEKER_DURATION_MILLIS = b.comment("How long a shrieker ring takes to expand and fade.")
                .defineInRange("shriekerDurationMillis", 900, 50, 10000);
        SCULK_SHRIEKER_RING_THICKNESS_KEYS = b.comment("Ring thickness in key widths, as above.")
                .defineInRange("shriekerRingThicknessKeys", 1.0, 0.25, 5.0);
        SCULK_SHRIEKER_MAX_ESCALATION_LEVEL = b.comment(
                        "How many repeat shrieks it takes to reach full intensity. The first shriek is",
                        "level 0, so the default of 3 reaches full intensity on the fourth shriek in a",
                        "row, which is also the one where vanilla summons a Warden. Set 0 for no",
                        "escalation at all and every shriek looking identical.")
                .defineInRange("shriekerMaxEscalationLevel", 3, 0, 10);
        SCULK_SHRIEKER_ESCALATION_WINDOW_MILLIS = b.comment(
                        "Gap within which a repeat shriek escalates rather than resetting. Much " +
                        "shorter than the game's own memory: vanilla only drops " +
                        "its warning level after 10 minutes without a shriek, and ignores shrieks " +
                        "within 10 seconds of the last one it counted, so the board can reset " +
                        "while the game is still counting toward a Warden.")
                .defineInRange("shriekerEscalationWindowMillis", 20000, 1000, 120000);
        SCULK_DETECTION_RADIUS = b.comment(
                        "How far to watch for sensors and shriekers firing. Cost scales with the "
                                + "number of chunk sections in range that actually contain sculk, not "
                                + "with the radius cubed — see SculkBlockWatcher.")
                .defineInRange("detectionRadius", 16, 1, 128);
        SCULK_SCAN_INTERVAL_TICKS = b.comment(
                        "Ticks between activation scans. A sculk sensor stays ACTIVE for 30 ticks, a "
                                + "calibrated one for only 10, and a shrieker shrieks for 90. So 10 or less "
                                + "catches every event; above 10 starts missing calibrated sensors, and above "
                                + "30 starts missing ordinary ones.")
                .defineInRange("scanIntervalTicks", 5, 1, 40);
        b.pop();

        b.push("sleepWake");
        SLEEP_WAKE_COLOR = b.comment("The colour the board fades through on the way into and out of a bed.")
                .define("color", "#3355AA");
        SLEEP_WAKE_DURATION_MILLIS = b.comment(
                        "How long each fade takes. The same number covers both directions: out when you",
                        "lie down, in when you get up.")
                .defineInRange("durationMillis", 1000, 50, 10000);
        b.pop();

        b.push("biomeColors");
        BIOME_FALLBACK_TO_DERIVED_COLOR = b.comment(
                        "What to do about a biome with no entry in biome_profiles.json.",
                        "",
                        "On, it invents a colour from the biome's own grass and water colours, which is",
                        "what stops a 600-mod pack from being mostly grey. The same biome is always the",
                        "same colour.",
                        "",
                        "Off currently makes no difference: an unlisted biome still gets that invented",
                        "colour. A mod-wide wildcard entry such as \"somemod:*\" in the JSON is checked",
                        "BEFORE any of this, so one line there sets the colour for everything a mod adds.")
                .define("fallbackToDerivedColor", true);
        b.pop();

        b.push("bossEvents");
        BOSS_FALLBACK_TO_BOSSBAR_COLOR = b.comment(
                        "Despite the name, this does not read boss bars and currently changes nothing.",
                        "A boss with no entry in boss_profiles.json (and no mod-wide wildcard) gets a",
                        "stable colour invented from its entity id whether this is on or off.")
                .define("fallbackToBossBarColor", true);
        BOSS_ENRAGE_INTENSITY_SCALING = b.comment(
                        "Let the pulse get faster and harder as a boss loses health, rather than",
                        "pulsing at one steady rate for the whole fight. Only does anything for bosses",
                        "whose profile sets enrageThresholdPercent.")
                .define("enrageIntensityScaling", true);
        BOSS_PROXIMITY_ENABLED = b.comment(
                        "Detect bosses by looking for them near you. This is the only way the generic "
                                + "boss pulse finds anything (nothing reads boss bars), so turning it off "
                                + "turns the generic boss pulse off entirely; the dedicated boss rooms "
                                + "are unaffected. It catches the bosses that are bosses the way the Warden "
                                + "is one, with no bar above the screen, which several Legendary Monsters "
                                + "mobs are.")
                .define("proximityDetectionEnabled", true);
        BOSS_USE_BOSSES_TAG = b.comment(
                        "Treat anything in the 'c:bosses' convention tag as a boss. This is the mod "
                                + "author's own declaration of what a boss is, and eleven mods in the "
                                + "Forge Everything pack populate it, Legendary Monsters included. The "
                                + "only other way something counts as a boss is an exact entry for its "
                                + "id in boss_profiles.json, which is how to add one that isn't tagged.")
                .define("useBossesTag", true);
        BOSS_DETECTION_RADIUS = b.comment("Blocks. Generous, because bosses are large and fight at range.")
                .defineInRange("proximityDetectionRadius", 48, 4, 128);
        BOSS_GRACE_MILLIS = b.comment(
                        "How long the encounter lighting survives the boss leaving range, so a dragon "
                                + "circling out and back does not restart the effect each pass.")
                .defineInRange("proximityGraceMillis", 5000, 0, 60000);
        BOSS_EXCLUDED = b.comment(
                        "Comma-separated list of things proximity detection must ignore. Accepts exact "
                                + "entity ids ('minecraft:warden') and mod-wide wildcards "
                                + "('mutantmonsters:*'). Checked before everything else, so it "
                                + "overrides the c:bosses tag and a hand-written profile alike.",
                        "Defaults explained: the Warden, the Ender Dragon and the Wither have dedicated "
                                + "layers of their own that outrank the generic one anyway. So does the Naga, "
                                + "but its layer is held back in release builds, which with this exclusion "
                                + "currently leaves a release build drawing nothing for the Naga at all.",
                        "Mutant Monsters and Ice and Fire's three dragons are left over from when any "
                                + "mob with a big enough health pool counted as a boss, which those roaming "
                                + "mobs kept tripping. Detection now only counts the c:bosses tag and exact "
                                + "profile entries, and neither mod tags those mobs, so these two entries do "
                                + "nothing today. They stay so that a future update tagging them doesn't "
                                + "bring the problem back.")
                .define("proximityExcluded",
                        "minecraft:warden, minecraft:ender_dragon, minecraft:wither, "
                                + "twilightforest:naga, mutantmonsters:*, "
                                + "iceandfire:fire_dragon, iceandfire:ice_dragon, iceandfire:lightning_dragon");
        b.pop();

        b.push("rainThunderstorm");
        RAIN_COLOR = b.comment("Pale blue-grey. Wants to stay brighter than the biome colours it falls over.")
                .define("rainColor", "#AECDE8");
        SNOW_COLOR = b.comment(
                        "Snowfall, which replaces the rain in biomes cold enough to snow. Tinted blue on",
                        "purpose: pure white on an LED reads as a stuck key rather than as snow. Shares",
                        "rainOpacity, requireSkyExposure and shelterGraceMillis with the rain.")
                .define("snowColor", "#CFE9FF");
        RAIN_PARTICLE_COUNT = b.comment("Drops in flight. Some are off-board at any moment, so on-board density is lower than this.")
                .defineInRange("rainParticleCount", 12, 1, 64);
        RAIN_PARTICLE_LIFESPAN_MILLIS = b.comment(
                        "Wants to be close to the ~2900ms the slowest drop needs to cross the whole board. "
                                + "Much shorter and drops die partway down and the bottom rows stay dry; the "
                                + "default of 2800 gets even the slowest drops down to the bottom row.")
                .defineInRange("rainParticleLifespanMillis", 2800, 100, 10000);
        RAIN_LAYER_OPACITY = b.comment(
                        "How much of the biome underneath survives a drop. 1.0 replaces the key "
                                + "outright, which on a dark biome reads as the biome having vanished. "
                                + "Lower this if rain is drowning out the scenery.")
                .defineInRange("rainOpacity", 0.78, 0.05, 1.0);
        RAIN_REQUIRE_SKY_EXPOSURE = b.comment(
                        "Only show rain where the player is genuinely being rained on, rather than "
                                + "whenever it happens to be raining somewhere in the world. Uses the "
                                + "game's own per-position test, so a ravine or any other opening to the "
                                + "sky counts as outdoors while a cave under it does not.")
                .define("requireSkyExposure", true);
        RAIN_SHELTER_GRACE_MILLIS = b.comment(
                        "How long rain keeps running after you duck out of it. Without a grace period "
                                + "the overlay snaps off and on as you walk under trees, since leaves "
                                + "block sky just as effectively as stone does.")
                .defineInRange("shelterGraceMillis", 4000, 0, 60000);
        LIGHTNING_COLOR = b.comment("The thunder flash. Near-white with a blue cast, like the real thing.")
                .define("lightningColor", "#F0F5FF");
        LIGHTNING_FLASH_DURATION_MILLIS = b.comment("How long each thunder flash lasts.")
                .defineInRange("lightningFlashDurationMillis", 400, 50, 5000);
        LIGHTNING_DETECTION_RADIUS = b.comment(
                        "NOT WIRED UP YET. Nothing reads this value, so changing it does nothing.",
                        "",
                        "It is meant for the better version of the thunder flash: watching for actual",
                        "lightning bolt entities appearing within this many blocks, rather than the",
                        "fixed timer described below. That needs confirming in a running client",
                        "first, so the setting is kept here rather than added later, so your config",
                        "carries over unchanged to the version where it starts working.")
                .defineInRange("lightningDetectionRadius", 64, 1, 256);
        LIGHTNING_FALLBACK_INTERVAL_MILLIS = b.comment(
                        "How often a flash fires during a thunderstorm: once every this many",
                        "milliseconds, for as long as it is thundering.",
                        "",
                        "This is a fixed timer rather than a response to real bolts, and it is a compromise:",
                        "the flash is roughly in step with the storm rather than exactly in step with",
                        "the strike you just heard. Raise it for a calmer storm, lower it for a busier",
                        "one. See lightningDetectionRadius for the version that fixes this properly.")
                .defineInRange("lightningFallbackIntervalMillis", 8000, 500, 60000);
        b.pop();

        // Everything in this section is dormant, and not because of anything you
        // did. The Tough As Nails integration is a placeholder that never returns
        // a reading, so none of these values reach the board yet. They are kept
        // so your settings survive to the version where it works. See
        // ToughAsNailsCompat for what finishing it involves.
        b.push("toughAsNails");
        THIRST_THRESHOLD_PERCENT = b.comment(
                        "Thirst percentage below which the warning starts.",
                        "Dormant until the Tough As Nails integration is finished; see above.")
                .defineInRange("thirstThresholdPercent", 30, 1, 99);
        THIRST_COLOR = b.comment("The thirst warning colour. Cyan, kept clear of the hunger amber.")
                .define("thirstColor", "#3FBFD4");
        THIRST_FLASH_KEY = b.comment(
                        "Which key carries the thirst warning. Same rules as healthFlash.flashKey: an",
                        "unknown label falls back to the whole board and logs why.")
                .define("thirstFlashKey", "T");
        TEMPERATURE_FLASH_KEY = b.comment(
                        "Which key carries the temperature warning. One key covers both directions,",
                        "since you cannot be overheating and freezing at the same time.")
                .define("temperatureFlashKey", "K");
        OVERHEATING_COLOR = b.comment("Too hot.").define("overheatingColor", "#FF6B35");
        FREEZING_COLOR = b.comment("Too cold.").define("freezingColor", "#A8E0F0");
        b.pop();

        b.push("wardenEncounter");
        WARDEN_DETECTION_RADIUS = b.comment(
                        "How close a Warden has to be before it takes the board, in blocks. The default",
                        "of 24 matches the range at which a Warden senses nearby creatures to sniff out,",
                        "so the board tends to react at about the point the Warden can start reacting to",
                        "you.")
                .defineInRange("detectionRadius", 24, 1, 128);
        WARDEN_EMERGENCE_COLOR = b.comment(
                        "The rings that close in while a Warden climbs out of the ground. They are "
                                + "drawn over the dim Warden presence wash, so a dark colour here "
                                + "disappears into it at the worst possible moment. Keep it bright.")
                .define("emergenceColor", "#2EE0C8");
        WARDEN_EMERGENCE_HOLD_UNTIL_RISEN = b.comment(
                        "Hold the emergence effect until the Warden actually finishes climbing out, "
                                + "rather than for a fixed length. Vanilla's emergence runs 134 ticks "
                                + "(6.7s); the effect used to borrow the shrieker's 900ms and so ended "
                                + "while the Warden was still in the ground. Turn off for a fixed "
                                + "minHold + fade.")
                .define("emergenceHoldUntilRisen", true);
        WARDEN_EMERGENCE_PULSE_MILLIS = b.comment(
                        "One ring contraction. It repeats for the whole emergence, so this is the "
                                + "heartbeat rate, not the total length.")
                .defineInRange("emergencePulseMillis", 1100, 120, 10000);
        WARDEN_EMERGENCE_MIN_HOLD_MILLIS = b.comment("Floor on the hold, so a fast release still registers.")
                .defineInRange("emergenceMinHoldMillis", 2000, 0, 30000);
        WARDEN_EMERGENCE_FADE_MILLIS = b.comment("Dissolve back into the Warden presence layer.")
                .defineInRange("emergenceFadeMillis", 1200, 50, 10000);
        WARDEN_EMERGENCE_MAX_MILLIS = b.comment(
                        "Hard cap. A Warden that somehow never leaves its emerging pose costs a long "
                                + "effect rather than a stuck one.")
                .defineInRange("emergenceMaxDurationMillis", 14000, 1000, 60000);
        WARDEN_EMERGENCE_NOMINAL_MILLIS = b.comment(
                        "Roughly how long an emergence runs. Shapes the build-up only — the ending is "
                                + "driven by the real pose, not by this.")
                .defineInRange("emergenceNominalMillis", 6700, 500, 60000);
        WARDEN_EMERGENCE_SUPPRESSION_FLOOR = b.comment(
                        "Tier 3 flashes below this priority are held off while a Warden is emerging. "
                                + "Below death (100) on purpose: dying during an emergence is still "
                                + "information you need.")
                .defineInRange("emergenceSuppressionFloor", 96, 0, 100);
        WARDEN_PRESENCE_COLOR = b.comment(
                        "Wash colour held on the board for as long as a Warden is in range. Replaces "
                                + "the biome layer, so it wants to be dim but genuinely lit.")
                .define("presenceColor", "#0C2A33");
        WARDEN_PRESENCE_GLINT_COLOR = b.comment("Glints over the presence wash.")
                .define("presenceGlintColor", "#24C8B8");
        WARDEN_ACTIVE_TWINKLE_COUNT = b.comment(
                        "How many sculk glints sit on the board while a Warden is nearby. More of them",
                        "reads as busier and more alarming; fewer reads as something lurking.")
                .defineInRange("activeTwinkleCount", 7, 1, 64);
        WARDEN_ACTIVE_TWINKLE_INTERVAL_MILLIS = b.comment(
                        "How often those glints move to new keys. Lower is more agitated.")
                .defineInRange("activeTwinkleIntervalMillis", 1100, 50, 5000);
        b.pop();

        b.push("menuTheme");
        MENU_THEME_ENABLED = b.comment(
                        "Ambient lighting while no world is loaded — title screen, server list, mod "
                                + "list and so on. Turn off for a dark keyboard at the menu. Which "
                                + "theme you get is 'style' below.")
                .define("enabled", true);
        MENU_STYLE = b.comment(
                        "Which title-screen theme to use.",
                        "  vanilla   - sky over grass with drifting clouds. The default, because a",
                        "              mod you just installed should not redecorate your menu with",
                        "              somebody else's art direction.",
                        "  mineshaft - the dark cave face with torches, a drill and ore glints. Built",
                        "              for the Forge Everything pack's title screen specifically.",
                        "  auto      - mineshaft if this looks like one of menuPackNames below,",
                        "              vanilla otherwise.",
                        "Pack maintainers: set this outright in defaultconfigs/ rather than relying on",
                        "auto. Detection is a convenience and cannot see inside every launcher.")
                .define("style", "vanilla");
        MENU_PACK_PATTERNS = b.comment(
                        "Comma-separated. Under 'auto', the mineshaft theme is used when the detected "
                                + "pack name contains any of these. Matched as a case-insensitive "
                                + "substring, so version suffixes do not break it.")
                .define("menuPackNames", "Forge Everything");
        MENU_VANILLA_SKY_COLOR = b.comment("Default theme: the sky above the horizon.")
                .define("vanillaSkyColor", "#5B93E8");
        MENU_VANILLA_GRASS_COLOR = b.comment("Default theme: the grass below it.")
                .define("vanillaGrassColor", "#4BA83C");
        MENU_VANILLA_CLOUD_COUNT = b.comment("Default theme: clouds drifting across the sky. 0 for a clear day.")
                .defineInRange("vanillaCloudCount", 4, 0, 12);
        MENU_BASE_COLOR = b.comment("Cold unlit rock. Everything else is light falling on this.")
                .define("baseColor", "#241B16");
        MENU_EMBER_COLOR = b.comment(
                        "The one warm colour in the scene. Torchlight, the drill's hot core, and the " +
                        "sparks are all derived from it, so recolouring this keeps the fire coherent.")
                .define("emberColor", "#FF7A29");
        MENU_ARCANE_COLOR = b.comment(
                        "The 'something magical in the dark crevices' accent. It is one entry in the " +
                        "ore glint table (the amethyst), not the only colour glinting.")
                .define("arcaneColor", "#9B5FFF");
        MENU_TORCH_COUNT = b.comment(
                        "How many torches stay lit behind the drill. The drill plants one every "
                                + "few keys as it cuts across the board and the oldest gutters out, so "
                                + "this is really the length of the lit corridor trailing it.",
                        "Renamed from 'torchCount', which meant something else: torches used to be "
                                + "scattered once at startup and never moved. The rename is deliberate -- "
                                + "it is what lets the new default reach anyone who already has a "
                                + "config file, since existing keys are kept as they are.")
                .defineInRange("torchTrailLength", 5, 1, 12);
        MENU_SPARK_COUNT = b.comment("Sparks in flight off the drill bit. They only spawn while the drill is actually cutting.")
                .defineInRange("sparkCount", 14, 1, 64);
        MENU_SPARK_INTERVAL_MILLIS = b.comment("Average gap before a spent spark slot throws another. Lower = a denser spray.")
                .defineInRange("sparkIntervalMillis", 260, 40, 5000);
        MENU_ORE_GLINT_COUNT = b.comment("Ore facets catching the light at once. Kept low so a glint stays an event.")
                .defineInRange("oreGlintCount", 5, 1, 32);
        MENU_ORE_GLINT_INTERVAL_MILLIS = b.comment("Average gap between one slot's glints. Raise for a quieter, rarer wall.")
                .defineInRange("oreGlintIntervalMillis", 1500, 120, 8000);
        b.pop();

        b.push("advanced");
        UPDATE_INTERVAL_TICKS = b.comment("Polling cadence for the slower STATE checks, such as time of day. The biome has its own,",
                        "biomeSampleIntervalTicks. Unrelated to animation frame rate.")
                .defineInRange("updateIntervalTicks", 20, 1, 1200);
        ANIMATION_FRAME_RATE_HZ = b.comment("How often the SDK worker thread advances any active animated pattern.")
                .defineInRange("animationFrameRateHz", 30, 1, 60);
        DEBOUNCE_MILLIS = b.comment(
                        "How long a new biome must persist before it commits. Only meaningful if it is "
                                + "LONGER than biomeSampleIntervalTicks -- otherwise every change "
                                + "commits on the second consecutive sample and the debounce filters "
                                + "nothing while still costing a full sample interval of delay.")
                .defineInRange("debounceMillis", 250, 0, 5000);
        BIOME_SAMPLE_INTERVAL_TICKS = b.comment(
                        "How often to check which biome you are standing in, independently of "
                                + "updateIntervalTicks. Biome lookup is a cheap chunk-local query, and "
                                + "sharing the one-second ambient cadence meant a change took between "
                                + "one and two seconds to appear -- long enough to feel disconnected "
                                + "from walking across the border.")
                .defineInRange("biomeSampleIntervalTicks", 4, 1, 40);
        BIOME_CROSSFADE_MILLIS = b.comment(
                        "How long one biome takes to dissolve into the next. Raise for a gentler "
                                + "transition; the fade always starts from whatever is currently on "
                                + "screen, so crossing a border mid-fade stays smooth.")
                .defineInRange("biomeCrossfadeMillis", 1400, 50, 10000);
        b.pop();

        b.comment(
                 "Which classes of lighting hardware this mod is allowed to drive.",
                 "Only the keyboard is on by default: a fresh install should never",
                 "start flashing someone's motherboard or RAM unasked. Modpack",
                 "authors can ship different defaults, and users can switch any",
                 "class off without disabling the mod.",
                 "Corsair and OpenRGB report every device they control, so all of",
                 "these apply to them. Razer, Logitech and SteelSeries only ever",
                 "drive a keyboard, so for those only keyboardEnabled matters.",
                 "Disabled classes are skipped during device enumeration, so they",
                 "cost nothing at all -- no LEDs read, no buffers allocated.")
         .push("devices");
        // Per-device-class switches. Keyboard is the only one on by default, on
        // purpose: installing a Minecraft mod and having your RAM sticks and
        // motherboard abruptly start flashing at you is not a delightful
        // surprise, it is an alarming one. Opt in to the light show.
        //
        // These only matter for hardware your lighting software actually reports.
        // Switching one on when you own nothing in that category does nothing at
        // all, harmlessly.
        DEVICE_KEYBOARD_ENABLED = b.comment(
                        "Light the keyboard. This is the device everything is designed around, and",
                        "turning it off leaves the mod running with almost nowhere to draw.")
                .define("keyboardEnabled", true);
        DEVICE_MOUSE_ENABLED = b.comment("Light the mouse, where the lighting software exposes one.")
                .define("mouseEnabled", false);
        DEVICE_MOUSEMAT_ENABLED = b.comment("Light the mousemat. Usually a strip of zones around the edge.")
                .define("mousematEnabled", false);
        // Everything except the keyboard defaults to FALSE. Installing a
        // Minecraft mod and having your RAM and motherboard start pulsing at
        // you unprompted is a jump-scare, not a feature. Opt in.
        DEVICE_HEADSET_ENABLED = b.comment("Light the headset, including headset stands that report as one.")
                .define("headsetEnabled", false);
        DEVICE_MEMORY_ENABLED = b.comment("Light RGB RAM modules.")
                .define("memoryEnabled", false);
        DEVICE_COOLING_ENABLED = b.comment("Light fans, AIO pump heads and coolers. On Corsair this also covers LED strip",
                        "controllers (Lighting Node, Commander and the like).")
                .define("coolingEnabled", false);
        DEVICE_MOTHERBOARD_ENABLED = b.comment("Light motherboard zones and anything plugged into its RGB headers. On Corsair",
                        "this also covers graphics cards; OpenRGB files those under otherDevicesEnabled.")
                .define("motherboardEnabled", false);
        DEVICE_OTHER_ENABLED = b.comment(
                        "Light anything that does not fall into the categories above. Whatever your",
                        "lighting software could not classify ends up here, so this is the catch-all. On",
                        "OpenRGB that includes graphics cards, LED strips, cases, speakers and monitors.")
                .define("otherDevicesEnabled", false);
        b.pop();

        // build() locks the spec. Nothing may be defined after this, which is
        // why every section lives in this one static block rather than being
        // spread across lazily-initialised helpers.
        SPEC = b.build();
    }

    private RGBProfileConfig() {
    }
}
