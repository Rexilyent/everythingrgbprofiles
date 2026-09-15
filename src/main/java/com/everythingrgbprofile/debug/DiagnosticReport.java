package com.everythingrgbprofile.debug;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.sdk.BackendHealth;
import com.everythingrgbprofile.sdk.SdkWorkerThread;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The file {@code /rgbprofiles report} writes: everything needed to work out a
 * lighting problem on hardware nobody here owns, in one place, readable by a
 * person.
 *
 * <p>Built for the conversation that follows "my keyboard doesn't light".
 * Without it, that conversation is a dozen questions — which version, which
 * brand, is the software running, is it on in the config — each costing a
 * round trip, and the player's latest.log answers some of them, buried among
 * every other mod's output. The report answers all of them up front, and
 * leads with what the mod already concluded, so that the common cases are
 * solved by the player reading the first screen of it.
 *
 * <p>Nothing personal goes in: no player name, no server address, no paths
 * beyond the vendor install locations the mod looked in.
 */
public final class DiagnosticReport {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Vendor programs worth knowing are running, as lowercase substrings of the
     * executable name. The last two are not backends: they drive the same
     * lights, and two programs driving one keyboard fight over it.
     */
    private static final String[][] VENDOR_PROCESSES = {
            {"Corsair iCUE", "icue"},
            {"OpenRGB", "openrgb"},
            {"Razer Synapse / Chroma", "razer", "rzsdk", "rzchroma"},
            {"Logitech G HUB", "lghub"},
            {"SteelSeries GG", "steelseries"},
            {"SignalRGB (drives the same lights)", "signalrgb"},
            {"ASUS Armoury Crate / Aura (drives the same lights)", "armourycrate", "lightingservice", "aura"},
    };

    private DiagnosticReport() {
    }

    /** Where the report is written: next to latest.log, which is usually asked for alongside it. */
    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve(RGBProfileMod.MODID + "-report.txt");
    }

    public static Path write(String report) throws IOException {
        Path path = path();
        Files.createDirectories(path.getParent());
        Files.writeString(path, report, StandardCharsets.UTF_8);
        return path;
    }

    public static String build() {
        StringBuilder out = new StringBuilder();
        long now = System.currentTimeMillis();
        line(out, "Everything RGB Profiles diagnostic report");
        line(out, "Written " + LocalDateTime.now().format(STAMP));
        line(out, "Only Corsair iCUE has been tested on real hardware. OpenRGB, Razer Chroma, Logitech G HUB "
                + "and SteelSeries GameSense are experimental.");

        section(out, "Summary");
        for (String s : conclusions()) line(out, "* " + s);

        section(out, "Versions");
        line(out, "Everything RGB Profiles " + modVersion(RGBProfileMod.MODID)
                + (Feature.experimentalBuild() ? " (EXPERIMENTAL build)" : " (release build)"));
        line(out, "Minecraft " + modVersion("minecraft") + ", NeoForge " + modVersion("neoforge"));
        line(out, "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + "), "
                + System.getProperty("sun.arch.data.model", "?") + "-bit");
        line(out, "OS " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")");

        section(out, "Settings");
        line(out, "[general] enabled = " + safe(() -> RGBProfileConfig.GENERAL_ENABLED.get()));
        line(out, "[hardware] lightingBackend = \"" + safe(() -> RGBProfileConfig.LIGHTING_BACKEND.get()) + "\"");
        line(out, "[hardware] openRgbHost = \"" + safe(() -> RGBProfileConfig.OPENRGB_HOST.get())
                + "\", openRgbPort = " + safe(() -> RGBProfileConfig.OPENRGB_PORT.get()));
        line(out, "[devices] keyboard " + safe(() -> RGBProfileConfig.DEVICE_KEYBOARD_ENABLED.get())
                + ", mouse " + safe(() -> RGBProfileConfig.DEVICE_MOUSE_ENABLED.get())
                + ", mousemat " + safe(() -> RGBProfileConfig.DEVICE_MOUSEMAT_ENABLED.get())
                + ", headset " + safe(() -> RGBProfileConfig.DEVICE_HEADSET_ENABLED.get())
                + ", memory " + safe(() -> RGBProfileConfig.DEVICE_MEMORY_ENABLED.get())
                + ", cooling " + safe(() -> RGBProfileConfig.DEVICE_COOLING_ENABLED.get())
                + ", motherboard " + safe(() -> RGBProfileConfig.DEVICE_MOTHERBOARD_ENABLED.get())
                + ", other " + safe(() -> RGBProfileConfig.DEVICE_OTHER_ENABLED.get()));
        line(out, "[advanced] animationFrameRateHz = " + safe(() -> RGBProfileConfig.ANIMATION_FRAME_RATE_HZ.get()));
        line(out, "[debug] enabled = " + safe(() -> RGBProfileConfig.DEBUG_ENABLED.get()));

        section(out, "Settings changed from their defaults");
        ConfigAudit audit = auditConfig();
        if (audit.error != null) {
            line(out, "Could not read the settings: " + audit.error);
        } else if (audit.changed.isEmpty()) {
            line(out, "None: every setting is at its default.");
        }
        for (String s : audit.changed) line(out, s);
        if (!audit.unusable.isEmpty()) {
            out.append('\n');
            line(out, "Settings that could not be used (their default is used instead):");
            for (String s : audit.unusable) line(out, "  " + s);
        }

        section(out, "Features");
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            String name = feature.name().toLowerCase(Locale.ROOT);
            if (!feature.isAvailable()) {
                off.add(name + " (not in this version)");
                continue;
            }
            boolean enabled;
            try {
                enabled = feature.isOn();
            } catch (RuntimeException e) {
                off.add(name + " (setting unreadable)");
                continue;
            }
            if (enabled) {
                on.add(name);
            } else {
                off.add(name + " (turned off in the config)");
            }
        }
        line(out, "On: " + (on.isEmpty() ? "none" : String.join(", ", on)));
        line(out, "Off: " + (off.isEmpty() ? "none" : String.join(", ", off)));

        section(out, "Supported mods");
        for (Map.Entry<String, String> mod : ModCompatRegistry.supportedMods().entrySet()) {
            String version = modVersion(mod.getKey());
            line(out, mod.getValue() + ": " + ("unknown".equals(version) ? "not installed" : "installed, " + version));
        }
        line(out, "Mods loaded in total: " + safe(() -> ModList.get().size()));

        section(out, "Startup");
        if (BackendHealth.gateSummary() != null) {
            line(out, "Lighting was never started: " + BackendHealth.gateSummary());
        } else if (!BackendHealth.workerStarted()) {
            line(out, "Lighting was never started, and nothing recorded why. This is a gap in the mod's diagnostics.");
        } else if (!BackendHealth.connectFinished()) {
            line(out, "Still connecting to lighting software.");
        } else {
            line(out, "Tried lighting software in " + BackendHealth.connectMillis() + " ms, "
                    + "lightingBackend = \"" + BackendHealth.requestedBackend() + "\".");
        }

        section(out, "Lighting software");
        for (BackendHealth.Diagnosis d : BackendHealth.diagnoses()) {
            out.append('\n');
            line(out, d.displayName() + " [" + d.backendId() + "]"
                    + (BackendHealth.tested(d.backendId()) ? "" : " (experimental)")
                    + roleOf(d.backendId()));
            line(out, "  State:  " + d.state().label() + (d.state().modFault() ? "  <-- a problem in the mod" : ""));
            line(out, "  What:   " + d.summary());
            if (d.fix() != null) line(out, "  Try:    " + d.fix());
            line(out, "  When:   " + ago(now, d.atMillis()));
            if (d.detail() != null) {
                line(out, "  Detail:");
                for (String l : d.detail().split("\n")) line(out, "    " + l);
            }
        }

        section(out, "Lighting in use");
        SdkWorkerThread.Status status = SdkWorkerThread.status();
        if (status == null) {
            line(out, "No lighting thread.");
        } else {
            line(out, "Driving: " + status.displayName() + (status.connected() ? "" : " (not connected)"));
            if (!BackendHealth.followerIds().isEmpty()) {
                line(out, "Mirrored to: " + String.join(", ", BackendHealth.followerIds()));
            }
            KeyGrid grid = status.grid();
            if (grid.isEmpty()) {
                line(out, "No LEDs.");
            } else {
                for (KeyGrid.Surface s : grid.surfaces()) {
                    line(out, "Device: " + s.model() + " (" + s.deviceClass() + ", " + s.leds().size()
                            + " LEDs, id " + s.deviceId() + ")");
                }
                line(out, String.format(Locale.ROOT, "Geometry: key width %.4f of the board, aspect ratio %.3f",
                        grid.keyWidthNormalised(), grid.aspectRatio()));
                StringBuilder keys = new StringBuilder();
                for (String k : new String[]{"W", "A", "S", "D", "T", "H", "F"}) {
                    KeyGrid.LedRef ref = grid.namedKey(k);
                    keys.append(k).append('=').append(ref == null ? "missing" : String.valueOf(ref.luid())).append(' ');
                }
                line(out, "Keys found by name: " + keys.toString().trim()
                        + " (missing is expected for Logitech and SteelSeries, which do not say where keys are)");
            }
            line(out, "Frames sent: " + status.framesSent());
            if (status.stoppedBecause() != null) {
                line(out, "Render loop: STOPPED. " + status.stoppedBecause());
            } else if (status.lastLoopMillis() == 0) {
                line(out, "Render loop: not started yet.");
            } else {
                long since = now - status.lastLoopMillis();
                line(out, "Render loop: last ran " + since + " ms ago"
                        + (since > 2000 ? "  <-- stuck: it should run many times a second" : ""));
            }
        }

        section(out, "Lighting programs running");
        scanProcesses(out);

        section(out, "Effects");
        EffectTimeline.Snapshot current = EffectTimeline.current();
        if (current.atMillis() == 0) {
            line(out, "Not sampled yet.");
        } else {
            line(out, "Now: " + current.board());
            line(out, "Active: " + (current.active().isEmpty() ? "nothing" : String.join(", ", current.active())));
        }
        List<EffectTimeline.Event> events = EffectTimeline.recent();
        out.append('\n');
        line(out, "Last " + events.size() + " effect changes, oldest first. '+' started, '-' stopped; "
                + "\"board\" is who owned the keyboard just after, which tells an effect that never "
                + "started from one that started and was drawn over.");
        for (EffectTimeline.Event e : events) {
            line(out, LocalDateTime.ofInstant(Instant.ofEpochMilli(e.atMillis()), ZoneId.systemDefault()).format(CLOCK)
                    + " " + (e.started() ? "+ " : "- ") + e.effectId() + " (" + e.tier() + ", prio " + e.priority()
                    + ")" + (e.started() ? " | " + e.board() : ""));
        }

        section(out, "Errors recorded this session");
        List<BackendHealth.Problem> problems = BackendHealth.problems();
        if (problems.isEmpty()) {
            line(out, "None.");
        }
        for (BackendHealth.Problem p : problems) {
            out.append('\n');
            line(out, p.source() + ": " + p.message());
            line(out, "  " + (p.count() == 1 ? "Once, " + ago(now, p.firstMillis())
                    : p.count() + " times, first " + ago(now, p.firstMillis()) + ", last " + ago(now, p.lastMillis())));
            if (p.detail() != null) {
                for (String l : p.detail().split("\n")) line(out, "    " + l);
            }
        }
        out.append('\n');
        return out.toString();
    }

    /**
     * What the mod makes of it all, most useful first. The part of the report a
     * player can act on without reading the rest.
     */
    public static List<String> conclusions() {
        List<String> out = new ArrayList<>();
        if (BackendHealth.gateSummary() != null) {
            out.add(BackendHealth.gateSummary() + (BackendHealth.gateFix() == null ? "" : " " + BackendHealth.gateFix()));
            return out;
        }
        if (!BackendHealth.connectFinished()) {
            out.add("Still connecting to lighting software; check again in a few seconds.");
            return out;
        }
        List<BackendHealth.Diagnosis> all = BackendHealth.diagnoses();
        String leader = BackendHealth.leaderId();
        for (BackendHealth.Diagnosis d : all) {
            if (d.state().modFault()) {
                out.add(d.displayName() + " failed because of a problem in this mod: " + d.summary());
            }
        }
        for (BackendHealth.Diagnosis d : all) {
            if (d.state() == BackendHealth.State.LOST) {
                out.add(d.displayName() + " disconnected: " + d.summary() + " " + d.fix());
            }
        }
        if (leader == null) {
            out.add("No lighting software connected, so nothing will light.");
            for (BackendHealth.Diagnosis d : all) {
                if (d.state().modFault() || d.state() == BackendHealth.State.NOT_SELECTED) continue;
                out.add(d.displayName() + ": " + d.summary() + (d.fix() == null ? "" : " " + d.fix()));
            }
        } else {
            for (BackendHealth.Diagnosis d : all) {
                if (!d.backendId().equals(leader)) continue;
                out.add("Lighting through " + d.displayName() + ". " + d.summary()
                        + (BackendHealth.tested(leader) ? "" : " This backend is experimental; if colours or "
                        + "positions look wrong, /rgbprofiles test shows which."));
            }
            for (BackendHealth.Diagnosis d : all) {
                if (d.backendId().equals(leader) || d.state().modFault() || d.state() == BackendHealth.State.LOST) continue;
                if (d.state().needsAttention()) {
                    out.add(d.displayName() + ": " + d.summary() + (d.fix() == null ? "" : " " + d.fix()));
                }
            }
        }
        ConfigAudit audit = auditConfig();
        if (!audit.unusable.isEmpty()) {
            out.add(audit.unusable.size() + " setting(s) could not be used and fell back to their defaults: "
                    + String.join("; ", audit.unusable));
        }
        int bugs = 0;
        for (BackendHealth.Problem p : BackendHealth.problems()) bugs += p.count();
        if (bugs > 0) out.add(bugs + " error(s) recorded this session; see the end of this report.");
        return out;
    }

    /** Settings that differ from their defaults, and changed ones that cannot be used. */
    private static final class ConfigAudit {
        final List<String> changed = new ArrayList<>();
        final List<String> unusable = new ArrayList<>();
        String error;
    }

    /**
     * Walks every setting rather than a chosen few, so a setting added later
     * is covered without anyone remembering to add it here.
     *
     * <p>Only changed settings are listed: the defaults are known, and a list of
     * three hundred values would bury the one that matters. Colours get one more
     * check, because a colour that does not parse falls back to its default
     * silently, which reads as the setting being ignored.
     */
    private static ConfigAudit auditConfig() {
        ConfigAudit audit = new ConfigAudit();
        try {
            walk(RGBProfileConfig.SPEC.getValues(), audit);
        } catch (RuntimeException e) {
            audit.error = e.toString();
        }
        return audit;
    }

    private static void walk(UnmodifiableConfig config, ConfigAudit audit) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            Object value = entry.getRawValue();
            if (value instanceof UnmodifiableConfig section) {
                walk(section, audit);
                continue;
            }
            if (!(value instanceof ModConfigSpec.ConfigValue<?> setting)) continue;
            Object now = setting.get();
            Object initial = setting.getDefault();
            if (Objects.equals(now, initial)) continue;
            List<String> path = setting.getPath();
            String key = path.get(path.size() - 1);
            String name = path.size() > 1
                    ? "[" + String.join(".", path.subList(0, path.size() - 1)) + "] " + key
                    : key;
            audit.changed.add(name + " = " + quote(now) + "   (default " + quote(initial) + ")");
            if (now instanceof String text && key.toLowerCase(Locale.ROOT).contains("color")) {
                try {
                    RGBColor.fromHex(text.trim());
                } catch (RuntimeException e) {
                    audit.unusable.add(name + " = " + quote(now) + " is not a colour; write it like \"#FF8800\"");
                }
            }
        }
    }

    private static String quote(Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }

    private static String roleOf(String id) {
        if (id.equals(BackendHealth.leaderId())) return ", leading";
        if (BackendHealth.followerIds().contains(id)) return ", mirroring";
        return "";
    }

    /**
     * Which lighting programs are running. A hint, not proof: Windows hides the
     * names of processes running as another user or as administrator, so
     * "not seen" cannot mean "not running".
     */
    private static void scanProcesses(StringBuilder out) {
        TreeSet<String> names = new TreeSet<>();
        try {
            ProcessHandle.allProcesses().forEach(p -> p.info().command().ifPresent(cmd -> {
                String file = Path.of(cmd).getFileName().toString().toLowerCase(Locale.ROOT);
                names.add(file);
            }));
        } catch (RuntimeException e) {
            line(out, "Could not list processes: " + e);
            return;
        }
        for (String[] vendor : VENDOR_PROCESSES) {
            List<String> seen = new ArrayList<>();
            for (String name : names) {
                for (int i = 1; i < vendor.length; i++) {
                    if (name.contains(vendor[i])) {
                        seen.add(name);
                        break;
                    }
                }
            }
            line(out, vendor[0] + ": " + (seen.isEmpty() ? "not seen" : "seen (" + String.join(", ", seen) + ")"));
        }
        line(out, "(Programs running as administrator can be hidden from this list, so \"not seen\" is a hint, not proof.)");
    }

    private static String modVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String ago(long now, long at) {
        long seconds = Math.max(0, (now - at) / 1000);
        String clock = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).format(STAMP);
        if (seconds < 60) return seconds + "s ago (" + clock + ")";
        if (seconds < 3600) return (seconds / 60) + "m ago (" + clock + ")";
        return (seconds / 3600) + "h " + (seconds % 3600 / 60) + "m ago (" + clock + ")";
    }

    /** A config read that cannot throw: a report that fails to write helps nobody. */
    private static String safe(java.util.function.Supplier<Object> read) {
        try {
            return String.valueOf(read.get());
        } catch (RuntimeException e) {
            return "(unreadable)";
        }
    }

    private static void section(StringBuilder out, String title) {
        out.append("\n== ").append(title).append(" ==\n");
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
