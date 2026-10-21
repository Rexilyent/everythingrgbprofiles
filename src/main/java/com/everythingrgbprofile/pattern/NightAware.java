package com.everythingrgbprofile.pattern;

/**
 * A pattern that wants to know whether it's night. Optional, and nothing
 * else in the package needs it: a pattern that doesn't implement this never
 * hears about the time of day, which is how every other scene here works
 * and how they'll keep working.
 *
 * <p>Pushed in rather than read out of the game, because patterns run on the
 * worker thread and are meant to be pure functions of their own clock. The
 * client thread works out "is it night" (see
 * {@code ClientEventHandlers.pollBiomeNight}), and
 * {@code BiomeColorEffect.setNight} hands it to whichever patterns care.
 *
 * <p>"Night" means what the Pale Garden means by it, which is not quite the
 * clock. See pollBiomeNight for why a thunderstorm at noon counts.
 */
public interface NightAware {

    /**
     * Called on the worker thread, once a second or so, and again with the
     * current value whenever a fresh instance is built. Repeating the same
     * answer is normal and must be cheap: implementations act on a change,
     * not on the call.
     */
    void setNight(boolean night);
}
