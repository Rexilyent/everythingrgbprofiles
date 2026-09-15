package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;

/**
 * Counts how much trouble you're in. Specifically: how many shrieks have
 * landed close enough together to count as one escalating incident.
 *
 * <p>Shriek once and it's a warning. Shriek again inside the window and it
 * gets darker, faster, and more insistent. Wander off, wait it out, and it
 * resets to nothing.
 *
 * <p>Escalation drives three things together — deeper colour, faster
 * ring-contract, harder convergence flash — which is why they're all derived
 * from one level here rather than tracked separately. They must move as one or
 * the escalation stops reading as escalation and starts reading as three
 * unrelated effects.
 *
 * <p>Pure state machine, no rendering, no Minecraft. Easy to reason about,
 * easy to test.
 *
 * <h2>Caveat: the window is a guess</h2>
 * The default {@code escalationWindowMillis} (20000ms, from config) is an
 * <b>untuned placeholder</b>. It has not been compared against vanilla's own
 * warning level, which rises with each shriek and decays on a timer of its
 * own, because measuring that means repeatedly baiting a real Warden spawn.
 * If escalation ever feels out of step with the game, this is the number to
 * change.
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
     * Base → deep colour as the level climbs. Static because it's a pure
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
