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
 * <h2>⚠ THIS DOES NOT WORK YET, AND THAT IS ON PURPOSE</h2>
 * Read this before filing it as broken, because it is <i>knowingly</i>
 * unfinished. Wiring it up properly means confirming the exact public API
 * method names against TAN's own {@code -api} artifact instead of guessing at
 * them, and nobody has done that yet.
 *
 * <p>That artifact isn't on this project's build classpath, so writing real
 * calls would have meant <b>inventing method names and hoping for the best</b>.
 * What's here instead: reflection against a best-guess API shape, wrapped so
 * that any mismatch at all (wrong class, wrong method, a completely different
 * capability model) fails safely. {@link #read} returns null, logs once, and
 * the caller treats that identically to "TAN isn't installed". Dormant, no
 * crash, no log spam.
 *
 * <p>Being loudly unfinished beats being quietly wrong. A guessed
 * implementation that half-works generates bug reports nobody can reproduce.
 * A placeholder that says "I am a placeholder" in the log generates a
 * five-minute fix whenever somebody finally gets round to it.
 *
 * <h2>How to actually finish this</h2>
 * Add TAN's {@code -api} artifact as a {@code compileOnly} Gradle dependency
 * and replace the reflection below with real compile-time calls. The
 * reflection is here ONLY because that artifact has not been added to the
 * build yet, not because it's the recommended approach — it isn't.
 *
 * <p>And to be clear, {@code compileOnly} on the API artifact does NOT
 * break this mod's "never import another mod's classes" rule. A public
 * {@code -api} artifact, as distinct from TAN's main mod jar, is precisely
 * the sanctioned pattern for exactly this kind of soft integration.
 */
public final class ToughAsNailsCompat {

    /** One-shot latch, so the failure message is logged once per session rather than once per tick. */
    private static boolean loggedFailure = false;

    public record Reading(double thirstPercent, boolean overheating, boolean freezing, String season) {
    }

    /**
     * Reads TAN's stats for a player, or null if unavailable.
     *
     * <p>Currently: always null. Yes, always. See the class doc, it's on
     * purpose.
     */
    public static Reading read(Player player) {
        try {
            // TODO: replace with real TAN API calls once the -api artifact is
            // on the build classpath (see the class doc). The class name below
            // was a best guess and IS wrong: TAN 10.1 for 1.21.1 has no
            // toughasnails.api.stat package. What it actually ships is
            // toughasnails.api.thirst.IThirst with ThirstHelper, and
            // toughasnails.api.temperature.TemperatureHelper. Either way this
            // falls into the catch rather than into a plausible-looking wrong
            // answer.
            Class<?> capabilityClass = Class.forName("toughasnails.api.stat.capability.IThirst");
            // The capability lookup and the method invocation would go here,
            // against the real API. Until that shape is confirmed, throw
            // immediately, so there is exactly one code path through this
            // method and it is the safe one.
            throw new UnsupportedOperationException("TAN API wiring not yet confirmed, see this class's doc comment");
        } catch (Throwable t) {
            // Throwable rather than Exception, and that matters: reflection
            // failures include NoClassDefFoundError and friends, which are
            // Errors, not Exceptions. Catching only Exception here would let
            // the exact failure mode this method exists to survive walk
            // straight out into the tick loop.
            if (!loggedFailure) {
                RGBProfileMod.LOGGER.info("RGB Profile: Tough As Nails detected but its stat API isn't wired up yet " +
                        "(placeholder integration, see ToughAsNailsCompat's class doc) — thirst/temperature effects staying dormant.");
                loggedFailure = true;
            }
            return null;
        }
    }

    private ToughAsNailsCompat() {
    }
}
