package com.everythingrgbprofile.priority;

/**
 * The three compositing tiers. This tiny enum is the entire conflict
 * resolution strategy for the mod, which is why it gets a real doc comment
 * despite being eleven lines.
 *
 * <p>The problem it solves: at any moment a dozen effects might all want the
 * keyboard. You're in a swamp (ambient), it's raining (overlay), you just
 * levelled up (flash), and something is shrieking at you in the dark
 * (also flash, considerably more urgent). "Last writer wins" produces
 * incoherent flickering garbage. So instead every effect declares what KIND
 * of thing it is, and {@link Compositor} knows how each kind behaves.
 */
public enum EffectTier {
    /**
     * The opaque base layer — the wallpaper. Biome colour, menu ambient, that
     * sort of thing. Exactly one is visible at a time; ties are broken by
     * {@link EffectController#priority()}. Something is essentially always
     * active here, because a black keyboard is not a look.
     */
    TIER1_OPAQUE_BASE,
    /**
     * Translucent overlays that stay in their own lane — their own keys or
     * zone — and blend over Tier 1 rather than replacing it. All active
     * overlays draw simultaneously; priority is meaningless here because
     * nobody is competing. Low health, rain, the portal spiral, etc.
     */
    TIER2_OVERLAY,
    /**
     * One-shot full-board interrupts that blank everything underneath for a
     * short, loud moment, then get out of the way. Death, lightning,
     * advancements.
     *
     * <p>"Blanks everything underneath" is the defining trait and also the
     * trap — see {@link EffectController#tier3SuppressionFloor} for the
     * portal bug it caused and how it was fixed.
     */
    TIER3_MOMENTARY_FLASH
}
