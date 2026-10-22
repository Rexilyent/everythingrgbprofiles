package com.everythingrgbprofile.priority;

/**
 * The three compositing tiers. This very small enum is the entire conflict
 * resolution strategy for the whole mod, which is why it gets a real doc
 * comment despite being about eleven lines long.
 *
 * <p>The problem it solves: at any given moment a dozen effects might all want
 * the keyboard at once. You're in a swamp (ambient), it's raining (overlay),
 * you just levelled up (flash), and something is shrieking at you in the dark
 * (also a flash, considerably more urgent). "Last writer wins" gets you
 * incoherent flickering garbage. So instead every effect declares what KIND of
 * thing it is and {@link Compositor} knows how each kind is supposed to
 * behave.
 */
public enum EffectTier {
    /**
     * The opaque base layer, i.e. the wallpaper. Biome colour, menu ambient,
     * bosses, that sort of thing. One is visible at a time (two for the length
     * of a dissolve), picked by {@link EffectController#priority()}. Something
     * is essentially always active down here, because a black keyboard is not
     * a look.
     */
    TIER1_OPAQUE_BASE,
    /**
     * Translucent overlays that blend over Tier 1 instead of replacing it.
     * Some are a single key (low health), some are the whole board (rain).
     * Every active overlay draws at once, in registration order, and priority
     * is meaningless up here because nobody is competing for anything. Low
     * health, rain, the portal spiral, and so on.
     */
    TIER2_OVERLAY,
    /**
     * One-shot full-board interrupts that blank everything underneath for a
     * short, loud moment, then get out of the way. Death, lightning,
     * advancements.
     *
     * <p>"Blanks everything underneath" is the defining trait and also the
     * trap. See {@link EffectController#tier3SuppressionFloor} for the portal
     * bug it caused and what was done about it.
     */
    TIER3_MOMENTARY_FLASH
}
