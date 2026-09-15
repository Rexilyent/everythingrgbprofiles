package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Knows which lighting backends exist and picks what this session drives.
 *
 * <h2>Adding a vendor</h2>
 * Write a {@link LightingBackend} — or extend {@link GridBackend} if the vendor
 * exposes a fixed grid — and add one line to {@link #BACKENDS}. Nothing else in
 * the mod needs to know. If adding a vendor ever requires touching a pattern,
 * the abstraction has failed, not the vendor.
 *
 * <h2>Order</h2>
 * Backends that can <i>enumerate</i> hardware come first: Corsair and OpenRGB
 * both report exactly which devices exist and where their LEDs are. The grid
 * vendors — Razer, Logitech, SteelSeries — come after, because they cannot: all
 * three answer "connected" whenever their desktop software is running, whether
 * or not a keyboard is plugged in.
 *
 * <h2>Why auto drives everything that answers</h2>
 * Auto used to keep the first backend that connected. That was fine with one
 * vendor, and wrong the moment there were three that cannot tell you what is
 * attached. G HUB installed for a Logitech mouse answers exactly like G HUB with
 * a Logitech keyboard, so first-one-wins would let it take the slot from a
 * Razer or Corsair keyboard sitting on the same desk, and the user would get a
 * dark board with nothing in the log to say why.
 *
 * <p>So auto connects every backend that answers and picks a <b>leader</b> — the
 * first one whose main device is a keyboard. Patterns render against the
 * leader's geometry, and every other backend is a <b>follower</b> that shows the
 * same frame, resampled onto its own LEDs by {@link SurfaceMapper}. A grid
 * backend with no keyboard behind it costs a few ignored requests; guessing
 * wrong would have cost the user their lighting.
 *
 * <p>Naming a backend explicitly in config skips all of this and drives exactly
 * that one.
 */
public final class BackendRegistry {

    /** id -> how to build it. Insertion order is leader-preference order. */
    private static final Map<String, Supplier<LightingBackend>> BACKENDS = new LinkedHashMap<>();
    /**
     * id -> name, known without constructing anything, so diagnostics can name
     * a backend that was never built: not selected, or failed to construct.
     */
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();

    static {
        add("corsair", "Corsair iCUE", CorsairBackend::new);
        add("openrgb", "OpenRGB", OpenRgbBackend::new);
        add("razer", "Razer Chroma", RazerChromaBackend::new);
        add("logitech", "Logitech G HUB", LogitechBackend::new);
        add("steelseries", "SteelSeries GameSense", SteelSeriesBackend::new);
    }

    private static void add(String id, String displayName, Supplier<LightingBackend> factory) {
        BACKENDS.put(id, factory);
        DISPLAY_NAMES.put(id, displayName);
    }

    private BackendRegistry() {
    }

    /** Every registered id, in preference order. For config comments and logs. */
    public static List<String> ids() {
        return new ArrayList<>(BACKENDS.keySet());
    }

    /**
     * The backend for this session, per config. Never null.
     *
     * <p>An unrecognised id falls back to auto with a warning rather than
     * refusing to start: a typo in a config file should cost a log line, not
     * your lighting.
     */
    public static LightingBackend select() {
        String want = RGBProfileConfig.LIGHTING_BACKEND.get();
        if (want == null || want.isBlank() || want.equalsIgnoreCase("auto")) {
            BackendHealth.begin("auto", DISPLAY_NAMES, null);
            return new Mirror();
        }
        String id = want.toLowerCase(java.util.Locale.ROOT).trim();
        Supplier<LightingBackend> factory = BACKENDS.get(id);
        if (factory == null) {
            RGBProfileMod.LOGGER.warn(
                    "RGB Profile: unknown lightingBackend '{}'. Known: {}. Falling back to auto.",
                    want, ids());
            BackendHealth.begin(want + " (not recognised, so auto)", DISPLAY_NAMES, null);
            BackendHealth.problem("Config", "lightingBackend is set to \"" + want
                    + "\", which is not one of " + ids() + ", so every backend was tried instead.", null);
            return new Mirror();
        }
        BackendHealth.begin(id, DISPLAY_NAMES, id);
        try {
            return factory.get();
        } catch (Throwable t) {
            // Not caught, this would fail the worker thread's construction on
            // the game thread, taking client setup down with it.
            BackendHealth.recordFailure(id, DISPLAY_NAMES.get(id), "while starting up", t,
                    "Could not start.", BackendHealth.SEND_REPORT);
            return new Mirror.Nothing(id, DISPLAY_NAMES.get(id));
        }
    }

    /**
     * Auto mode: one leader for geometry, every other live backend mirroring it.
     *
     * <p>With exactly one vendor present — the common case — there are no
     * followers, and this behaves
     * exactly like that vendor's backend alone.
     */
    static final class Mirror implements LightingBackend {

        /** Stands in for a backend that could not even be constructed, so the render loop still has one. */
        static final class Nothing implements LightingBackend {
            private final String id;
            private final String displayName;

            Nothing(String id, String displayName) {
                this.id = id;
                this.displayName = displayName;
            }

            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return displayName;
            }

            @Override
            public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
                return false;
            }

            @Override
            public boolean connected() {
                return false;
            }

            @Override
            public KeyGrid keyGrid() {
                return KeyGrid.empty();
            }

            @Override
            public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
            }

            @Override
            public void shutdown() {
            }
        }

        private LightingBackend leader;
        private final List<LightingBackend> live = new ArrayList<>();
        private final List<LightingBackend> followers = new ArrayList<>();
        /** Per follower: its LED -> the leader LED it copies. */
        private final List<Map<KeyGrid.LedRef, KeyGrid.LedRef>> maps = new ArrayList<>();
        /** Per follower: a frame map reused every frame, so mirroring allocates nothing. */
        private final List<Map<KeyGrid.LedRef, RGBColor>> scratch = new ArrayList<>();

        @Override
        public String id() {
            return leader != null ? leader.id() : "auto";
        }

        @Override
        public String displayName() {
            if (leader == null) return "auto-detect";
            return followers.isEmpty()
                    ? leader.displayName()
                    : leader.displayName() + " (+" + followers.size() + " mirrored)";
        }

        @Override
        public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
            for (Map.Entry<String, Supplier<LightingBackend>> e : BACKENDS.entrySet()) {
                LightingBackend candidate;
                try {
                    candidate = e.getValue().get();
                } catch (Throwable t) {
                    BackendHealth.recordFailure(e.getKey(), DISPLAY_NAMES.get(e.getKey()), "while starting up", t,
                            "Could not start.", BackendHealth.SEND_REPORT);
                    continue;
                }
                boolean up = false;
                try {
                    up = candidate.connect(workDir, deviceEnabled);
                } catch (Throwable t) {
                    // Each backend is meant to catch its own failures; one that
                    // escapes is a gap in that backend, so it is the mod's fault.
                    BackendHealth.recordFailure(e.getKey(), candidate.displayName(), "while connecting", t,
                            candidate.displayName() + " did not answer.", BackendHealth.SEND_REPORT);
                }
                if (up) {
                    live.add(candidate);
                } else {
                    try {
                        candidate.shutdown();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (live.isEmpty()) {
                RGBProfileMod.LOGGER.info("RGB Profile: no lighting backend connected (tried {}).", ids());
                return false;
            }

            for (LightingBackend b : live) {
                KeyGrid.Surface primary = b.keyGrid().primarySurface();
                if (primary != null && primary.deviceClass() == KeyGrid.DeviceClass.KEYBOARD) {
                    leader = b;
                    break;
                }
            }
            // Nobody has a keyboard. Plot on whatever has LEDs, same as the
            // single-backend fallback to a mousepad.
            if (leader == null) leader = live.get(0);

            List<KeyGrid.LedPosition> leaderLeds = leader.keyGrid().allKeys();
            List<String> mirrored = new ArrayList<>();
            for (LightingBackend b : live) {
                if (b == leader) continue;
                List<KeyGrid.LedPosition> theirs = new ArrayList<>();
                for (KeyGrid.Surface s : b.keyGrid().surfaces()) theirs.addAll(s.leds());
                followers.add(b);
                maps.add(SurfaceMapper.map(theirs, leaderLeds));
                scratch.add(new HashMap<>());
                mirrored.add(b.displayName());
            }
            RGBProfileMod.LOGGER.info("RGB Profile: lighting led by {}{}.", leader.displayName(),
                    mirrored.isEmpty() ? "" : ", mirrored to " + String.join(", ", mirrored));
            return true;
        }

        /**
         * True while <i>any</i> live backend can still deliver.
         *
         * <p>Not just the leader. The render loop stops calling
         * {@link #applyFrame} the moment this goes false, so a leader dropping
         * out — G HUB restarting, say — would otherwise silence every follower
         * along with it. A dropped leader skips its own frames internally.
         */
        /** Every backend mirroring the leader, by id. Empty until connected. */
        List<String> followerIds() {
            List<String> ids = new ArrayList<>();
            for (LightingBackend f : followers) ids.add(f.id());
            return ids;
        }

        @Override
        public boolean connected() {
            for (LightingBackend b : live) {
                if (b.connected()) return true;
            }
            return false;
        }

        @Override
        public KeyGrid keyGrid() {
            return leader != null ? leader.keyGrid() : KeyGrid.empty();
        }

        @Override
        public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
            if (leader == null) return;
            leader.applyFrame(frame);
            for (int i = 0; i < followers.size(); i++) {
                LightingBackend f = followers.get(i);
                if (!f.connected()) continue;
                Map<KeyGrid.LedRef, RGBColor> out = scratch.get(i);
                out.clear();
                for (Map.Entry<KeyGrid.LedRef, KeyGrid.LedRef> m : maps.get(i).entrySet()) {
                    RGBColor c = frame.get(m.getValue());
                    if (c != null) out.put(m.getKey(), c);
                }
                // Safe to reuse: every backend copies what it needs out of the
                // map before returning, and nothing holds on to it.
                f.applyFrame(out);
            }
        }

        @Override
        public void shutdown() {
            for (LightingBackend b : live) {
                try {
                    b.shutdown();
                } catch (Throwable ignored) {
                }
            }
            live.clear();
            followers.clear();
            maps.clear();
            scratch.clear();
            leader = null;
        }
    }
}
