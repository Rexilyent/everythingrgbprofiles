package com.everythingrgbprofile.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SteelSeries GG / Engine, through GameSense.
 *
 * <p>GameSense is a REST server that SteelSeries GG runs on localhost, so this
 * needs no DLL and nothing beyond what a SteelSeries owner already has
 * installed. Full per-key control goes through the "bitmap" handler: register
 * one event bound to {@code rgb-per-key-zones} in {@code bitmap} mode, then send
 * that event with a 22x6 grid of {@code [r, g, b]} triples, row-major from the
 * top-left.
 *
 * <p>Protocol details are from SteelSeries' own documentation in the
 * {@code gamesense-sdk} repository: the address file, the handler JSON, the
 * bitmap shape and the fifteen-second deinitialise timer.
 *
 * <h2>What has been checked against a real GG install</h2>
 * The only keyboard this project has been tested on is a Corsair K70 RGB
 * RAPIDFIRE, so this backend was checked against a real GG install with no
 * SteelSeries device attached. Run through this class against it: the
 * metadata, bind, event, heartbeat and
 * {@code stop_game} calls are all accepted, and forty-nine changing frames over
 * three seconds went through without the transport dropping. {@code stop_game}
 * is a real endpoint and not just an echo — an unknown path gets a 404.
 *
 * <p><b>What none of that proves.</b> GameSense answers HTTP 200 to a bitmap
 * with 131 entries, to {@code [r, g]} pairs instead of triples, and to an
 * event name that was never registered in the first place. It echoes the
 * payload straight back and validates precisely nothing.
 *
 * <p>So "accepted" proves the connection exists and says absolutely nothing
 * about the colours. The payload shape rests entirely on SteelSeries' own
 * documentation until somebody with a SteelSeries keyboard sits and watches it
 * light up.
 *
 * <p>It also means {@link #transportLost} can notice GG closing and can never,
 * under any circumstances, notice a wrong payload.
 *
 * <h2>The first thing to check if a SteelSeries board stays dark</h2>
 * GG reports this app as {@code "enabled": false} straight after registering,
 * and it was still false after those forty-nine frames. What that means could
 * not be settled here: GG shows no Engine page at all until a SteelSeries device
 * is connected, so on a machine without one there is nothing to inspect and no
 * toggle to try. The likelier reading is that the flag reflects "no device to
 * run this on" rather than "switched off by default" — but if a real board stays
 * dark, check GG's Engine page for "Everything RGB Profiles" and whether it
 * needs enabling. There is no documented call to enable it from here, and
 * guessing at an undocumented one to flip a user's setting is not on the table.
 *
 * <p>Frame rate is the other lever: GameSense documents no limit, and this
 * sends at up to twenty frames a second.
 */
public final class SteelSeriesBackend extends GridBackend {

    /** GameSense restricts names to A-Z, 0-9, hyphen and underscore. */
    private static final String GAME = "EVERYTHINGRGBPROFILES";
    private static final String EVENT = "FRAME";

    private final LocalHttp http = new LocalHttp();
    private final LocalHttp.Lane frames = http.lane();
    private final LocalHttp.Lane beats = http.lane();
    private String base;

    public SteelSeriesBackend() {
        // 22x6, up to 20 frames a second, heartbeat after 8s of silence. A
        // delivered frame counts as activity, so a busy board never heartbeats
        // and a static one does just often enough to beat the 15s timeout.
        super("steelseries:keyboard", 22, 6, 20, 8000, true);
    }

    @Override
    public String id() {
        return "steelseries";
    }

    @Override
    public String displayName() {
        return "SteelSeries GameSense";
    }

    @Override
    protected String modelName() {
        return "SteelSeries GameSense keyboard";
    }

    @Override
    protected void open() throws Exception {
        String programData = System.getenv("PROGRAMDATA");
        if (programData == null) programData = "C:\\ProgramData";
        Path props = Path.of(programData, "SteelSeries", "SteelSeries Engine 3", "coreProps.json");
        // The file exists whenever GG is installed and names the port the
        // server is listening on. It survives GG being closed, so its existence
        // proves nothing — the metadata call below is the real test.
        if (!Files.isRegularFile(props)) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_INSTALLED,
                    "SteelSeries GG does not look installed: the file it leaves its address in is missing.",
                    "If you have a SteelSeries keyboard, install SteelSeries GG. If not, ignore this line.",
                    "Looked for: " + props);
        }
        JsonObject root = JsonParser.parseString(Files.readString(props, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has("address")) {
            throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                    "SteelSeries GG's address file has no address in it.",
                    "Start SteelSeries GG, which rewrites that file, then restart Minecraft.",
                    props + ": " + BackendHealth.clip(root.toString()));
        }
        // GG writes "127.0.0.1:<port>" here and has never been seen writing
        // anything else. But it's a plain file in ProgramData, and whatever
        // it says is where every frame and heartbeat gets POSTed for the rest
        // of the session, so it has to name this computer or it gets ignored.
        // Frames are only colours, nothing secret, but a lighting mod sending
        // HTTP to some host it read out of a file is still a bad look.
        String address = root.get("address").getAsString().trim();
        if (!isLoopbackAddress(address)) {
            // The address itself stays out of the detail. GG never writes a
            // non-loopback one, so anything that shows up here came from
            // somewhere else, and nobody pasting a report should be handing
            // it out.
            throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                    "SteelSeries GG's address file points somewhere other than this computer, so it was ignored.",
                    "Restart SteelSeries GG, which rewrites that file. If this keeps coming back, something other than GG is editing it.",
                    "Looked in: " + props);
        }
        base = "http://" + address;

        try {
            http.send("POST", base + "/game_metadata",
                    "{\"game\":\"" + GAME + "\",\"game_display_name\":\"Everything RGB Profiles\","
                            + "\"developer\":\"Everything RGB Profiles\",\"deinitialize_timer_length_ms\":15000}",
                    1500);
            http.send("POST", base + "/bind_game_event",
                    "{\"game\":\"" + GAME + "\",\"event\":\"" + EVENT + "\",\"value_optional\":true,"
                            + "\"handlers\":[{\"device-type\":\"rgb-per-key-zones\",\"mode\":\"bitmap\"}]}",
                    1500);
        } catch (LocalHttp.HttpStatusException e) {
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "SteelSeries GG is running but rejected this mod's registration (HTTP " + e.status + ").",
                    BackendHealth.SEND_REPORT, e.getMessage());
        }
    }

    /**
     * Whether coreProps' address is a host and a port on this computer, and
     * nothing else. No scheme, no path, no userinfo, no port-less "just trust
     * me". GG always names its port, so a missing one is wrong too.
     *
     * <p>Literals only, plus {@code localhost}. A hostname would mean a DNS
     * lookup to find out where it goes, and asking the network whether a
     * name is local is precisely the thing this check exists to avoid.
     */
    static boolean isLoopbackAddress(String address) {
        URI uri;
        try {
            uri = new URI("http://" + address);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty()) return false;
        int port = uri.getPort();
        if (port < 1 || port > 65535) return false;

        String host = uri.getHost();
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        // IPv6 comes back still wearing its brackets.
        if (host.startsWith("[") && host.endsWith("]")) {
            String v6 = host.substring(1, host.length() - 1);
            try {
                // Has a colon, so this parses a literal and never does a lookup.
                return InetAddress.getByName(v6).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        // IPv4 by hand, all four parts, so nothing gets a chance to read
        // "127.1" or "0x7f.0.0.1" generously.
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return parts[0].equals("127");
    }

    @Override
    protected String notRunningSummary() {
        return "SteelSeries GG is installed, but nothing answered at "
                + (base == null ? "its address" : base) + ", so it is probably not running.";
    }

    @Override
    protected String notRunningFix() {
        return "Start SteelSeries GG, then restart Minecraft.";
    }

    @Override
    protected String transportDetail() {
        return "Frames failing in a row: " + frames.consecutiveFailures() + ", last: " + frames.lastFailure();
    }

    @Override
    protected boolean push(int[] rgb) {
        StringBuilder json = new StringBuilder(rgb.length * 14 + 96);
        json.append("{\"game\":\"").append(GAME).append("\",\"event\":\"").append(EVENT)
                .append("\",\"data\":{\"frame\":{\"bitmap\":[");
        for (int i = 0; i < rgb.length; i++) {
            if (i > 0) json.append(',');
            int c = rgb[i];
            json.append('[').append((c >> 16) & 0xFF).append(',')
                    .append((c >> 8) & 0xFF).append(',')
                    .append(c & 0xFF).append(']');
        }
        json.append("]}}}");
        return frames.fire("POST", base + "/game_event", json.toString(), 500);
    }

    @Override
    protected void keepAlive() {
        beats.fire("POST", base + "/game_heartbeat", "{\"game\":\"" + GAME + "\"}", 500);
    }

    @Override
    protected boolean transportLost() {
        // About two seconds of solid failure at twenty frames a second. Long
        // enough to ride out GG hitching, short enough that a closed GG does not
        // leave the backend firing into the void for the rest of the session.
        return frames.consecutiveFailures() >= 40;
    }

    @Override
    protected void close() {
        if (base == null) return;
        try {
            http.send("POST", base + "/stop_game", "{\"game\":\"" + GAME + "\"}", 800);
        } catch (Exception ignored) {
            // See the class notes: the deinitialise timer covers this.
        }
    }
}
