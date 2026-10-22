package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;

/**
 * Counts how much trouble you are currently in. Specifically: how many shrieks
 * have landed close enough together to count as one escalating incident rather
 * than several unrelated ones.
 *
 * <p>Shriek once and it is a warning. Shriek again inside the window and it
 * gets brighter, faster and considerably more insistent. Wander off, wait it
 * out, and it resets to nothing at all.
 *
 * <p>Escalation drives two things at once, the colour and how fast the ring
 * closes, which is exactly why both are derived from one level in here instead
 * of being tracked separately. They have to move together, or the escalation
 * stops reading as escalation and starts reading as two unrelated effects
 * going off near each other.
 *
 * <p>Pure state machine. No rendering, no Minecraft, nothing to mock. Easy to
 * reason about and easy to test.
 *
 * <h2>Caveat: this does not track vanilla's own counter</h2>
 * The default {@code escalationWindowMillis} (20000ms, from the config) is far
 * shorter than the game's memory. Vanilla's {@code WardenSpawnTracker} only
 * drops its warning level after 12000 ticks (10 minutes) without a shriek,
 * ignores shrieks within 200 ticks (10 seconds) of the last one it counted,
 * and summons on level 4. So shrieks a minute apart reset this to zero while
 * the game is still counting toward a Warden. If escalation ever feels out of
 * step with the game, this is the number to go and change.
 */
public final class SculkEscalationTracker {

    private final int maxLevel;
    private final long escalationWindowMillis;

    private int currentLevel = 0;
    private long lastShriekMillis = Long.MIN_VALUE; // sentinel: no shriek ever

    public SculkEscalationTracker(int maxLevel, long escalationWindowMillis) {
        this.maxLevel = maxLevel;
        this.escalationWindowMillis = escalationWindowMillis;
    }

    /**
     * Call on each shrieker activation. Returns the resulting level (0..maxLevel).
     *
     * <p>Note the window is measured from the LAST shriek, not the first. So a
     * steady drumbeat of shrieks 15 seconds apart keeps climbing indefinitely
     * (well, up to maxLevel), while one 25 seconds late drops you straight back
     * to zero. That's the intent: escalation tracks sustained attention, not
     * total count.
     */
    public int onShriek(long nowMillis) {
        if (lastShriekMillis != Long.MIN_VALUE && (nowMillis - lastShriekMillis) <= escalationWindowMillis) {
            currentLevel = Math.min(maxLevel, currentLevel + 1);
        } else {
            // Too slow, or the first shriek ever. Back to baseline.
            currentLevel = 0;
        }
        lastShriekMillis = nowMillis;
        return currentLevel;
    }

    public void reset() {
        currentLevel = 0;
        lastShriekMillis = Long.MIN_VALUE;
    }

    /**
     * Base to peak colour as the level climbs. Static because it's a pure
     * function of the level — the caller already has one, no reason to make it
     * ask the tracker.
     */
    public static RGBColor colorForLevel(RGBColor base, RGBColor deep, int level, int maxLevel) {
        double t = maxLevel <= 0 ? 0 : level / (double) maxLevel;
        return base.lerp(deep, t);
    }

    /**
     * Ring-contract closes faster at higher escalation, down to 50% of the
     * base duration at max.
     *
     * <p>50% and not lower on purpose: a ring that closes much faster than
     * that stops reading as "closing in" and starts reading as a single flash,
     * which throws away the entire inward-motion effect right at the moment
     * you most want it. Urgency has a floor before it turns into noise.
     */
    public static long durationForLevel(long baseDurationMillis, int level, int maxLevel) {
        double t = maxLevel <= 0 ? 0 : level / (double) maxLevel;
        double factor = 1.0 - t * 0.5;
        return Math.round(baseDurationMillis * factor);
    }
}
