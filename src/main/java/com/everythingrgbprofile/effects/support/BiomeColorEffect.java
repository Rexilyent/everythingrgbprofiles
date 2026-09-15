package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.debug.Diagnostics;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternFactory;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.FadePattern;
import com.everythingrgbprofile.pattern.patterns.ShimmerPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;
import com.everythingrgbprofile.profile.BiomeProfile;
import com.everythingrgbprofile.profile.ProfileResolver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Biome Color — the resting state. This is what your keyboard is doing
 * whenever nothing more interesting is happening, which is most of the time,
 * which is exactly why it has to be right.
 *
 * <p>Always animated, never a solid fill. Resolved exact -> wildcard ->
 * derived via {@link ProfileResolver}, then crossfaded on a biome change over
 * {@code biomeCrossfadeMillis}.
 *
 * <h2>Four rules that make a transition look like a transition</h2>
 * Walking from a forest into a swamp used to produce a visible lurch. It was
 * four unrelated things stacked, and each one has a rule attached that the
 * code below refers to by name. Break any of them and the lurch comes back.
 *
 * <p><b>Rule 1 — the outgoing pattern keeps its own clock.</b> The naive
 * crossfade renders the outgoing pattern with
 * {@code nowMillis - transitionStartMillis}, which hands it an elapsed time of
 * <i>zero</i> at the exact instant the fade starts. Shimmer is
 * {@code sin(2*pi*f*jitter*t + phase)}, so snapping t from 47 seconds back to
 * 0 teleports every key to an unrelated point on its own curve. A hard jolt,
 * delivered precisely when the transition is meant to be smoothest. So the
 * outgoing pattern is rendered against {@code previousPatternStartMillis}, its
 * own start time, and never notices the fade is happening.
 *
 * <p><b>Rule 2 — same pattern means keep the instance.</b> Building a fresh
 * {@code ShimmerPattern} per biome change rolls brand-new random per-key
 * phases, and crossfading two independently-phased noise fields reads as churn
 * rather than as a transition, because there is no correspondence between them
 * to interpolate. The realisation that fixes it: a biome transition is a
 * crossfade of <b>colour</b>, not of pattern. When both biomes resolve to the
 * same pattern — nearly always, since most things are {@code shimmer} — the
 * running instance is kept with its phases and clock intact, and only the
 * colour moves. Blending two patterns is reserved for a genuine change like
 * shimmer to pulse-slow, where a change of motion is the point.
 *
 * <p><b>Rule 3 — fade from what is on screen, not from a stored value.</b>
 * Stashing {@code currentColor} while a fade is already running stashes a
 * half-blended value, so every interruption compounds the error and a border
 * walk drifts somewhere unrelated. It starts from the colour genuinely being
 * displayed this frame instead, so re-crossing a border mid-fade is safe.
 *
 * <p><b>Rule 4 — do not re-trigger on a border.</b> Handled upstream by the
 * debounce in {@code ClientEventHandlers.pollBiome}, because standing on a
 * biome edge makes the game report both biomes several times a second and
 * every report would restart the fade.
 */
public final class BiomeColorEffect implements EffectController {

    /**
     * Frame-one appearance, and exactly what {@link #clear()} restores. Held
     * as constants rather than inline field initialisers so that "never seen a
     * biome yet" has one definition, and a world unload can genuinely return
     * to it instead of approximating it.
     */
    private static final RGBColor DEFAULT_COLOR = RGBColor.fromHex("#4F7942"); // a forest green, so frame one isn't black
    private static final String DEFAULT_PATTERN_KEY = "shimmer|";

    /**
     * How long one biome dissolves into the next.
     *
     * <p>Config-driven rather than a fixed 800ms, and read per call rather than
     * cached so the live value always applies. Exposed so diagnostics can
     * compare it against the sampling interval.
     */
    public static long crossfadeMillis() {
        return RGBProfileConfig.BIOME_CROSSFADE_MILLIS.get();
    }

    private final Map<String, BiomeProfile> profiles;

    private volatile String currentBiomeId;
    private volatile RGBColor currentColor = DEFAULT_COLOR;
    private volatile Pattern currentPattern = new ShimmerPattern();
    /** "pattern|preset" — the identity used to decide keep-or-replace. */
    private volatile String currentPatternKey = DEFAULT_PATTERN_KEY;
    private volatile long patternStartMillis;

    /** Highlight colour for the running pattern. Null = let the context derive one. */
    private volatile RGBColor currentAccentColor;
    /** The outgoing pattern's accent, held so a pattern crossfade stays coherent. */
    private volatile RGBColor previousAccentColor;

    /** Colour we're fading FROM. Null when no colour fade is running. */
    private volatile RGBColor fadeFromColor;

    /** Only populated when the PATTERN changed and two frames must be blended. */
    private volatile Pattern previousPattern;
    private volatile long previousPatternStartMillis;

    private volatile long transitionStartMillis = Long.MIN_VALUE;

    public BiomeColorEffect(Map<String, BiomeProfile> profiles) {
        this.profiles = profiles;
    }

    /**
     * Called from a worker-thread job on a confirmed (debounced) biome change.
     *
     * <p>{@code derivedFallbackColor} is computed on the CLIENT thread from
     * the biome's registry data and passed in ready-made. That's
     * the whole reason this class contains no Minecraft imports: registry
     * access is client-thread-only, this class is worker-thread-only, and the
     * boundary is one already-resolved colour.
     */
    public void onBiomeChanged(String biomeId, RGBColor derivedFallbackColor, boolean fallbackEnabled, long nowMillis) {
        if (biomeId.equals(currentBiomeId)) return;

        BiomeProfile profile = ProfileResolver.resolve(profiles, biomeId, fallbackEnabled, null);
        RGBColor resolvedColor = profile != null ? profile.resolvedColor(derivedFallbackColor) : derivedFallbackColor;
        RGBColor resolvedAccent = profile != null ? profile.resolvedAccentColor() : null;

        String patternKey = profile != null
                ? (profile.pattern == null ? "shimmer" : profile.pattern) + "|" + (profile.preset == null ? "" : profile.preset)
                : "shimmer|";

        // Was a fade already in flight? Captured BEFORE it gets overwritten,
        // purely for diagnostics. Repeatedly interrupting a fade is what
        // walking along a biome border looks like from in here, and having it
        // in the log is the only way to tell that apart from the fade simply
        // being wrong.
        double interrupted = -1;
        if (fadeFromColor != null && transitionStartMillis != Long.MIN_VALUE) {
            long e = nowMillis - transitionStartMillis;
            if (e < crossfadeMillis()) interrupted = e / (double) crossfadeMillis();
        }
        RGBColor previousOnScreen = colorOnScreen(nowMillis);

        Diagnostics.biomeCommitted(biomeId, profile != null, previousOnScreen, resolvedColor,
                patternKey, !patternKey.equals(currentPatternKey), interrupted, nowMillis);

        // Rule 3: fade from what is ACTUALLY on screen right now, not from a
        // stored field that may already be mid-blend.
        this.fadeFromColor = previousOnScreen;

        if (patternKey.equals(currentPatternKey)) {
            // Rule 2, and note it is a deletion rather than an addition: same
            // motion means keep the running instance. Phases and clock
            // survive, so the animation never stutters and only the colour
            // moves, which is what a biome crossfade is.
            this.previousPattern = null;
        } else {
            // A real change of motion, so blend the two. Rule 1: hang on to
            // the outgoing pattern's own start time, or it restarts from
            // frame zero exactly as it is trying to fade out.
            this.previousPattern = this.currentPattern;
            this.previousPatternStartMillis = this.patternStartMillis;
            this.previousAccentColor = this.currentAccentColor;
            this.currentPattern = profile != null
                    ? PatternFactory.fromNameAndPreset(profile.pattern, profile.preset)
                    : new ShimmerPattern();
            this.patternStartMillis = nowMillis;
            this.currentPatternKey = patternKey;
        }

        this.transitionStartMillis = nowMillis;
        this.currentBiomeId = biomeId;
        this.currentColor = resolvedColor;
        this.currentAccentColor = resolvedAccent;
    }

    /**
     * Back to "no biome has ever been seen", which is the only state in which
     * {@link #isActive} reports false.
     *
     * <p>Called when the world unloads. {@link #isActive} is true from the first
     * biome of the session onward, which was fine until the main menu gained a
     * layer of its own: nothing had ever needed to switch this one off. The
     * consequence was that quitting to the title screen with
     * {@code menuTheme.enabled = false} left the last biome's colour and pattern
     * on the board, with nothing above it in Tier 1 to take over and no poll
     * still running to change it.
     *
     * <p>Everything is returned to its constructed value, not just the id, so
     * that loading a second world crossfades in from the neutral default the
     * way a fresh launch does — rather than dissolving out of whatever biome
     * the PREVIOUS world happened to end in.
     */
    public void clear() {
        this.currentBiomeId = null;
        this.currentColor = DEFAULT_COLOR;
        this.currentPattern = new ShimmerPattern();
        this.currentPatternKey = DEFAULT_PATTERN_KEY;
        this.patternStartMillis = 0;
        this.currentAccentColor = null;
        this.previousAccentColor = null;
        this.fadeFromColor = null;
        this.previousPattern = null;
        this.previousPatternStartMillis = 0;
        this.transitionStartMillis = Long.MIN_VALUE;
    }

    /**
     * The colour the board is showing this instant, mid-fade included.
     *
     * <p>Used both by {@code render} and by {@code onBiomeChanged} to capture
     * the fade origin — which is the entire point of having it as a method
     * rather than inline. One definition of "what's on screen", two callers,
     * no possibility of them disagreeing.
     */
    private RGBColor colorOnScreen(long nowMillis) {
        if (fadeFromColor == null || transitionStartMillis == Long.MIN_VALUE) return currentColor;
        long elapsed = nowMillis - transitionStartMillis;
        if (elapsed >= crossfadeMillis()) return currentColor;
        return fadeFromColor.lerp(currentColor, FadePattern.easeInOutCubic(elapsed / (double) crossfadeMillis()));
    }

    @Override
    public String id() {
        return "biome_color";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER1_OPAQUE_BASE;
    }

    @Override
    public int priority() {
        return 0; // lowest. everything outranks scenery
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Active from the first biome we ever see, then forever. This is the
        // wallpaper; it does not take breaks.
        return currentBiomeId != null;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        RGBColor color = colorOnScreen(nowMillis);
        PatternContext ctx = new PatternContext(grid, null, color, currentAccentColor, 0, PatternParams.EMPTY);
        Map<KeyGrid.LedRef, LayerPixel> newFrame = currentPattern.render(ctx, nowMillis - patternStartMillis);

        boolean blendingPatterns = previousPattern != null
                && transitionStartMillis != Long.MIN_VALUE
                && (nowMillis - transitionStartMillis) < crossfadeMillis();
        // The common path, and the fast one: same pattern, colour already
        // interpolated above, nothing more to do. Most biome changes never
        // reach the blending code below at all.
        if (!blendingPatterns) {
            return newFrame;
        }

        double eased = FadePattern.easeInOutCubic(
                (nowMillis - transitionStartMillis) / (double) crossfadeMillis());

        // previousPatternStartMillis, NOT transitionStartMillis. That one
        // substitution is Rule 1 in its entirety — see the class doc.
        PatternContext oldCtx = new PatternContext(grid, null, fadeFromColor, previousAccentColor, 0, PatternParams.EMPTY);
        Map<KeyGrid.LedRef, LayerPixel> oldFrame =
                previousPattern.render(oldCtx, nowMillis - previousPatternStartMillis);

        // Union of both key sets, because patterns are sparse and the two may
        // light entirely different keys (a shimmer covers everything, a
        // particle pattern covers a handful). Iterating only one side would
        // silently drop the other's contribution.
        Map<KeyGrid.LedRef, LayerPixel> blended = new HashMap<>();
        Set<KeyGrid.LedRef> allKeys = new HashSet<>();
        allKeys.addAll(oldFrame.keySet());
        allKeys.addAll(newFrame.keySet());
        for (KeyGrid.LedRef key : allKeys) {
            LayerPixel oldPixel = oldFrame.get(key);
            LayerPixel newPixel = newFrame.get(key);
            // Present in only one frame, and this is where the "hard cut" came
            // from. An earlier version left these keys alone on the assumption
            // that the pattern owning them ramps itself up or down. None of
            // them do: shimmer, twinkle and drift all render at full strength
            // from their first frame and have no fade-in of any kind.
            //
            // So a key the incoming pattern lights but the outgoing one did not
            // appeared instantly at full brightness, and a key the outgoing one
            // held but the incoming one does not stayed at full brightness for
            // the whole crossfade and then vanished the instant it ended. On a
            // change between patterns with different footprints — a whole-board
            // shimmer against a sparse particle field, which is most of them —
            // that is the majority of the board popping rather than fading.
            // Scaling by the same eased factor is the crossfade the loop below
            // was already doing for keys both patterns light.
            if (oldPixel == null) {
                blended.put(key, newPixel.scaledAlpha(eased));
                continue;
            }
            if (newPixel == null) {
                blended.put(key, oldPixel.scaledAlpha(1.0 - eased));
                continue;
            }
            // Present in both: interpolate colour AND alpha together.
            blended.put(key, new LayerPixel(
                    oldPixel.color().lerp(newPixel.color(), eased),
                    oldPixel.alpha() + (newPixel.alpha() - oldPixel.alpha()) * eased));
        }
        return blended;
    }
}
