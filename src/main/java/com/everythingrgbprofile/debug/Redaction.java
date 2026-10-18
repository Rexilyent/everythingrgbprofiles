package com.everythingrgbprofile.debug;

import com.everythingrgbprofile.config.RGBProfileConfig;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the bits that say whose machine this is out of text that's about to
 * be logged, shown, or written into the report.
 *
 * <p>The rule is simple: if it helps somebody fix a dark keyboard, it stays.
 * If it only tells them who owns the keyboard, it goes. Paths keep their
 * shape and lose the username. A custom OpenRGB address becomes a
 * placeholder. Versions, error names, stack frames and ports all stay,
 * because those are the actual diagnosis.
 *
 * <p>{@link com.everythingrgbprofile.sdk.BackendHealth} runs everything it
 * stores through here, so the log, {@code /rgbprofiles status} and the report
 * all read the cleaned version. The report runs its finished text through
 * here again anyway, for the lines it builds itself. Redacting twice is free.
 * Leaking once isn't.
 */
public final class Redaction {

    /** Windows ({@code C:\\Users\\name}), macOS ({@code /Users/name}) and Linux ({@code /home/name}) home folders. */
    private static final Pattern ANY_HOME = Pattern.compile(
            "(?i)(\\b[a-z]:[\\\\/]+users[\\\\/]+|/users/|/home/)([^\\\\/\\s\"':;]+)");

    private Redaction() {
    }

    /**
     * Takes the user's name out of every path, and a custom OpenRGB address
     * out of everywhere.
     *
     * <p>Their own home folder becomes {@code ~}. Anything else that looks
     * like a home folder (another drive, another account, a path some vendor
     * message spelled with forward slashes) keeps its shape and loses the name,
     * so {@code C:\\Users\\Alex\\...} reads {@code C:\\Users\\<user>\\...}. A path
     * is still useful for working out which file went wrong; whose machine it
     * was on is not.
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        String home = System.getProperty("user.home");
        if (home != null && home.length() > 3) {
            // Both slash directions, because Java, JNA and the vendor SDKs do
            // not agree on which one a Windows path uses.
            for (String form : new String[]{home, home.replace('\\', '/'), home.replace('/', '\\')}) {
                text = Pattern.compile(Pattern.quote(form), Pattern.CASE_INSENSITIVE)
                        .matcher(text).replaceAll(Matcher.quoteReplacement("~"));
            }
        }
        text = ANY_HOME.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "<user>"));
        return scrubOpenRgbHost(text);
    }

    /**
     * A full stack trace, scrubbed.
     *
     * <p>Handing the throwable straight to the logger prints its message
     * as-is, and exception messages love quoting paths and hostnames back at
     * you. So the trace gets turned into text first, every frame of it, and
     * cleaned like everything else. Nothing a developer needs goes missing:
     * the frames are all still there, just minus somebody's username.
     */
    public static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return scrub(out.toString().stripTrailing());
    }

    /**
     * Swaps a custom OpenRGB address for a placeholder.
     *
     * <p>Point OpenRGB at another box on the network and its LAN IP (or
     * hostname, which is somehow worse, it's usually "DAVES-PC") turns up in
     * the settings, the changed-settings list, and every OpenRGB message that
     * says where it knocked. Literally nobody debugging a keyboard needs that.
     * What they need is "is it the default or not", so loopback stays visible
     * and anything else becomes {@code <openrgb host>}. The port stays too,
     * because a wrong port is an actual fixable mistake and also not a secret.
     */
    private static String scrubOpenRgbHost(String text) {
        String host;
        try {
            host = RGBProfileConfig.OPENRGB_HOST.get();
        } catch (Throwable t) {
            // Config not loaded yet, which means OpenRGB hasn't been pointed
            // anywhere from it either. Nothing to hide. Throwable, not
            // RuntimeException: outside a loaded game (the tuner, the probes)
            // touching a Forge config throws an Error, and this runs on every
            // single thing BackendHealth records.
            return text;
        }
        if (host == null) return text;
        host = host.trim();
        if (host.isEmpty() || host.equals("127.0.0.1") || host.equals("::1")
                || host.equalsIgnoreCase("localhost")) {
            return text;
        }
        // Edges block on hostname characters so a host like "pc" leaves the
        // word "PCIe" alone. A short hostname can still collide with a word
        // standing on its own; redacting one word too many is the right way
        // round to be wrong here.
        Pattern exact = Pattern.compile("(?<![\\w.-])" + Pattern.quote(host) + "(?![\\w-]|\\.\\w)",
                Pattern.CASE_INSENSITIVE);
        return exact.matcher(text).replaceAll(Matcher.quoteReplacement("<openrgb host>"));
    }
}
