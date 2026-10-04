package com.everythingrgbprofile.color;

/**
 * Every named colour in the mod, in one file, so that no effect anywhere ever
 * has a raw hex literal sitting in it.
 *
 * <h2>Why bother, they're just colours</h2>
 * Because inline hex is how you wake up with four subtly different reds spread
 * across three files and no way to tell which one was supposed to be "the"
 * health red. And because the groupings below are load-bearing design
 * decisions rather than decoration: the entire premise of this mod is that you
 * can glance at your keyboard and know what just happened <i>without reading
 * anything</i>. That only works if related events look related and unrelated
 * events genuinely don't.
 *
 * <p>These are the colours that are deliberately <b>not</b> user-configurable —
 * internal pattern defaults and family anchors. Anything a user is meant to
 * tweak lives in {@link com.everythingrgbprofile.config.RGBProfileConfig}
 * instead, and the values here mirror those defaults.
 *
 * <h2>Why half of these look oversaturated on a monitor</h2>
 * Because a keyboard is not a monitor, and colours picked on one kept arriving
 * muddy or washed out on the actual board. The reason is physical rather than
 * aesthetic: each key is a red, a green and a blue LED sitting behind one
 * diffuser, mixing additively. Whatever all three channels have in common,
 * i.e. {@code min(r,g,b)}, comes out as white light and dilutes the hue. A
 * sand colour like {@code #DBCFA3} is about three-quarters white, which on a
 * monitor is sand and on a keycap is a white key with a faint opinion.
 *
 * <p>Two rules fell out of tuning these on the real hardware, and most of the
 * history comments in this file are one of those two being learned the hard
 * way:
 *
 * <ul>
 *   <li><b>Keep saturation high and vary brightness instead.</b> Making a
 *       colour "lighter" the way you would with paint raises all three
 *       channels, which is precisely the white that washes it out. On an LED,
 *       brighter means more of the dominant channel, not more of the other
 *       two.</li>
 *   <li><b>Judge brightness by the strongest channel, not by luminance.</b> An
 *       LED is as bright as its hardest-driven die. Luminance weights green
 *       heavily and blue barely at all, so a deep blue can score as nearly
 *       black while the key is plainly lit, and two colours with equal
 *       luminance can look nothing alike on the board.</li>
 * </ul>
 *
 * <p>Dark colours have the opposite problem. Below roughly a tenth of full
 * drive an LED stops reading as dim and starts reading as off, which is a much
 * worse thing for it to say. Several entries below got lifted for exactly that
 * reason, and each one says so.
 */
public final class ColorPalette {

    // ---- Status / warning family -------------------------------------
    // Every one of these has to be identifiable in peripheral vision while you
    // are extremely busy not dying. Hue alone doesn't do that, and isn't asked
    // to: health (0 degrees), overheating (16) and hunger (39) are all warm,
    // and thirst (188) and freezing (193) are nearly the same cyan. What keeps
    // them apart is that each one lights its own key (H, F, T and K by
    // default, temperature sharing K between hot and cold), health pulses
    // fast where the rest pulse slow, and freezing is far paler than thirst.
    // Move two of them onto the same key and the hues will not save you.
    public static final RGBColor HEALTH_RED = RGBColor.fromHex("#FF1E1E");
    public static final RGBColor HUNGER_AMBER = RGBColor.fromHex("#FFA500");
    public static final RGBColor THIRST_CYAN = RGBColor.fromHex("#3FBFD4");
    public static final RGBColor OVERHEATING_ORANGE = RGBColor.fromHex("#FF6B35");
    public static final RGBColor FREEZING_ICE = RGBColor.fromHex("#A8E0F0");

    // ---- Sculk family ------------------------------------------------
    // These are meant to read as ONE escalating thing rather than four
    // unrelated events: ambient glint, then "something heard you", then
    // "something is actively shrieking about it", then Warden. Same teal
    // family the whole way up, increasing intensity. You are supposed to feel
    // your stomach drop as it climbs, which is a weirdly specific design goal
    // for a keyboard, and yet here we are.
    //
    // SCULK_AMBIENT is Warden presence as a WASH colour, not a twinkle
    // colour. The old #10182B sat at luminance 24, which is indistinguishable
    // from an unlit key, and the effect using it drew nothing except twinkles.
    // So a Warden walking up on you replaced the entire biome layer with a
    // black board and two nearly-black dots. It read as the mod crashing at
    // the exact moment the game got interesting, which is impressively the
    // worst possible timing. Lifted to a dim teal that still says "cave" while
    // actually emitting light.
    public static final RGBColor SCULK_AMBIENT = RGBColor.fromHex("#0C2A33");
    /** The glints sitting over that wash: the Warden's own chest-glow cyan. */
    public static final RGBColor SCULK_WARDEN_GLINT = RGBColor.fromHex("#24C8B8");
    public static final RGBColor SCULK_SENSOR_PING = RGBColor.fromHex("#4FC3C3");
    public static final RGBColor SCULK_SHRIEKER_BASE = RGBColor.fromHex("#1F8C7A");
    /**
     * Peak escalation, and note that it is now BRIGHTER than the base instead
     * of darker. The ramp used to run from #1F8C7A (luminance 116) toward
     * #08211C (luminance 27), which meant the more shrieks you racked up the
     * closer the alert crept to invisible. The single most dangerous state in
     * the game, rendered as an almost completely unlit board. An escalating
     * warning has to actually escalate.
     */
    public static final RGBColor SCULK_SHRIEKER_PEAK = RGBColor.fromHex("#5FF5DC");
    /**
     * Warden emergence. This used to be a Tier 3 flash, and Tier 3 <b>blanks
     * the entire board</b> before it draws anything, so painting it in the old
     * #08211C (luminance 27) meant that a Warden clawing its way out of the
     * floor switched your keyboard off. It's a Tier 2 overlay now, but the
     * colour change stuck regardless: the Warden's sonic-boom cyan, which is
     * what that moment genuinely looks like in game.
     */
    public static final RGBColor WARDEN_EMERGENCE = RGBColor.fromHex("#2EE0C8");

    // ---- Progression family ------------------------------------------
    public static final RGBColor LEVEL_UP_GOLD = RGBColor.fromHex("#FFD700");
    /** The experience bar filling in the level-up animation: the green of the game's own bar. */
    public static final RGBColor LEVEL_UP_XP_GREEN = RGBColor.fromHex("#80FF20");
    // Nudged off pure cyan on purpose. At #00FFFF this lands right on top of
    // the sculk sensor ping, and "you earned an advancement" reading as
    // "something in the dark just noticed you" is a genuinely bad time for
    // everyone involved.
    public static final RGBColor ADVANCEMENT_CYAN = RGBColor.fromHex("#00E5FF");

    // ---- One-offs ----------------------------------------------------
    /** The momentary flash on death. Red rather than white, because you did not win anything. */
    public static final RGBColor DEATH_FLASH_RED = RGBColor.fromHex("#FF1A1A");
    /** The board soaked through, underneath the drips. Dark enough to read as "off, but wrong". */
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
     * Skull and spine. Charcoal instead of the near-black the mob actually is,
     * because an LED at luminance 20 is indistinguishable from an LED that is
     * switched off, and a boss you cannot see is not atmospheric. It's broken.
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
     * <p>Prismarine teal, lifted well clear of the near-black that deep ocean
     * water genuinely is, for the same reason as {@link #WITHER_BONE}. This
     * colour carries the entire silhouette, so it has to be visible as a shape
     * rather than as a vague suggestion that a shape might be nearby.
     */
    public static final RGBColor GUARDIAN_PRISMARINE = RGBColor.fromHex("#2E8F87");
    /** The eye: warm amber against all that teal, which is the only reason it reads as an eye. */
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
     * The Slider's stone, and the dungeon floor underneath it. A cool grey
     * instead of the flat #7F7F7F its texture actually uses, so that the body
     * stays visually separate from the near-white flash of a hit landing on
     * it.
     */
    public static final RGBColor SLIDER_STONE = RGBColor.fromHex("#6F7B8C");
    /** The runes on its faces while it is awake, from the blue of its glow texture. */
    public static final RGBColor SLIDER_RUNE = RGBColor.fromHex("#3A86FF");
    /** The same runes below a quarter health, where the texture turns red-orange. */
    public static final RGBColor SLIDER_CRITICAL = RGBColor.fromHex("#FF3308");
    /**
     * The marker for where you are standing in the room. Gold, purely because
     * nothing else on the board is: it has to read as a different KIND of
     * thing from the stone and the runes at a glance, given that it represents
     * the thing the cube is actively hunting.
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
     * Where you are. Deliberately not the Slider's gold, which would vanish
     * completely into a room that is already gold and orange in every
     * direction. Green is the one hue nothing else in this fight touches.
     */
    public static final RGBColor SUN_SPIRIT_PLAYER = RGBColor.fromHex("#6DFF8A");
    /** What its death drains the board to: the Aether's eternal day finally ending. */
    public static final RGBColor SUN_SPIRIT_DUSK = RGBColor.fromHex("#1E2E78");

    /**
     * The Valkyrie Queen's wings and armour. Her texture is a blue-tinted
     * white, and this keeps the tint plus some of the pallor, so she reads as
     * silver on the keys instead of as the pure white that means a hit just
     * landed.
     */
    public static final RGBColor VALKYRIE_QUEEN_SILVER = RGBColor.fromHex("#B8C4FF");
    /**
     * Her room's light — the Silver Dungeon is lit by ambrosium torches and
     * glowstone — and the room opening when she is beaten.
     */
    public static final RGBColor VALKYRIE_QUEEN_GOLD = RGBColor.fromHex("#FFB12E");
    /**
     * Thunder crystals, and the lightning they eventually turn into. The
     * crystal's texture is a pale electric aqua, deepened here to cyan so that
     * it cannot possibly be mistaken for her silver.
     */
    public static final RGBColor VALKYRIE_QUEEN_LIGHTNING = RGBColor.fromHex("#1FD8FF");
    /** Where you are. The same green as in the Sun Spirit's room, and for the same reason: nothing else here is green. */
    public static final RGBColor VALKYRIE_QUEEN_PLAYER = RGBColor.fromHex("#6DFF8A");

    // --- The End -------------------------------------------------------
    /** The void between the islands: the resting colour of the whole sequence. */
    public static final RGBColor END_VOID_PURPLE = RGBColor.fromHex("#5B2C8F");
    /** Dragon breath and crystal beams — the bright half of the End's palette. */
    public static final RGBColor END_DRAGON_MAGENTA = RGBColor.fromHex("#D34DFF");
    /**
     * Perched on the portal: cooler and calmer, the window where you can
     * actually reach its head.
     *
     * <p>Currently wired up to nothing. {@code EnderDragonEffect} does handle
     * the PERCHED pose, but it only changes the silhouette's shape and glow
     * there and keeps whatever accent colour the fight is already using, so
     * this cooler blue never reaches the board. Either wire it into that case
     * or delete it, but don't leave it here looking employed.
     */
    public static final RGBColor END_PERCHED = RGBColor.fromHex("#3E7BD6");
    /** The fireball, and the flame it sits and breathes. */
    public static final RGBColor END_DRAGON_FLAME = RGBColor.fromHex("#FF5FD2");
    /** Dragon's breath on the floor: deep purple at the flame tips. */
    public static final RGBColor END_BREATH_FIRE = RGBColor.fromHex("#8B2FE0");
    /** Deep water, at the bottom of a flooded board. Dense enough to hide the biome. */
    public static final RGBColor DROWNING_DEEP = RGBColor.fromHex("#0E3FA8");
    /** The waterline catching light — paler and brighter than what is under it. */
    public static final RGBColor DROWNING_SURFACE = RGBColor.fromHex("#5FC8E8");
    /** The base of the flames when you are burning: the hottest part, nearly yellow. */
    public static final RGBColor BURNING_CORE = RGBColor.fromHex("#FFB01F");
    /** The tips of the flames, where the fire cools to red. */
    public static final RGBColor BURNING_TIP = RGBColor.fromHex("#E8350C");

    // Rain has to stay legible on top of whatever biome is underneath it, and
    // the biome it was losing to is a bright one. The earlier #7A9BB5 had a
    // relative luminance around 150, while plains (#91BD59) hit 172 at the
    // top of the shimmer it used to run. A raindrop DARKER than the grass it is falling on
    // does not read as a raindrop at all, because on a small LED the eye
    // registers brightness long before it registers hue, so the drop showed up
    // as a barely-perceptible shift in tint and nothing more. Lifted to around
    // 200, which clears every green in the default biome set while staying a
    // cool blue-grey instead of going white.
    public static final RGBColor RAIN_BLUE_GRAY = RGBColor.fromHex("#AECDE8");
    public static final RGBColor LIGHTNING_WHITE_BLUE = RGBColor.fromHex("#F0F5FF");

		// ---- Badlands ------------------------------------------------------
    // The strata ladder itself is deliberately not in this file, because it
    // doesn't need to be: both ends of it are the profile's own colour scaled
    // and lightened by the same 0.52, which is a measured relationship rather
    // than a number somebody picked. See StrataPattern for the six per-channel
    // figures that average out to it.
    //
    // The two below are the colours that ladder cannot generate on its own,
    // for the simple reason that neither of them is terracotta.

    /**
     * Gold catching the light in an exposed face.
     *
     * <p>{@code gold_block.png} averages out to #F6D03D at hue 47. Saturated
     * for the hardware and pinned at hue 47, which is the one number in this
     * entry that actually matters: the {@code yellow_terracotta} band sits at
     * 38, and the moment the gold slides down toward it the glints stop
     * reading as gold and turn into a bright patch of wall.
     */
    public static final RGBColor BADLANDS_GOLD = RGBColor.fromHex("#FFC814");
    /**
     * Oak on a badlands rim.
     *
     * <p>Lifted and pushed toward olive from the #59492B the game actually
     * renders. The full reasoning lives on {@code StrataPattern.woodedBadlands},
     * which is the only thing that ever draws this.
     */
    public static final RGBColor BADLANDS_CANOPY = RGBColor.fromHex("#8E9A2B");

    // ---- Beach: the sea end of it -------------------------------------
    // The sand itself comes from the biome profile, same as the desert's
    // does. These two are the part a profile has no way to supply, because
    // one entry only carries a colour and an accent and a shore needs three.
    /**
     * Shallow water over sand, lit from above.
     *
     * <p>Deliberately not the {@code #3F76E4} the game tints beach water.
     * That is the colour of deep water seen from a distance; what you are
     * actually looking at in the swash zone is a few inches of it over pale
     * sand, which goes turquoise. It also has to sit well clear of
     * {@link #DROWNING_DEEP}, since being at the seaside and being underwater
     * should never look like the same event.
     */
    public static final RGBColor SHORE_SHALLOWS = RGBColor.fromHex("#1E9BD8");
    /**
     * Foam at the waterline.
     *
     * <p>Tinted aqua rather than left white. Foam genuinely is close to white
     * and it is also the brightest thing this pattern draws, which is the
     * exact combination that turns an LED into a plain bright key with no
     * character. Keeping some blue in it means it still reads as part of the
     * water at full brightness.
     */
    public static final RGBColor SHORE_FOAM = RGBColor.fromHex("#A8ECF5");
    /**
     * Water against a stony shore. Deeper and bluer than the shallows over
     * sand, because rock drops away fast and there's no pale bottom under it
     * to turn the water turquoise.
     */
    public static final RGBColor STONY_WATER = RGBColor.fromHex("#1866C8");

    // ---- Ocean: the things growing in it ------------------------------
    // Same problem as the beach. The water and the light on it come from the
    // biome profile, and everything else has nowhere to live but here.
    /**
     * Kelp. Pushed a long way greener than the murky olive-brown the game
     * draws it in, because murky olive-brown at LED brightness is "a key that
     * is slightly off" and nobody's brain fills in "kelp" from that.
     */
    public static final RGBColor KELP = RGBColor.fromHex("#3E9C1A");
    /** Brain coral, the pink one. */
    public static final RGBColor CORAL_BRAIN = RGBColor.fromHex("#FF4FA8");
    /** Bubble coral, the purple one. */
    public static final RGBColor CORAL_BUBBLE = RGBColor.fromHex("#B42CEB");
    /** Fire coral, the red one. */
    public static final RGBColor CORAL_FIRE = RGBColor.fromHex("#FF2A1A");
    /** Horn coral, the yellow one. */
    public static final RGBColor CORAL_HORN = RGBColor.fromHex("#FFD21F");

    // ---- Cold biomes: mountains, snowfields, snowy forest -------------
    // The one rule all of these follow: snow is never grey and never white.
    // Snow in shade is blue, snow in sun is warm, and a white key on a
    // keyboard just reads as "a light is on". Grey is worse. Grey is a light
    // that's on and also a bit sad about it.
    /** High-altitude sky. Thin air, deep blue, a lot more saturated than the sky you get at sea level. */
    public static final RGBColor ALPINE_SKY = RGBColor.fromHex("#2A5CE8");
    /**
     * Bare rock under the snow. Slate pushed toward violet, because neutral
     * slate grey on an LED is the exact greyscale this whole family exists
     * to get away from.
     */
    public static final RGBColor ALPINE_ROCK = RGBColor.fromHex("#3E3A78");
    /** Snow in shadow. Periwinkle, which is what snow in shadow actually is, ask any painter. */
    public static final RGBColor SNOW_SHADE = RGBColor.fromHex("#5C78FF");
    /** Blown snow: spindrift off a summit, a ground blizzard. Pale, icy, and still not white. */
    public static final RGBColor SNOW_PLUME = RGBColor.fromHex("#B4E0FF");
    /** One crystal catching the sun. The only thing in this family allowed this close to white, and only for a moment. */
    public static final RGBColor SNOW_SPARKLE = RGBColor.fromHex("#E2F4FF");
    /**
     * Snow falling during a snowstorm, the weather overlay. Default only; the
     * config's {@code snowColor} wins. Same reasoning as the rest of the
     * section, tinted blue so it doesn't read as the keyboard glitching white.
     */
    public static final RGBColor SNOWFALL = RGBColor.fromHex("#CFE9FF");

    // ---- Taiga: the snowy forest's engine with the snow off ------------
    /** Moss and ferns on the forest floor. Warmer and yellower than the spruces, or the trunks sink into it. */
    public static final RGBColor TAIGA_FLOOR = RGBColor.fromHex("#5A8A1C");
    /** Ground mist between the trunks. Pale blue, because grey mist on an LED is just a dim key. */
    public static final RGBColor TAIGA_MIST = RGBColor.fromHex("#8FB8E8");
    /** Fox. Red-orange, pushed well clear of the floor's green so it pops even at the edges. */
    public static final RGBColor FOX = RGBColor.fromHex("#FF5A0A");
    /** The white tip of a fox's tail. Cream rather than white, for the usual reason. */
    public static final RGBColor FOX_TAIL_TIP = RGBColor.fromHex("#FFE8C0");

    // ---- Snowy beach: the frozen version of the beach's sea end --------
    /** Near-freezing water. Deep steel blue, dark enough that the ice glaze and the snow stand clear of it. */
    public static final RGBColor FROZEN_SHALLOWS = RGBColor.fromHex("#1A4AB0");
    /** Foam on cold water. Paler and icier than {@link #SHORE_FOAM}. */
    public static final RGBColor FROZEN_FOAM = RGBColor.fromHex("#BCE6FF");
    /** Sand with the snow just washed off it. Cold violet-brown; plain dark brown here reads as mud. */
    public static final RGBColor FROZEN_WET_SAND = RGBColor.fromHex("#5A4C86");
    /** The ice glaze the swash zone freezes into while it dries. */
    public static final RGBColor ICE_GLAZE = RGBColor.fromHex("#6FE2FF");
    /** Slush floating in the shallows. */
    public static final RGBColor SLUSH = RGBColor.fromHex("#A6DCFF");

    // ---- Savanna --------------------------------------------------------
    // The grass and the light on it come from the profile. The sky and the
    // acacias are fixed, because a profile carries two colours and an acacia
    // against a hot sky needs four.
    /** The sky at the horizon on a hot afternoon. Pale gold, and the brightest thing in the scene. */
    public static final RGBColor SAVANNA_HAZE = RGBColor.fromHex("#FFAE38");
    /** The sky overhead. Burnt orange, still warm all the way up. See SavannaPattern for why there's no blue. */
    public static final RGBColor SAVANNA_SKY_HIGH = RGBColor.fromHex("#E2581A");
    /** Acacia leaves. Dark olive, the one cool-ish thing on a board that's otherwise all heat. */
    public static final RGBColor ACACIA_LEAVES = RGBColor.fromHex("#456E10");
    /** Acacia bark. Dark and warm, so the trunks read as silhouettes against the gold. */
    public static final RGBColor ACACIA_BARK = RGBColor.fromHex("#4A2A10");

    // ---- Plains and meadow ---------------------------------------------
    // Grass and sun come from the profile. Everything below is what grows in
    // it, plus the colour a cloud turns it. All pushed to full saturation,
    // because a flower is one key and has to win against a whole field of
    // green with nothing but its hue.
    /** Grass under a cloud. Deep and cool, since shade on grass goes blue-green rather than just darker. */
    public static final RGBColor GRASS_SHADE = RGBColor.fromHex("#145A3C");
    public static final RGBColor DANDELION = RGBColor.fromHex("#FFD21A");
    public static final RGBColor POPPY = RGBColor.fromHex("#FF2A14");
    /** Azure bluet. Nearly white in the game, and on an LED white is just "bright", so this leans on the "azure". */
    public static final RGBColor AZURE_BLUET = RGBColor.fromHex("#B8CCFF");
    public static final RGBColor CORNFLOWER = RGBColor.fromHex("#3C5BFF");
    public static final RGBColor ALLIUM = RGBColor.fromHex("#B44DFF");
    /** Bees. Orange rather than yellow, so a bee sitting on a dandelion is still two colours. */
    public static final RGBColor BEE = RGBColor.fromHex("#FF9A00");

    // ---- Dripstone caves ----------------------------------------------
    /** The air in the cave, behind the dripstone. Dark and warm, never black. */
    public static final RGBColor CAVE_DARK = RGBColor.fromHex("#3A2010");
    /** The one stalactite dripping lava instead of water. */
    public static final RGBColor DRIPSTONE_LAVA = RGBColor.fromHex("#FF6A12");

    // ---- The Nether ---------------------------------------------------
    // Profiles supply each biome's main colour and its light. These are the
    // rest: the second and third materials every scene needs and a profile
    // entry has no room for.
    /** Glowstone. Warm yellow, the brightest thing in the wastes. */
    public static final RGBColor GLOWSTONE = RGBColor.fromHex("#FFC23A");
    /** Lava where the surface has crusted over. Deep red, so the bright churn has something to churn against. */
    public static final RGBColor LAVA_CRUST = RGBColor.fromHex("#B01E00");
    /** A lava pop and the spark it throws. Hotter and yellower than the lava it came out of. */
    public static final RGBColor LAVA_SPARK = RGBColor.fromHex("#FFC040");
    /** Crimson stem. Purple-red, darker than the cap, same as the block. */
    public static final RGBColor CRIMSON_STEM = RGBColor.fromHex("#6E1E48");
    /** Weeping vines. Brighter red than the cap they hang off, so they read against it. */
    public static final RGBColor CRIMSON_VINE = RGBColor.fromHex("#E0203A");
    public static final RGBColor CRIMSON_SPORE = RGBColor.fromHex("#FF4A4A");
    /** Warped stem. Teal fading into blue, with the purple-grey of the bark pushed out because it reads as mud. */
    public static final RGBColor WARPED_STEM = RGBColor.fromHex("#1E5E78");
    /** Twisting vines. Bright cyan-green, the most saturated thing in the forest. */
    public static final RGBColor WARPED_VINE = RGBColor.fromHex("#1FE0B0");
    public static final RGBColor WARPED_SPORE = RGBColor.fromHex("#5FFFE0");
    /** Enderman eyes. */
    public static final RGBColor ENDER_EYE = RGBColor.fromHex("#E07BFF");
    /** The purple burst an enderman leaves when it teleports. */
    public static final RGBColor ENDER_PARTICLE = RGBColor.fromHex("#9A3DFF");
    /** Smoke hanging between the basalt columns. Violet-grey; plain grey is the one colour this whole mod is allergic to. */
    public static final RGBColor BASALT_SMOKE = RGBColor.fromHex("#4A3E66");
    /** The floor between the magma patches. */
    public static final RGBColor BLACKSTONE = RGBColor.fromHex("#2E2238");
    /** Falling ash. Pale with a violet cast, and only ever drawn dim. */
    public static final RGBColor BASALT_ASH = RGBColor.fromHex("#D6CCF0");

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
    // These belong to the mineshaft menu theme, which is NOT the default. See
    // menuTheme.style in the config: it ships as "vanilla", and this palette
    // only gets used on "mineshaft" or on "auto" in a pack that matches.
    //
    // The look being matched is the Forge Everything pack's title art: heavy
    // tech plus magic, FTB painterly aesthetic, a Create drill contraption
    // grinding away deep in a torchlit cave with something arcane growing in
    // the crevices.
    //
    // Which comes out as a dim, warm, chiaroscuro stone base with two twinkle
    // layers over it: amber embers for the drill sparks and molten metal and
    // lava glow, and a cooler violet arcane accent for the magic half of the
    // pack.
    //
    // Explicitly NOT the clean bright "gamer RGB main menu" palette. Dark,
    // uneven, painted-looking. If this ever starts looking like a Discord
    // theme, something has gone badly wrong.
    public static final RGBColor MENU_STONE_BASE = RGBColor.fromHex("#241B16");
    public static final RGBColor MENU_EMBER = RGBColor.fromHex("#FF7A29");
    public static final RGBColor MENU_ARCANE = RGBColor.fromHex("#9B5FFF");

    /** Constants only. There is nothing here worth instantiating. */
    private ColorPalette() {
    }
}
