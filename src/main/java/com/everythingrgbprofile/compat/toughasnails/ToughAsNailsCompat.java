package com.everythingrgbprofile.compat.toughasnails;

import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;

import com.everythingrgbprofile.RGBProfileMod;

/**
 * Soft integration with Tough As Nails' thirst/temperature/season stats,
 * gated entirely behind
 * {@link com.everythingrgbprofile.compat.ModCompatRegistry#isToughAsNailsLoaded()}.
 *
 * <h2>⚠ THIS DOES NOT WORK YET, AND THAT IS DELIBERATE</h2>
 * Read this before assuming it's broken: it is <i>knowingly</i> unfinished.
 * Wiring it properly means confirming the exact public API method names
 * against TAN's own {@code -api} artifact rather than guessing, and that
 * hasn't been done.
 *
 * <p>That artifact is not on this project's build classpath yet, so writing
 * real calls would have meant <b>inventing method names and hoping</b>. The
 * alternative chosen here: reflection against a best-guess API shape, wrapped
 * so that any mismatch — wrong class, wrong method, entirely different
 * capability model — fails safely. {@link #read} returns null, logs once, and
 * the caller treats it exactly like "TAN isn't installed". Dormant. No crash.
 * No log spam.
 *
 * <p>Being visibly and loudly unfinished beats being subtly wrong. A guessed
 * implementation that half-works produces bug reports nobody can reproduce; a
 * placeholder that says "I am a placeholder" in the log produces a five-minute
 * fix whenever someone gets round to it.
 *
 * <h2>How to actually finish this</h2>
 * Add TAN's {@code -api} artifact as a {@code compileOnly} Gradle dependency
 * and replace the reflection below with real compile-time calls. The
 * reflection is here ONLY because that artifact has not been added to the
 * build yet, not because it's the recommended approach — it isn't.
 *
 * <p>And to be clear, {@code compileOnly} on the API artifact does NOT break
 * this mod's "never import another mod's classes" rule. A public {@code -api}
 * artifact (as distinct from TAN's main mod jar) is precisely the sanctioned
 * pattern for this kind of soft integration.
 */
public final class ToughAsNailsCompat {

    /** One-shot latch: the failure message is logged once per session, not once per tick. */
    private static boolean loggedFailure = false;

    public record Reading(double thirstPercent, boolean overheating, boolean freezing, String season) {
    }

    /**
     * Reads TAN's stats for a player, or null if unavailable.
     *
     * <p>Currently: always null. See the class doc. It's on purpose.
     */
    public static Reading read(Player player) {
        try {
            // TODO: replace with real TAN API calls once the -api artifact is
            // on the build classpath (see the class doc). The class name below
            // is a best guess and may well be wrong, which is exactly why this
            // is structured to fail into the catch rather than into a
            // plausible-looking wrong answer.
            Class<?> capabilityClass = Class.forName("toughasnails.api.stat.capability.IThirst");
            // The capability lookup and method invocation would go here,
            // against the real API. Until that's confirmed, we throw
            // immediately so there is precisely one code path and it is the
            // safe one.
            throw new UnsupportedOperationException("TAN API wiring not yet confirmed, see this class's doc comment");
        } catch (Throwable t) {
            // Throwable, not Exception: reflection failures include
            // NoClassDefFoundError and friends, which are Errors. Catching only
            // Exception here would let exactly the failure mode this method
            // exists to survive escape into the tick loop.
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
