package com.everythingrgbprofile.color;

/**
 * Every named colour in the mod, in one file, so that no effect anywhere ever
 * contains a raw hex literal.
 *
 * <h2>Why bother, it's just colours</h2>
 * Because inline hex is how you end up with four subtly different reds across
 * three files and no way to know which one is "the" health red. Also because
 * the groupings below are load-bearing design decisions, not decoration — the
 * whole premise of the mod is that you can glance at your keyboard and know
 * what happened <i>without reading anything</i>. That only works if related
 * events look related and unrelated events don't.
 *
 * <p>These are the colours that are deliberately <b>not</b> user-configurable —
 * internal pattern defaults and family anchors. Anything a user is meant to
 * tweak lives in {@link com.everythingrgbprofile.config.RGBProfileConfig}
 * instead, and the values here mirror those defaults.
 *
 * <h2>Why so many of these look oversaturated on screen</h2>
 * Because a keyboard is not a screen, and colours picked on a screen kept
 * coming out muddy or washed out on the board. The reason is physical: each
 * key is a red, a green and a blue LED behind one diffuser, and they mix
 * additively. Whatever all three channels have in common — {@code min(r,g,b)}
 * — comes out as white light and dilutes the hue. A sand colour like
 * {@code #DBCFA3} is about three-quarters white, which on a monitor is sand
 * and on a key is a white key with a faint opinion.
 *
 * <p>Two rules came out of tuning these on the real board, and most of the
 * history comments in this file are one of them being learned:
 *
 * <ul>
 *   <li><b>Keep saturation high and vary brightness instead.</b> Making a colour
 *       "lighter" the way you would with paint raises all three channels, which
 *       is exactly the white that washes it out. Brighter means more of the
 *       dominant channel, not more of the other two.</li>
 *   <li><b>Judge brightness by the strongest channel, not by luminance.</b> An
 *       LED is as bright as its hardest-driven die. Luminance weights green
 *       heavily and blue barely at all, so a deep blue can score as nearly
 *       black while the key is plainly lit, and two colours with equal
 *       luminance can look nothing alike on the board.</li>
 * </ul>
 *
 * <p>Dark colours have the opposite problem: below roughly a tenth of full
 * drive, an LED stops looking dim and starts looking off. Several entries
 * below were lifted for exactly that reason.
 */
public final class ColorPalette {

    // ---- Status / warning family -------------------------------------
    // Every one of these has to be identifiable in peripheral vision while
    // you're busy not dying, so they're spread across the hue wheel on
    // purpose. If two of these ever drift close together, that's a bug.
    public static final RGBColor HEALTH_RED = RGBColor.fromHex("#FF1E1E");
    public static final RGBColor HUNGER_AMBER = RGBColor.fromHex("#FFA500");
    public static final RGBColor THIRST_CYAN = RGBColor.fromHex("#3FBFD4");
    public static final RGBColor OVERHEATING_ORANGE = RGBColor.fromHex("#FF6B35");
    public static final RGBColor FREEZING_ICE = RGBColor.fromHex("#A8E0F0");

    // ---- Sculk family ------------------------------------------------
    // These four are meant to read as ONE escalating thing, not four separate
    // events: ambient glint -> "something heard you" -> "something
    // is actively shrieking about it" -> warden. Same teal family throughout,
    // increasing intensity. You should feel your stomach drop as it climbs,
    // which is a weirdly specific design goal for a keyboard, and yet.
    // Warden presence, as a WASH colour rather than a twinkle colour. At
    // luminance 24 the old #10182B was indistinguishable from an unlit key,
    // and the effect that used it drew nothing but twinkles — so a Warden
    // walking up replaced the whole biome layer with a black board and two
    // near-black dots. It read as the mod crashing at the exact moment the
    // game got interesting. Lifted to a dim teal that still says "cave", but
    // is actually emitting something.
    public static final RGBColor SCULK_AMBIENT = RGBColor.fromHex("#0C2A33");
    /** The glints over that wash — the Warden's own chest-glow cyan. */
    public static final RGBColor SCULK_WARDEN_GLINT = RGBColor.fromHex("#24C8B8");
    public static final RGBColor SCULK_SENSOR_PING = RGBColor.fromHex("#4FC3C3");
    public static final RGBColor SCULK_SHRIEKER_BASE = RGBColor.fromHex("#1F8C7A");
    /**
     * Peak escalation, and it is now BRIGHTER than the base rather than
     * darker. The ramp used to run #1F8C7A (luminance 116) toward #08211C
     * (luminance 27), so the more shrieks you racked up the closer the alert
     * got to invisible — the most dangerous state in the game rendered as an
     * almost unlit board. An escalating warning has to escalate.
     */
    public static final RGBColor SCULK_SHRIEKER_PEAK = RGBColor.fromHex("#5FF5DC");
    /**
     * Warden emergence. This used to be a Tier 3 flash, and Tier 3 <b>blanks
     * the whole board</b> before it draws — so painting it in the old #08211C
     * (luminance 27) meant a Warden digging its way out of the floor turned the
     * keyboard off. It is a Tier 2 overlay now, but the colour stayed: the
     * Warden's sonic-boom cyan, which is what that moment actually looks like
     * in game.
     */
    public static final RGBColor WARDEN_EMERGENCE = RGBColor.fromHex("#2EE0C8");

    // ---- Progression family ------------------------------------------
    public static final RGBColor LEVEL_UP_GOLD = RGBColor.fromHex("#FFD700");
    /** The experience bar filling in the level-up animation: the green of the game's own bar. */
    public static final RGBColor LEVEL_UP_XP_GREEN = RGBColor.fromHex("#80FF20");
    // Nudged off pure cyan on purpose: at #00FFFF this sits right on top of
    // the sculk sensor ping, and "you earned an advancement" reading as
    // "something in the dark noticed you" is a genuinely bad time.
    public static final RGBColor ADVANCEMENT_CYAN = RGBColor.fromHex("#00E5FF");

    // ---- One-offs ----------------------------------------------------
    /** The momentary flash on death. Red, not white — you did not win anything. */
    public static final RGBColor DEATH_FLASH_RED = RGBColor.fromHex("#FF1A1A");
    /** The board soaked through, under the drips. Dark enough to read as "off, but wrong". */
    public static final RGBColor DEATH_BLOOD_SOAK = RGBColor.fromHex("#2E0709");
    /** Running blood: the drip heads and the streaks they leave. */
    public static final RGBColor DEATH_BLOOD = RGBColor.fromHex("#C81020");
    public static final RGBColor NIGHT_MOONLIGHT = RGBColor.fromHex("#C8D6FF");
    public static final RGBColor SLEEP_WAKE_BLUE = RGBColor.fromHex("#3355AA");
    /** The raid's horde, its bar, and the band glowing up from the bottom of the board: the raid bar's red. */
    public static final RGBColor RAID_WARNING_RED = RGBColor.fromHex("#B22222");
    /**
     * The raiders' heads. The illagers' pale grey-green skin, kept a little
     * greener than it really is so it cannot be mistaken for white on a key.
     */
    public static final RGBColor RAID_RAIDER = RGBColor.fromHex("#B8D0A0");
    /** A raid won: the green of the Hero of the Village effect. */
    public static final RGBColor RAID_VICTORY = RGBColor.fromHex("#44FF44");

    // --- The Wither -----------------------------------------------------
    /**
     * Skull and spine. Charcoal rather than the near-black the mob actually
     * is: an LED at luminance 20 is indistinguishable from an LED that is off,
     * and a boss you cannot see is not atmospheric, it is broken.
     */
    public static final RGBColor WITHER_BONE = RGBColor.fromHex("#4A4652");
    /** The eyes, the charge, and the skulls it spits. */
    public static final RGBColor WITHER_EYE = RGBColor.fromHex("#A8DCF5");
    /** Armoured below half health, when it starts diving at you. */
    public static final RGBColor WITHER_POWERED = RGBColor.fromHex("#B02436");

    // --- The Elder Guardian ---------------------------------------------
    /**
     * The body, its spikes, and the monument water around it.
     *
     * <p>Prismarine teal, and lifted well clear of the near-black that deep
     * ocean water actually is — the same reasoning as {@link #WITHER_BONE}. It
     * carries the whole silhouette, so it has to be visible as a shape rather
     * than as a suggestion of one.
     */
    public static final RGBColor GUARDIAN_PRISMARINE = RGBColor.fromHex("#2E8F87");
    /** The eye: warm amber against all that teal, which is what makes it an eye. */
    public static final RGBColor GUARDIAN_EYE = RGBColor.fromHex("#F2A23C");
    /** The laser, which the eye runs into over the three seconds it winds up. */
    public static final RGBColor GUARDIAN_BEAM = RGBColor.fromHex("#B453E8");
    /**
     * Mining Fatigue. The olive of the debuff's own icon, used for the moment
     * the curse lands and for souring the water while it holds.
     */
    public static final RGBColor GUARDIAN_CURSE = RGBColor.fromHex("#6E6B2E");

    // --- Twilight Forest ------------------------------------------------
    /** Naga body: the deep green of its scales. */
    public static final RGBColor NAGA_SCALE_GREEN = RGBColor.fromHex("#2F8F3A");
    /** Naga head, and the eye on it. */
    public static final RGBColor NAGA_HIGHLIGHT = RGBColor.fromHex("#B6F04A");

    // --- The Aether -----------------------------------------------------
    /**
     * The Slider's stone, and the dungeon floor under it. A cool grey rather
     * than the flat #7F7F7F of its texture, so the body stays apart from the
     * near-white flash of a hit landing on it.
     */
    public static final RGBColor SLIDER_STONE = RGBColor.fromHex("#6F7B8C");
    /** The runes on its faces while it is awake, from the blue of its glow texture. */
    public static final RGBColor SLIDER_RUNE = RGBColor.fromHex("#3A86FF");
    /** The same runes below a quarter health, where the texture turns red-orange. */
    public static final RGBColor SLIDER_CRITICAL = RGBColor.fromHex("#FF3308");
    /**
     * The marker for where you are in the room. Gold, because nothing else on
     * the board is: it has to read as a different kind of thing from the stone
     * and the runes at a glance, since it is the thing the cube is hunting.
     */
    public static final RGBColor SLIDER_PLAYER = RGBColor.fromHex("#FFC24A");

    /** The Sun Spirit's molten body, between the two oranges its texture is mostly made of. */
    public static final RGBColor SUN_SPIRIT_SUN = RGBColor.fromHex("#FFA012");
    /**
     * The hotter, redder orange of its texture's darker flecks: the corona's
     * tips, fire on the floor, the fire crystals, and the glow of the room.
     */
    public static final RGBColor SUN_SPIRIT_FLAME = RGBColor.fromHex("#E0460F");
    /** Frozen, and the ice crystals that freeze it. Its frozen texture's deeper blue. */
    public static final RGBColor SUN_SPIRIT_ICE = RGBColor.fromHex("#3FB4F0");
    /**
     * Where you are. Not the Slider's gold, which would vanish into a room
     * that is already gold and orange everywhere: green is the one hue nothing
     * else in this fight uses.
     */
    public static final RGBColor SUN_SPIRIT_PLAYER = RGBColor.fromHex("#6DFF8A");
    /** The colour its death drains the board to: the Aether's eternal day ending. */
    public static final RGBColor SUN_SPIRIT_DUSK = RGBColor.fromHex("#1E2E78");

    /**
     * The Valkyrie Queen's wings and armour. Her texture is a blue-tinted
     * white; this keeps the tint and some of the pallor, so she reads as
     * silver on the keys rather than as the pure white of a hit landing.
     */
    public static final RGBColor VALKYRIE_QUEEN_SILVER = RGBColor.fromHex("#B8C4FF");
    /**
     * Her room's light — the Silver Dungeon is lit by ambrosium torches and
     * glowstone — and the room opening when she is beaten.
     */
    public static final RGBColor VALKYRIE_QUEEN_GOLD = RGBColor.fromHex("#FFB12E");
    /**
     * Thunder crystals and the lightning they turn into. The crystal's
     * texture is a pale electric aqua; deepened to cyan so it cannot be
     * mistaken for her silver.
     */
    public static final RGBColor VALKYRIE_QUEEN_LIGHTNING = RGBColor.fromHex("#1FD8FF");
    /** Where you are. The same green as in the Sun Spirit's room, and for the same reason: nothing else here is green. */
    public static final RGBColor VALKYRIE_QUEEN_PLAYER = RGBColor.fromHex("#6DFF8A");

    // --- The End -------------------------------------------------------
    /** The void between the islands: the resting colour of the whole sequence. */
    public static final RGBColor END_VOID_PURPLE = RGBColor.fromHex("#5B2C8F");
    /** Dragon breath and crystal beams — the bright half of the End's palette. */
    public static final RGBColor END_DRAGON_MAGENTA = RGBColor.fromHex("#D34DFF");
    /** Perched on the portal: cooler and calmer, the window where you can hit it. */
    public static final RGBColor END_PERCHED = RGBColor.fromHex("#3E7BD6");
    /** The fireball, and the flame it sits and breathes. */
    public static final RGBColor END_DRAGON_FLAME = RGBColor.fromHex("#FF5FD2");
    /** Dragon's breath on the floor: deep purple at the flame tips. */
    public static final RGBColor END_BREATH_FIRE = RGBColor.fromHex("#8B2FE0");
		public static final RGBColor ENDER_DRAGON_PURPLE = RGBColor.fromHex("#7F00FF");

    /** Deep water, at the bottom of a flooded board. Dense enough to hide the biome. */
    public static final RGBColor DROWNING_DEEP = RGBColor.fromHex("#0E3FA8");
    /** The waterline catching light — paler and brighter than what is under it. */
    public static final RGBColor DROWNING_SURFACE = RGBColor.fromHex("#5FC8E8");
    /** The base of the flames when you are burning: the hottest part, nearly yellow. */
    public static final RGBColor BURNING_CORE = RGBColor.fromHex("#FFB01F");
    /** The tips of the flames, where the fire cools to red. */
    public static final RGBColor BURNING_TIP = RGBColor.fromHex("#E8350C");

    // Rain has to stay legible on top of whatever biome is underneath it, and
    // the biome it loses to is a bright one. The earlier #7A9BB5 had a relative
    // luminance of about 150; plains (#91BD59) sits at 172 at the top of its
    // shimmer. A raindrop that is DARKER than the grass it falls on does not
    // read as a raindrop — on a small LED the eye takes brightness long before
    // it takes hue, so the drop landed as a barely-perceptible shift in tint.
    // Lifted to ~200, which clears every green in the default biome set while
    // staying a cool blue-grey rather than going white.
    public static final RGBColor RAIN_BLUE_GRAY = RGBColor.fromHex("#AECDE8");
    public static final RGBColor LIGHTNING_WHITE_BLUE = RGBColor.fromHex("#F0F5FF");

    // ---- Sulfur caves, and the 26.2 title panorama ---------------------
    // Minecraft 26.2 added the sulfur caves, and made one the game's own title
    // panorama, so these colours carry both the biome layer and the menu theme.
    //
    // Sampled from the biome definition and from the block textures it is built
    // out of, then pushed toward saturation, which is the one adjustment this
    // palette needs almost everywhere. A keyboard mixes three LEDs behind a
    // diffuser: whatever all three channels share comes out as white light and
    // dilutes the hue, so a colour that is right on a monitor arrives pale. The
    // sampled values are given beside each one so the distance is on the record.

    /** Sulfur rock. {@code sulfur.png} averages #BDAF65, which is half white light. */
    public static final RGBColor SULFUR_ROCK = RGBColor.fromHex("#C4A917");
    /** The acid pools. The biome declares water_color #34BF89. */
    public static final RGBColor SULFUR_ACID_POOL = RGBColor.fromHex("#19D98C");
    /** Vented gas. The biome declares fog_color #8CB831, and this keeps its hue. */
    public static final RGBColor SULFUR_GAS = RGBColor.fromHex("#A6D41C");
    /** Spikes off the ceiling. {@code sulfur_spike_up_tip.png} highlights at #D5CC6D. */
    public static final RGBColor SULFUR_SPIKE = RGBColor.fromHex("#E8DC72");
    /**
     * A bubble bursting. Near-white with a yellow cast, close to the sulfur
     * cube's own #ECF1BE, and left pale rather than saturated on purpose: this
     * is the brightest thing in the scene and it has to read as a flash of
     * light instead of as another yellow.
     */
    public static final RGBColor SULFUR_POP = RGBColor.fromHex("#F2F6C8");
    /**
     * Cinnabar in the walls, the red half of the 26.2 cave palette.
     *
     * <p>The texture averages #97524E, a low-saturation brick that on hardware
     * sits beside sulfur yellow as an indeterminate brown and stops reading as
     * red at all. Pushed to a deeper, cleaner red, which is the same liberty
     * the lush-caves greens take and for the same reason — it has to stay
     * unmistakably the other side of the wheel from everything else here.
     */
    public static final RGBColor SULFUR_CINNABAR = RGBColor.fromHex("#B8352C");

    // ---- Default menu theme: sky over grass ---------------------------
    /** Daytime sky, for the default title-screen theme. */
    public static final RGBColor MENU_VANILLA_SKY = RGBColor.fromHex("#5B93E8");
    /** Grass below the horizon. Saturated for an LED, not sampled from a texture. */
    public static final RGBColor MENU_VANILLA_GRASS = RGBColor.fromHex("#4BA83C");

    // ---- Main-menu ambient theme -------------------------------------
    // The look being matched: the Forge Everything pack's title art — heavy
    // tech + magic, FTB painterly aesthetic. A Create drill contraption
    // grinding away deep in a torchlit cave with something arcane growing in
    // the crevices.
    //
    // So: a dim, warm, chiaroscuro stone base with two twinkle layers on top —
    // amber embers (drill sparks, molten metal, lava glow) and a cooler violet
    // arcane accent (the magic half of the pack).
    //
    // Explicitly NOT the clean bright "gamer RGB main menu" palette. Dark,
    // uneven, painted-looking. If it ever starts looking like a Discord theme,
    // something has gone wrong.
    public static final RGBColor MENU_STONE_BASE = RGBColor.fromHex("#241B16");
    public static final RGBColor MENU_EMBER = RGBColor.fromHex("#FF7A29");
    public static final RGBColor MENU_ARCANE = RGBColor.fromHex("#9B5FFF");

    /** Constants only. There is nothing to instantiate here. */
    private ColorPalette() {
    }
}
