package com.everythingrgbprofile.event;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.debug.DiagnosticReport;
import com.everythingrgbprofile.debug.EffectTimeline;
import com.everythingrgbprofile.debug.HardwareTest;
import com.everythingrgbprofile.sdk.BackendHealth;
import com.everythingrgbprofile.sdk.NativeCrashGuard;
import com.everythingrgbprofile.sdk.SdkWorkerThread;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /rgbprofiles} and the chat notices that point to it: the part of the
 * diagnostics a player actually sees.
 *
 * <ul>
 *   <li>{@code /rgbprofiles status} — which lighting software is in use, and for
 *       every kind that is not, why, with what to try.</li>
 *   <li>{@code /rgbprofiles test} — {@link HardwareTest} on the keyboard, with
 *       each step's expected result above the hotbar.</li>
 *   <li>{@code /rgbprofiles effects} — which effects are active, who owns the
 *       board, and the latest changes, from {@link EffectTimeline}.</li>
 *   <li>{@code /rgbprofiles report} — {@link DiagnosticReport} to a file, with
 *       links to open it or copy it.</li>
 *   <li>{@code /rgbprofiles retry} — forgets which lighting software
 *       {@link NativeCrashGuard} is skipping after a crash.</li>
 * </ul>
 *
 * <p>Client commands, so they work on any server, including ones without this
 * mod, and never touch the server.
 *
 * <p>The notices exist because a player with a dark keyboard does not know the
 * command exists. They are said once each — at the first world joined, and
 * when a connection drops or the mod hits an error — and can be turned off
 * with {@code [debug] chatNotices}.
 */
@EventBusSubscriber(modid = RGBProfileMod.MODID, value = Dist.CLIENT)
public final class DiagnosticsCommands {

    private static final String ROOT = "rgbprofiles";

    private static boolean startupNoticeDone;
    private static int seenChanges = -1;
    private static final Map<String, BackendHealth.State> seenStates = new HashMap<>();
    private static int seenProblems;
    private static boolean bugNoticeDone;

    /** When the test this client started began, or {@link Long#MIN_VALUE} for none. */
    private static long testStartMillis = Long.MIN_VALUE;
    private static HardwareTest.Step lastTestStep;

    private DiagnosticsCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT)
                .executes(ctx -> help())
                .then(Commands.literal("status").executes(ctx -> status()))
                .then(Commands.literal("test").executes(ctx -> test()))
                .then(Commands.literal("effects").executes(ctx -> effects()))
                .then(Commands.literal("report").executes(ctx -> report()))
                .then(Commands.literal("retry").executes(ctx -> retry())));
    }

    // ---------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------

    private static int help() {
        say(title("Everything RGB Profiles"));
        say(Component.literal(" ").append(command("status")).append(gray(" — what is lighting your keyboard, or why nothing is")));
        say(Component.literal(" ").append(command("test")).append(gray(" — check the keyboard shows the right colours in the right places")));
        say(Component.literal(" ").append(command("effects")).append(gray(" — which lighting effects are running, and what just changed")));
        say(Component.literal(" ").append(command("report")).append(gray(" — write a file to send with a bug report")));
        say(Component.literal(" ").append(command("retry")).append(gray(" — try lighting software again that was skipped after a crash")));
        return 1;
    }

    private static int status() {
        say(title("Lighting status"));

        if (BackendHealth.gateSummary() != null) {
            say(yellow(BackendHealth.gateSummary()));
            if (BackendHealth.gateFix() != null) say(fix(BackendHealth.gateFix()));
            return 1;
        }
        if (!BackendHealth.workerStarted()) {
            say(yellow("Lighting has not started yet."));
            return 1;
        }
        if (!BackendHealth.connectFinished()) {
            say(yellow("Still connecting to lighting software. Try again in a few seconds."));
            return 1;
        }

        String leader = BackendHealth.leaderId();
        List<BackendHealth.Diagnosis> all = BackendHealth.diagnoses();
        SdkWorkerThread.Status worker = SdkWorkerThread.status();

        if (leader == null) {
            say(Component.literal("No lighting software connected, so nothing will light. Here is what was found:")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            BackendHealth.Diagnosis lead = find(all, leader);
            MutableComponent line = Component.literal("Lighting through ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(lead == null ? leader : lead.displayName()).withStyle(ChatFormatting.WHITE));
            if (worker != null && !worker.grid().isEmpty()) {
                line.append(gray(" — " + worker.grid().surfaces().size() + " device(s), "
                        + worker.grid().allKeys().size() + " LEDs"));
            }
            say(line);
            if (!BackendHealth.tested(leader)) {
                say(gray("This one is experimental: only Corsair iCUE has been tested. If colours or positions "
                        + "look wrong, run ").append(command("test")).append(gray(".")));
            }
            if (!BackendHealth.followerIds().isEmpty()) {
                say(gray("Also showing the same lighting on: " + String.join(", ", BackendHealth.followerIds())));
            }
        }

        // With lighting working, software that is simply absent is noise, so
        // it collapses into one line with the details on hover. With nothing
        // working, every one of them might be the answer, so each gets shown.
        List<BackendHealth.Diagnosis> quiet = new ArrayList<>();
        for (BackendHealth.Diagnosis d : all) {
            if (d.backendId().equals(leader)) continue;
            boolean absent = d.state() == BackendHealth.State.NOT_INSTALLED
                    || d.state() == BackendHealth.State.NOT_RUNNING
                    || d.state() == BackendHealth.State.NOT_SELECTED;
            if (leader != null && absent) {
                quiet.add(d);
                continue;
            }
            say(Component.literal(" ").append(stateLabel(d)).append(Component.literal(" " + d.displayName() + ": ")
                    .withStyle(ChatFormatting.WHITE)).append(gray(d.summary())));
            if (d.fix() != null && d.state() != BackendHealth.State.CONNECTED) say(fix(d.fix()));
        }
        if (!quiet.isEmpty()) {
            MutableComponent line = gray(" Not in use: ");
            for (int i = 0; i < quiet.size(); i++) {
                BackendHealth.Diagnosis d = quiet.get(i);
                if (i > 0) line.append(gray(", "));
                String hover = d.summary() + (d.fix() == null ? "" : "\n\n" + d.fix());
                line.append(Component.literal(d.displayName() + " (" + d.state().label() + ")")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withUnderlined(true)
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))));
            }
            say(line);
        }

        if (worker != null && worker.stoppedBecause() != null && !worker.stoppedBecause().contains("shutting down")) {
            say(Component.literal(" " + worker.stoppedBecause()).withStyle(ChatFormatting.RED));
        }
        int errors = 0;
        for (BackendHealth.Problem p : BackendHealth.problems()) errors += p.count();
        if (errors > 0) {
            say(Component.literal(" " + errors + " error(s) recorded this session. ").withStyle(ChatFormatting.GOLD)
                    .append(command("report")).append(gray(" has the details.")));
        }
        return 1;
    }

    private static int test() {
        SdkWorkerThread.Status worker = SdkWorkerThread.status();
        if (worker == null || !worker.connected()) {
            say(Component.literal("There is no connected lighting to test. ").withStyle(ChatFormatting.YELLOW)
                    .append(command("status")).append(gray(" says why.")));
            return 0;
        }
        long now = System.currentTimeMillis();
        if (!SdkWorkerThread.startHardwareTest(now)) {
            say(yellow("The lighting is not ready for a test yet. Try again in a moment."));
            return 0;
        }
        testStartMillis = now;
        lastTestStep = null;
        say(title("Keyboard test"));
        say(gray("About " + Math.round(HardwareTest.totalMillis() / 1000.0) + " seconds. Watch the keyboard: "
                + "what it should show is written above the hotbar. Your normal lighting comes back afterwards."));
        if (worker.grid().namedKey("W") == null) {
            say(gray("This lighting software cannot say where individual letters are, so the last step "
                    + "(W, A, S and D) will stay dark. That is expected."));
        }
        return 1;
    }

    /**
     * For "my effect didn't show". What owns the board says whether an effect
     * never started or started and was drawn over, which is the first thing
     * to know and not something anyone can tell by looking at a keyboard.
     */
    private static int effects() {
        long now = System.currentTimeMillis();
        say(title("Effects"));
        EffectTimeline.Snapshot current = EffectTimeline.current();
        if (current.atMillis() == 0) {
            say(yellow(BackendHealth.workerStarted()
                    ? "Effects have not started yet. Try again in a few seconds."
                    : "Lighting is not running, so no effects are either. "));
            if (!BackendHealth.workerStarted()) say(gray("See ").append(command("status")).append(gray(".")));
            return 1;
        }
        SdkWorkerThread.Status worker = SdkWorkerThread.status();
        if (worker != null && !worker.connected()) {
            say(yellow("No lighting is connected, so these run without reaching a keyboard. ")
                    .append(command("status")).append(gray(" says why.")));
        }
        say(Component.literal("Now: ").withStyle(ChatFormatting.WHITE).append(gray(current.board())));
        say(Component.literal("Active: ").withStyle(ChatFormatting.WHITE)
                .append(gray(current.active().isEmpty() ? "nothing" : String.join(", ", current.active()))));

        List<String> off = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (!feature.isAvailable()) continue;
            try {
                if (!feature.isOn()) off.add(feature.name().toLowerCase(java.util.Locale.ROOT));
            } catch (RuntimeException ignored) {
                // Config not readable yet; the report says so.
            }
        }
        if (!off.isEmpty()) {
            say(Component.literal("Turned off in the config: ").withStyle(ChatFormatting.WHITE)
                    .append(gray(String.join(", ", off))));
        }

        List<EffectTimeline.Event> events = EffectTimeline.recent();
        int shown = 0;
        for (int i = events.size() - 1; i >= 0 && shown < 8; i--) {
            EffectTimeline.Event e = events.get(i);
            if (now - e.atMillis() > 5 * 60_000) break;
            if (shown == 0) say(Component.literal("Latest changes:").withStyle(ChatFormatting.WHITE));
            MutableComponent line = Component.literal(" " + ago(now - e.atMillis()) + " ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal((e.started() ? "started " : "stopped ") + e.effectId())
                            .withStyle(e.started() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            if (e.started()) {
                line.withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
                        Component.literal(e.tier() + ", priority " + e.priority() + "\nJust after: " + e.board()))));
            }
            say(line);
            shown++;
        }
        if (shown == 0) say(gray("No effects have started or stopped in the last five minutes."));
        say(gray("Hover a change to see who owned the board just after. ").append(command("report"))
                .append(gray(" includes the last " + events.size() + " changes.")));
        return 1;
    }

    private static int retry() {
        List<String> cleared = NativeCrashGuard.clearAll(RGBProfileFiles.dllExtractDirectory());
        if (cleared.isEmpty()) {
            say(prefix().append(gray("Nothing is being skipped after a crash.")));
            return 0;
        }
        say(prefix().append(Component.literal("Cleared. " + String.join(", ", cleared)
                + " will be tried again when Minecraft next starts.").withStyle(ChatFormatting.GREEN)));
        say(gray("If the game crashes again while starting, it will be skipped again automatically. Please send us "
                + "the report and the hs_err_pid crash log from your game folder if that happens."));
        return 1;
    }

    private static String ago(long millis) {
        long seconds = millis / 1000;
        return seconds < 60 ? seconds + "s ago" : (seconds / 60) + "m ago";
    }

    private static int report() {
        String text;
        try {
            text = DiagnosticReport.build();
        } catch (RuntimeException e) {
            RGBProfileMod.LOGGER.error("RGB Profile: building the diagnostic report failed.", e);
            say(red("Could not build the report: " + e + ". Please send latest.log instead."));
            return 0;
        }
        Path path;
        try {
            path = DiagnosticReport.write(text);
        } catch (IOException e) {
            RGBProfileMod.LOGGER.error("RGB Profile: writing the diagnostic report failed.", e);
            say(red("Could not write the report file (" + e.getMessage() + "). ")
                    .append(link("[Copy it instead]", new ClickEvent.CopyToClipboard(text), "Copy the report")));
            return 0;
        }
        // Printed to the log as well, so a player who sends latest.log instead
        // of the report still sends the report.
        RGBProfileMod.LOGGER.info("RGB Profile: diagnostic report written to {}:\n{}", path, text);

        say(title("Diagnostic report"));
        for (String conclusion : DiagnosticReport.conclusions()) say(gray("• " + conclusion));
        say(Component.literal("Saved as logs/" + path.getFileName() + " ").withStyle(ChatFormatting.WHITE)
                .append(link("[Open]", new ClickEvent.OpenFile(path), "Open the report"))
                .append(" ")
                .append(link("[Folder]", new ClickEvent.OpenFile(path.getParent()), "Open the logs folder"))
                .append(" ")
                .append(link("[Copy]", new ClickEvent.CopyToClipboard(text), "Copy the whole report to paste somewhere")));
        say(gray("Attach this file when reporting a lighting problem. It has no personal details in it."));
        return 1;
    }

    // ---------------------------------------------------------------
    // Notices and the test's step captions
    // ---------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        showTestStep(mc);
        if (!RGBProfileConfig.DEBUG_CHAT_NOTICES.get()) return;
        int changes = BackendHealth.changes();
        if (changes == seenChanges && startupNoticeDone) return;
        if (!startupNoticeDone) {
            startupNotice();
        } else {
            changeNotices();
        }
        if (startupNoticeDone) seenChanges = changes;
    }

    private static void showTestStep(Minecraft mc) {
        if (testStartMillis == Long.MIN_VALUE) return;
        long elapsed = System.currentTimeMillis() - testStartMillis;
        HardwareTest.Step step = HardwareTest.stepAt(elapsed);
        if (step == null) {
            testStartMillis = Long.MIN_VALUE;
            mc.gui.setOverlayMessage(Component.empty(), false);
            say(Component.literal("Keyboard test finished. ").withStyle(ChatFormatting.GREEN)
                    .append(gray("If any step did not match, run ")).append(command("report"))
                    .append(gray(" and tell us which step looked wrong and what you saw instead.")));
            return;
        }
        if (step != lastTestStep) {
            lastTestStep = step;
            say(gray("Test " + (step.ordinal() + 1) + "/" + HardwareTest.Step.values().length + ": "
                    + step.expectation()));
        }
        mc.gui.setOverlayMessage(Component.literal("Keyboard test " + (step.ordinal() + 1) + "/"
                + HardwareTest.Step.values().length + ": " + step.expectation()).withStyle(ChatFormatting.AQUA), false);
    }

    /** Said once, at the first world joined after the lighting settles. */
    private static void startupNotice() {
        if (BackendHealth.gateSummary() != null) {
            // Turning the mod off is a choice that needs no announcing; running
            // on an unsupported system is not, so that one is said once.
            if (BackendHealth.gateFix() == null) {
                say(prefix().append(gray(BackendHealth.gateSummary() + " The rest of the mod does nothing here.")));
            }
            startupNoticeDone = true;
            return;
        }
        if (!BackendHealth.workerStarted() || !BackendHealth.connectFinished()) return;

        List<BackendHealth.Diagnosis> all = BackendHealth.diagnoses();
        String leader = BackendHealth.leaderId();
        boolean modFault = false;
        List<String> crashed = new ArrayList<>();
        for (BackendHealth.Diagnosis d : all) {
            modFault |= d.state().modFault();
            if (d.state() == BackendHealth.State.CRASHED) crashed.add(d.displayName());
            seenStates.put(d.backendId(), d.state());
        }
        if (!crashed.isEmpty()) {
            say(prefix().append(yellow("Minecraft crashed while connecting to " + String.join(" and ", crashed)
                    + " last time, so it was skipped to keep the game running. ")).append(command("status"))
                    .append(gray(" says what to do.")));
        }
        seenProblems = BackendHealth.problems().size();

        if (leader == null && rgbSoftwareFound(all)) {
            say(prefix().append(yellow("No keyboard lighting connected. ")).append(command("status"))
                    .append(gray(" says why and what to try.")));
        } else if (leader != null && !BackendHealth.tested(leader)) {
            BackendHealth.Diagnosis lead = find(all, leader);
            say(prefix().append(gray("Lighting through " + (lead == null ? leader : lead.displayName())
                    + ", which is experimental: only Corsair iCUE has been tested. If something looks wrong, "))
                    .append(command("test")).append(gray(" and ")).append(command("report")).append(gray(" help us fix it.")));
        }
        if (modFault) {
            say(prefix().append(red("Some lighting support failed because of a bug in this mod. "))
                    .append(command("report")).append(gray(" writes a file you can send us.")));
        }
        startupNoticeDone = true;
    }

    /**
     * Whether this player looks like they have RGB lighting software at all.
     *
     * <p>The mod ships in modpacks, where most players own no RGB keyboard, and
     * telling every one of them "no lighting connected" on every launch would
     * be noise they learn to ignore. So the notice is kept for players with
     * something to fix: lighting software that was found but did not connect.
     * Not installed says nothing was found. Not running is ambiguous for
     * Razer and OpenRGB, which are network servers and cannot tell "not
     * running" from "not installed", so only the other three count it.
     */
    private static boolean rgbSoftwareFound(List<BackendHealth.Diagnosis> all) {
        for (BackendHealth.Diagnosis d : all) {
            switch (d.state()) {
                case NOT_INSTALLED, NOT_SELECTED, WAITING -> {
                }
                case NOT_RUNNING -> {
                    if (!d.backendId().equals("razer") && !d.backendId().equals("openrgb")) return true;
                }
                default -> {
                    return true;
                }
            }
        }
        return false;
    }

    /** After startup: a connection dropping, or the mod hitting an error, is said as it happens. */
    private static void changeNotices() {
        for (BackendHealth.Diagnosis d : BackendHealth.diagnoses()) {
            BackendHealth.State before = seenStates.put(d.backendId(), d.state());
            if (before == d.state()) continue;
            if (d.state() == BackendHealth.State.LOST) {
                say(prefix().append(yellow("Lost " + d.displayName() + ". ")).append(gray(d.summary() + " "
                        + (d.fix() == null ? "" : d.fix()))));
            } else if (d.state() == BackendHealth.State.BROKEN) {
                say(prefix().append(red(d.displayName() + " stopped because of a bug in this mod. "))
                        .append(command("report")).append(gray(" writes a file you can send us.")));
            }
        }
        List<BackendHealth.Problem> problems = BackendHealth.problems();
        if (problems.size() > seenProblems && !bugNoticeDone) {
            for (BackendHealth.Problem p : problems.subList(Math.min(seenProblems, problems.size()), problems.size())) {
                if (!p.modFault()) continue;
                say(prefix().append(red("The lighting hit an error (a bug in this mod). "))
                        .append(command("report")).append(gray(" writes a file you can send us.")));
                // Once per session: an effect that fails keeps failing, and
                // one message says everything the next hundred would.
                bugNoticeDone = true;
                break;
            }
        }
        seenProblems = problems.size();
    }

    // ---------------------------------------------------------------
    // Chat helpers
    // ---------------------------------------------------------------

    private static BackendHealth.Diagnosis find(List<BackendHealth.Diagnosis> all, String id) {
        for (BackendHealth.Diagnosis d : all) {
            if (d.backendId().equals(id)) return d;
        }
        return null;
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(message, false);
        } else {
            mc.gui.getChat().addMessage(message);
        }
    }

    private static MutableComponent stateLabel(BackendHealth.Diagnosis d) {
        ChatFormatting colour = switch (d.state()) {
            case CONNECTED -> ChatFormatting.GREEN;
            case BROKEN -> ChatFormatting.RED;
            case LOST, REFUSED, NO_DEVICES, DISABLED, MISCONFIGURED, UNSUPPORTED -> ChatFormatting.GOLD;
            default -> ChatFormatting.YELLOW;
        };
        return Component.literal("[" + d.state().label() + "]").withStyle(colour);
    }

    private static MutableComponent prefix() {
        return Component.literal("[RGB Profiles] ").withStyle(ChatFormatting.DARK_AQUA);
    }

    private static MutableComponent title(String text) {
        return prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
    }

    private static MutableComponent command(String sub) {
        String full = "/" + ROOT + " " + sub;
        return link(full, new ClickEvent.RunCommand(full), "Click to run " + full);
    }

    /**
     * @param click what clicking the text does. A whole {@code ClickEvent}
     *              rather than an action and a string: since 1.21.5 the two
     *              are one object per kind of click, each carrying the value
     *              in the type it actually is — a URI for a link, a File for a
     *              file — so an action and a loose string can no longer be
     *              paired up here.
     */
    private static MutableComponent link(String text, ClickEvent click, String hover) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static MutableComponent fix(String text) {
        return Component.literal("   → " + text).withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent yellow(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent red(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }
}
