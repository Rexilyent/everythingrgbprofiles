package com.everythingrgbprofile.neoforge;

import java.util.IdentityHashMap;
import java.util.Map;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import com.everythingrgbprofile.config.ConfigSpec;
import com.everythingrgbprofile.config.RGBProfileConfig;

/**
 * Turns the shared config definitions back into a real {@link ModConfigSpec},
 * one call at a time, in the same order {@link RGBProfileConfig} made them.
 * NeoForge never finds out anything changed: it gets the same spec it always
 * got, writes the same file, corrects it the same way and reloads it when
 * you edit it, same as ever.
 */
final class NeoForgeConfig {

    static void register(ModContainer container) {
        ConfigSpec spec = RGBProfileConfig.SPEC;
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        Map<ConfigSpec.ConfigValue<?>, ModConfigSpec.ConfigValue<?>> bound = new IdentityHashMap<>();

        for (ConfigSpec.Entry entry : spec.layout()) {
            if (entry instanceof ConfigSpec.Push push) {
                if (!push.comment().isEmpty()) b.comment(push.comment().toArray(String[]::new));
                b.push(push.name());
            } else if (entry instanceof ConfigSpec.Pop) {
                b.pop();
            } else if (entry instanceof ConfigSpec.ConfigValue<?> value) {
                if (!value.comment().isEmpty()) b.comment(value.comment().toArray(String[]::new));
                bound.put(value, define(b, value));
            }
        }
        ModConfigSpec neoSpec = b.build();

        // COMMON rather than CLIENT. An earlier version had an optional
        // server-side sculk relay that read a couple of these values, and
        // COMMON was what let it. That relay is gone, because watching block
        // state on the client turned out to do the entire job by itself (see
        // SculkBlockWatcher), so nothing reads this config server-side now.
        //
        // It stays COMMON regardless. The only thing switching to CLIENT would
        // achieve is a dedicated server no longer bothering to load a file it
        // was already ignoring, and then the filename would be lying about
        // itself. The filename keeps "common" so configs from earlier versions
        // carry straight over, and the Fabric jar writes the same name for the
        // same reason. It lands in config/ under a name that says what it is,
        // because nobody enjoys archaeology.
        container.registerConfig(ModConfig.Type.COMMON, neoSpec, "everythingrgbprofiles-common.toml");

        spec.attach(new ConfigSpec.Store() {
            @Override
            public Object get(ConfigSpec.ConfigValue<?> value) {
                return bound.get(value).get();
            }

            @Override
            @SuppressWarnings("unchecked")
            public void set(ConfigSpec.ConfigValue<?> value, Object newValue) {
                ((ModConfigSpec.ConfigValue<Object>) bound.get(value)).set(newValue);
            }

            @Override
            public void save() {
                neoSpec.save();
            }
        });
    }

    private static ModConfigSpec.ConfigValue<?> define(ModConfigSpec.Builder b, ConfigSpec.ConfigValue<?> value) {
        String key = value.key();
        if (value instanceof ConfigSpec.BooleanValue v) return b.define(key, (boolean) v.getDefault());
        if (value instanceof ConfigSpec.IntValue v) return b.defineInRange(key, (int) v.getDefault(), v.min(), v.max());
        if (value instanceof ConfigSpec.DoubleValue v) return b.defineInRange(key, (double) v.getDefault(), v.min(), v.max());
        if (value instanceof ConfigSpec.StringValue v) return b.define(key, v.getDefault());
        throw new IllegalStateException("No NeoForge equivalent for " + value.getClass().getSimpleName());
    }

    private NeoForgeConfig() {
    }
}
