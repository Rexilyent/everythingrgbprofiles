package com.everythingrgbprofile.compat.toughasnails;

import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;

import com.everythingrgbprofile.RGBProfileMod;

/**
 * Soft integration with Tough As Nails' thirst and temperature stats (the
 * {@link Reading} record also has a slot for a season, which TAN itself does
 * not track; seasons are Serene Seasons' business), gated entirely behind
 * {@link com.everythingrgbprofile.compat.ModCompatRegistry#isToughAsNailsLoaded()}.
 *
 * <h2>Where these names came from</h2>
 * Read straight out of the {@code toughasnails.api} package in
 * {@code ToughAsNails-neoforge-1.21.1-10.1.0.13.jar}, which is to say checked
 * rather than guessed. An earlier version of this class was a placeholder that
 * reflected against {@code toughasnails.api.stat.capability.IThirst}, a class
 * that does not exist in TAN 10 and was never going to, so it fell over on
 * every call and the thirst and temperature lighting sat dormant. Now it calls
 * the real thing:
 *
 * <ul>
 *   <li>{@code ThirstHelper.getThirst(player).getThirst()}, out of 20, same
 *       scale as the food bar.</li>
 *   <li>{@code TemperatureHelper.getTemperatureForPlayer(player)}, one of
 *       ICY / COLD / NEUTRAL / WARM / HOT. Only ICY and HOT actually hurt.</li>
 *   <li>{@code TemperatureHelper.getPercentHyperthermic(player)}, 0..1, how far
 *       into heatstroke you are.</li>
 * </ul>
 *
 * <p>All three are synced to the client by TAN's own packets, which is the
 * only reason any of this works in a client-only mod. TAN's own HUD calls
 * {@code getPercentHyperthermic} client-side to draw its heat vignette, so
 * this is not trusting a value that only exists on the server.
 *
 * <h2>The cold side is vanilla's, not TAN's</h2>
 * There's no "percent hypothermic" in the API, and that's not an oversight on
 * TAN's part. Being ICY makes TAN drive vanilla's own {@code ticksFrozen}
 * counter (the powder snow one) through a mixin, so the progress toward
 * freezing to death is just {@link Player#getPercentFrozen()}. Synced entity
 * data, no reflection needed, and it means sitting in powder snow counts as
 * freezing too. Which, frankly, it is.
 *
 * <h2>Why still reflection</h2>
 * The API isn't on this project's build classpath, and pulling it in means a
 * new Maven repository for one mod's soft integration. So the methods are
 * looked up once, cached, and called through reflection. If the lookup fails
 * (TAN renamed something, a future major version reshuffles the package,
 * whatever) {@link #read} returns null, logs it once, and the caller treats
 * that exactly like TAN not being installed. No crash, no log spam. If TAN
 * does break this, a {@code compileOnly} on its {@code -api} artifact is the
 * proper fix, and it does not break the "never import another mod's classes"
 * rule in ModCompatRegistry, since a public API artifact is exactly what that
 * rule allows.
 */
public final class ToughAsNailsCompat {

    /** TAN's thirst bar, like the food bar, tops out at 20. Read from ThirstData's constructor. */
    private static final double MAX_THIRST = 20.0;

    /**
     * @param thirstPercent 0..100. 100 when TAN has thirst switched off, so
     *                      nothing ever warns about a stat that isn't there.
     * @param overheating   temperature is HOT, the level where heatstroke builds
     * @param freezing      temperature is ICY, the level where frostbite builds
     * @param heatProgress  0..1, how far into heatstroke. 0 unless HOT, give or
     *                      take the ticks draining back down after you leave
     * @param coldProgress  0..1, vanilla's frozen meter. Climbs while ICY, and
     *                      in powder snow regardless
     * @param season        always null; TAN doesn't do seasons
     */
    public record Reading(double thirstPercent, boolean overheating, boolean freezing,
                          double heatProgress, double coldProgress, String season) {
    }

    // Looked up once and kept. null after a failed lookup, and `broken` says so.
    private static Method thirstEnabled;
    private static Method getThirst;
    private static Method thirstValue;
    private static Method temperatureEnabled;
    private static Method temperatureForPlayer;
    private static Method percentHyperthermic;

    private static boolean resolved = false;
    private static boolean broken = false;

    /** One-shot latch, so the failure message is logged once per session rather than once per tick. */
    private static boolean loggedFailure = false;

    /** Reads TAN's stats for a player, or null if the API couldn't be reached. */
    public static Reading read(Player player) {
        if (!resolved) resolve();
        if (broken) return null;
        try {
            double thirstPercent = 100.0;
            if ((boolean) thirstEnabled.invoke(null)) {
                Object thirst = getThirst.invoke(null, player);
                // null only while the player's attachment hasn't been set up
                // yet, i.e. the first tick or two of a login. Full, until told
                // otherwise.
                if (thirst != null) {
                    thirstPercent = Math.max(0, Math.min(100, ((int) thirstValue.invoke(thirst)) / MAX_THIRST * 100.0));
                }
            }

            boolean overheating = false;
            boolean freezing = false;
            double heatProgress = 0;
            if ((boolean) temperatureEnabled.invoke(null)) {
                Object level = temperatureForPlayer.invoke(null, player);
                // Compared by name, since the enum class itself is off limits
                // at compile time. Cheap, five constants, nobody's renaming HOT.
                String name = level instanceof Enum<?> e ? e.name() : "";
                overheating = "HOT".equals(name);
                freezing = "ICY".equals(name);
                heatProgress = clamp01((float) percentHyperthermic.invoke(null, player));
            }
            double coldProgress = clamp01(player.getPercentFrozen());

            return new Reading(thirstPercent, overheating, freezing, heatProgress, coldProgress, null);
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> thirstHelper = Class.forName("toughasnails.api.thirst.ThirstHelper");
            Class<?> thirstData = Class.forName("toughasnails.api.thirst.IThirst");
            Class<?> temperatureHelper = Class.forName("toughasnails.api.temperature.TemperatureHelper");

            thirstEnabled = thirstHelper.getMethod("isThirstEnabled");
            getThirst = thirstHelper.getMethod("getThirst", Player.class);
            thirstValue = thirstData.getMethod("getThirst");
            temperatureEnabled = temperatureHelper.getMethod("isTemperatureEnabled");
            temperatureForPlayer = temperatureHelper.getMethod("getTemperatureForPlayer", Player.class);
            percentHyperthermic = temperatureHelper.getMethod("getPercentHyperthermic", Player.class);
        } catch (Throwable t) {
            // Throwable rather than Exception, and that matters: reflection
            // failures include NoClassDefFoundError and friends, which are
            // Errors, not Exceptions. Catching only Exception here would let
            // the exact failure mode this class exists to survive walk
            // straight out into the tick loop.
            fail(t);
        }
    }

    /** Switches the integration off for the rest of the session, and says so exactly once. */
    private static void fail(Throwable t) {
        broken = true;
        if (!loggedFailure) {
            loggedFailure = true;
            RGBProfileMod.LOGGER.warn("RGB Profile: Tough As Nails is installed but its API didn't answer the way "
                    + "10.1 does ({}). Thirst and temperature lighting are off for this session.", t.toString());
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private ToughAsNailsCompat() {
    }
}
