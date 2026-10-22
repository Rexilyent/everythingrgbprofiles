package com.everythingrgbprofile.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The shape of the config file, minus the part where anything gets stored.
 *
 * <p>NeoForge has {@code ModConfigSpec}. Fabric has nothing, and the one
 * library that would bring {@code ModConfigSpec} over is a whole extra mod
 * for players to install, so no thanks. This class copies just the slice of
 * that API {@link RGBProfileConfig} actually calls (push, pop, comment,
 * define, defineInRange, get, set, save) and remembers every call in order.
 * What happens next depends on the loader:
 *
 * <ul>
 *   <li>NeoForge replays the whole layout into a real {@code ModConfigSpec}
 *       and registers that, so nothing about the config changes there. Same
 *       file, same corrections, same live reload.</li>
 *   <li>Fabric writes and reads the same file itself, laid out the way
 *       NeoForge writes it, so a config carries between loaders untouched.</li>
 * </ul>
 *
 * <p>Either way, the loader hands back a {@link Store} through
 * {@link #attach}, and from then on {@code get()} goes straight to it.
 */
public final class ConfigSpec {

    /** Where the values actually live. One per loader. */
    public interface Store {
        Object get(ConfigValue<?> value);

        void set(ConfigValue<?> value, Object newValue);

        void save();
    }

    /** One step of the file's layout, in the order the builder saw it. */
    public sealed interface Entry permits Push, Pop, ConfigValue {
    }

    /** Opens a {@code [section]}, with whatever comment was queued up for it. */
    public record Push(String name, List<String> comment) implements Entry {
    }

    /** Closes the section most recently opened. */
    public record Pop() implements Entry {
    }

    private final List<Entry> layout;
    private final List<ConfigValue<?>> values;
    private volatile Store store;

    private ConfigSpec(List<Entry> layout) {
        this.layout = Collections.unmodifiableList(layout);
        List<ConfigValue<?>> found = new ArrayList<>();
        for (Entry entry : layout) {
            if (entry instanceof ConfigValue<?> value) found.add(value);
        }
        this.values = Collections.unmodifiableList(found);
    }

    /** Every push, pop and value, in file order. What the loaders replay. */
    public List<Entry> layout() {
        return layout;
    }

    /** Just the values, in file order. */
    public List<ConfigValue<?>> values() {
        return values;
    }

    /** Called once by the loader, after it has loaded the file. */
    public void attach(Store store) {
        this.store = store;
    }

    public boolean isLoaded() {
        return store != null;
    }

    private Store store() {
        Store current = store;
        if (current == null) {
            // Same deal as NeoForge, which throws on a read before the file
            // is loaded. Handing back the default here would be friendlier
            // for about five minutes, until somebody spends an evening
            // wondering why their setting is ignored only during startup.
            throw new IllegalStateException("Config read before it was loaded");
        }
        return current;
    }

    // ---------------------------------------------------------------
    // Values
    // ---------------------------------------------------------------

    public abstract static sealed class ConfigValue<T> implements Entry
            permits BooleanValue, IntValue, DoubleValue, StringValue {

        private final ConfigSpec[] owner;
        private final List<String> path;
        private final List<String> comment;
        private final T defaultValue;

        private ConfigValue(ConfigSpec[] owner, List<String> path, List<String> comment, T defaultValue) {
            this.owner = owner;
            this.path = List.copyOf(path);
            this.comment = List.copyOf(comment);
            this.defaultValue = defaultValue;
        }

        @SuppressWarnings("unchecked")
        public T get() {
            return (T) owner[0].store().get(this);
        }

        public void set(T value) {
            owner[0].store().set(this, value);
        }

        /** Writes the whole file, like NeoForge's {@code save()} does. */
        public void save() {
            owner[0].store().save();
        }

        /** Section names, then the key. */
        public List<String> getPath() {
            return path;
        }

        public String key() {
            return path.get(path.size() - 1);
        }

        public T getDefault() {
            return defaultValue;
        }

        public List<String> comment() {
            return comment;
        }

        /**
         * What a raw value read from the file turns into, or null when it is
         * unusable and the default should take its place. Numbers out of
         * range get clamped rather than thrown out, which is what NeoForge
         * does with them too.
         */
        public abstract T correct(Object raw);
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        private BooleanValue(ConfigSpec[] owner, List<String> path, List<String> comment, boolean defaultValue) {
            super(owner, path, comment, defaultValue);
        }

        @Override
        public Boolean correct(Object raw) {
            return raw instanceof Boolean b ? b : null;
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        private final int min;
        private final int max;

        private IntValue(ConfigSpec[] owner, List<String> path, List<String> comment, int defaultValue, int min, int max) {
            super(owner, path, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        public Integer correct(Object raw) {
            // Integers only. "25.0" for a percentage is a typo worth hearing
            // about, not something to quietly round.
            if (!(raw instanceof Integer || raw instanceof Long)) return null;
            long n = ((Number) raw).longValue();
            return (int) Math.max(min, Math.min(max, n));
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {
        private final double min;
        private final double max;

        private DoubleValue(ConfigSpec[] owner, List<String> path, List<String> comment, double defaultValue, double min, double max) {
            super(owner, path, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        @Override
        public Double correct(Object raw) {
            // Whole numbers are welcome here: nobody should get their
            // "rainOpacity = 1" thrown out for missing a ".0".
            if (!(raw instanceof Number n)) return null;
            double d = n.doubleValue();
            if (Double.isNaN(d)) return null;
            return Math.max(min, Math.min(max, d));
        }
    }

    public static final class StringValue extends ConfigValue<String> {
        private StringValue(ConfigSpec[] owner, List<String> path, List<String> comment, String defaultValue) {
            super(owner, path, comment, defaultValue);
        }

        @Override
        public String correct(Object raw) {
            return raw instanceof String s ? s : null;
        }
    }

    // ---------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------

    public static final class Builder {
        // The values exist before the spec does (build() comes last), so they
        // share this one-slot box and find their spec through it once built.
        private final ConfigSpec[] owner = new ConfigSpec[1];
        private final List<Entry> layout = new ArrayList<>();
        private final Deque<String> sections = new ArrayDeque<>();
        private List<String> pendingComment = List.of();

        /** Applies to whatever gets defined or pushed next, like NeoForge's. */
        public Builder comment(String... lines) {
            pendingComment = List.of(lines);
            return this;
        }

        public Builder push(String name) {
            layout.add(new Push(name, takeComment()));
            sections.addLast(name);
            return this;
        }

        public Builder pop() {
            if (sections.isEmpty()) throw new IllegalStateException("pop() without a push()");
            sections.removeLast();
            layout.add(new Pop());
            return this;
        }

        public BooleanValue define(String key, boolean defaultValue) {
            return add(new BooleanValue(owner, pathTo(key), takeComment(), defaultValue));
        }

        public ConfigValue<String> define(String key, String defaultValue) {
            return add(new StringValue(owner, pathTo(key), takeComment(), defaultValue));
        }

        public IntValue defineInRange(String key, int defaultValue, int min, int max) {
            return add(new IntValue(owner, pathTo(key), takeComment(), defaultValue, min, max));
        }

        public DoubleValue defineInRange(String key, double defaultValue, double min, double max) {
            return add(new DoubleValue(owner, pathTo(key), takeComment(), defaultValue, min, max));
        }

        public ConfigSpec build() {
            if (!sections.isEmpty()) throw new IllegalStateException("Unclosed section: " + sections.peekLast());
            if (owner[0] != null) throw new IllegalStateException("build() called twice");
            owner[0] = new ConfigSpec(new ArrayList<>(layout));
            return owner[0];
        }

        private <V extends ConfigValue<?>> V add(V value) {
            if (owner[0] != null) throw new IllegalStateException("Defined after build(): " + value.getPath());
            layout.add(value);
            return value;
        }

        private List<String> pathTo(String key) {
            List<String> path = new ArrayList<>(sections);
            path.add(key);
            return path;
        }

        private List<String> takeComment() {
            List<String> comment = pendingComment;
            pendingComment = List.of();
            return comment;
        }
    }
}
