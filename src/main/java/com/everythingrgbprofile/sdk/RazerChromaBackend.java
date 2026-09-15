package com.everythingrgbprofile.sdk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Razer Synapse, through the Chroma SDK's REST interface.
 *
 * <p>Synapse runs a Chroma server on {@code localhost:54235}. POSTing an
 * application description to it opens a session on a fresh port; the keyboard
 * then takes {@code CHROMA_CUSTOM} effects — a 6x22 grid of colours — PUT to
 * that session, which must be heartbeated at least every fifteen seconds and is
 * released with a DELETE. No DLL, and nothing to install beyond Synapse and
 * its Chroma module — see below for why that second part matters.
 *
 * <p>Everything here is taken from Razer's REST documentation and the
 * {@code RzChromaSDKTypes.h} header: the init body, the grid size, the colour
 * packing and the key table.
 *
 * <h2>Colour packing, and why it is spelt out</h2>
 * Razer's header defines a colour as "1st byte = Red; 2nd byte = Green; 3rd
 * byte = Blue", so the integer is {@code r | g << 8 | b << 16} and 255 is pure
 * red. The documentation also calls this "BGR", which is true if you read the
 * hex digits left to right, and it is easy to misread as putting blue in the
 * low byte. Get this backwards and every biome ships with
 * red and blue swapped: a working board in entirely the wrong colours, with no
 * error anywhere.
 *
 * <h2>Synapse alone is not enough</h2>
 * The REST server is part of Synapse's <b>Chroma</b> module, not Synapse
 * itself. On the test machine Synapse 4 was installed and running
 * ({@code RazerAppEngine}), yet nothing listened on 54235 or 54236 and no Chroma
 * service or DLL existed — the Chroma module had not been installed. Most Razer
 * owners who care about RGB will already have it, because Synapse's own
 * lighting effects need it too, but "Synapse is installed" and "this backend
 * can connect" are different claims. When it cannot, auto mode simply moves on.
 *
 * <h2>What has been checked against a real Chroma server</h2>
 * Once the Chroma module was installed on the test machine — which has no Razer
 * keyboard; the only keyboard this project has been tested on is a Corsair K70
 * RGB RAPIDFIRE — init returns a session, the readiness wait gets it
 * answering, forty-nine changing frames went through this class, and the
 * session heartbeats and closes cleanly.
 *
 * <p>Unlike SteelSeries, Razer <b>validates</b> what it is sent, which makes
 * this the best-checked grid backend. Our 6x22 grid comes back
 * {@code "result": 0}. Five rows, twenty-one columns or a flat list come back
 * {@code "result": 87} with "expecting a 2 dimensional array of 6 (rows) x 22
 * (columns) elements with integer values", and an unknown effect gets 50. So
 * the payload shape is confirmed by Razer's own validator, not just accepted.
 *
 * <p>The catch: every one of those rejections arrives as HTTP <b>200</b>. That is
 * why frames are checked for {@code result 0} rather than for status — without
 * it, an expired or rejected session would fail every frame, silently, for the
 * rest of the game.
 *
 * <p>Still unverified: the validator checks the grid's shape, not what the
 * integers mean. That red is the low byte rests on the header, and needs a
 * Razer keyboard showing red to settle.
 */
public final class RazerChromaBackend extends GridBackend {

    private static final String INIT_URL = "http://localhost:54235/razer/chromasdk";

    /** Razer's success marker. Anything else, including a 200, is a rejection. */
    private static final java.util.regex.Pattern RESULT_OK =
            java.util.regex.Pattern.compile("\"result\"\\s*:\\s*0\\b");

    private static final String INIT_BODY = "{"
            + "\"title\":\"Everything RGB Profiles\","
            + "\"description\":\"Minecraft world state on your keyboard\","
            + "\"author\":{\"name\":\"Everything RGB Profiles\",\"contact\":\"https://modrinth.com\"},"
            + "\"device_supported\":[\"keyboard\",\"mouse\",\"headset\",\"mousepad\",\"keypad\",\"chromalink\"],"
            + "\"category\":\"application\"}";

    private final LocalHttp http = new LocalHttp();
    private final LocalHttp.Lane frames = http.lane();
    private final LocalHttp.Lane beats = http.lane();
    private String session;

    public RazerChromaBackend() {
        // 22 columns x 6 rows. Heartbeat every 5s whether or not frames are
        // flowing: Razer describe the heartbeat as its own keep-alive, and a
        // frame arriving is not documented as counting.
        super("razer:keyboard", 22, 6, 20, 5000, false);
    }

    @Override
    public String id() {
        return "razer";
    }

    @Override
    public String displayName() {
        return "Razer Chroma";
    }

    @Override
    protected String modelName() {
        return "Razer Chroma keyboard";
    }

    @Override
    protected void open() throws Exception {
        String body;
        try {
            body = http.send("POST", INIT_URL, INIT_BODY, 1500);
        } catch (LocalHttp.HttpStatusException e) {
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "Razer's Chroma server is running but rejected this mod's registration (HTTP " + e.status + ").",
                    BackendHealth.SEND_REPORT, e.getMessage());
        }
        JsonObject reply = JsonParser.parseString(body).getAsJsonObject();
        if (!reply.has("uri")) {
            throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                    "Razer's Chroma server answered but did not open a session.",
                    "Restart Razer Synapse, then restart Minecraft. If it keeps happening, "
                            + BackendHealth.SEND_REPORT,
                    "Reply: " + BackendHealth.clip(body));
        }
        session = reply.get("uri").getAsString();

        // The session port is opened asynchronously; calling it immediately can
        // be refused. Probe with the cheapest call Razer offers until it
        // answers, for at most about a second and a half.
        Exception last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                http.send("PUT", session + "/heartbeat", null, 400);
                return;
            } catch (Exception notYet) {
                last = notYet;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                "Razer's Chroma server opened a session, but the session never answered.",
                "Restart Razer Synapse, then restart Minecraft. If it keeps happening, " + BackendHealth.SEND_REPORT,
                "Session " + session + ", last attempt: " + last);
    }

    @Override
    protected String notRunningSummary() {
        return "Nothing answered on localhost:54235, where Razer Synapse's Chroma SDK server listens.";
    }

    @Override
    protected String notRunningFix() {
        return "If you have a Razer keyboard, start Razer Synapse and make sure its Chroma module is installed "
                + "(Synapse alone is not enough), then restart Minecraft. If not, ignore this line.";
    }

    /**
     * Tells a closed Synapse apart from rejected frames. Heartbeats still
     * getting through while frames fail means Chroma is running and is refusing
     * what this mod sends, which is the mod's to fix, not the player's.
     */
    @Override
    protected void reportLost(String detail) {
        if (frames.consecutiveFailures() > 0 && beats.consecutiveFailures() == 0) {
            BackendHealth.record(id(), displayName(), BackendHealth.State.BROKEN,
                    "Razer Chroma is running but rejected the lighting frames this mod sent.",
                    BackendHealth.SEND_REPORT, detail);
        } else {
            super.reportLost(detail);
        }
    }

    @Override
    protected String transportDetail() {
        return "Frames failing in a row: " + frames.consecutiveFailures() + ", last: " + frames.lastFailure()
                + "\nHeartbeats failing in a row: " + beats.consecutiveFailures() + ", last: " + beats.lastFailure();
    }

    @Override
    protected boolean push(int[] rgb) {
        StringBuilder json = new StringBuilder(rgb.length * 9 + 48);
        json.append("{\"effect\":\"CHROMA_CUSTOM\",\"param\":[");
        for (int r = 0; r < rows; r++) {
            if (r > 0) json.append(',');
            json.append('[');
            for (int c = 0; c < cols; c++) {
                if (c > 0) json.append(',');
                int v = rgb[r * cols + c];
                int red = (v >> 16) & 0xFF, green = (v >> 8) & 0xFF, blue = v & 0xFF;
                json.append(red | green << 8 | blue << 16);
            }
            json.append(']');
        }
        json.append("]}");
        return frames.fire("PUT", session + "/keyboard", json.toString(), 500,
                body -> RESULT_OK.matcher(body).find());
    }

    @Override
    protected void keepAlive() {
        beats.fire("PUT", session + "/heartbeat", null, 500);
    }

    @Override
    protected boolean transportLost() {
        return frames.consecutiveFailures() >= 40 || beats.consecutiveFailures() >= 4;
    }

    @Override
    protected void close() {
        if (session == null) return;
        try {
            http.send("DELETE", session, null, 800);
        } catch (Exception ignored) {
            // Synapse drops the session on its own once heartbeats stop.
        }
        session = null;
    }

    /**
     * Key positions from {@code RzChromaSDKTypes.h}, where each RZKEY value is
     * {@code row << 8 | column}: ESC is 0x0001, Q is 0x0202, A is 0x0302.
     *
     * <p>Only this vendor gets named keys because only this vendor documents
     * them. Logitech and SteelSeries publish the grid size but not which key
     * lands in which cell, and guessing would reintroduce the bug the named-key
     * resolver exists to prevent — a typo'd or misplaced key silently lighting
     * the wrong cell forever. Without them, key-targeted effects fall back to
     * the whole board, which is at least obviously not what was asked for.
     */
    @Override
    protected Map<Character, int[]> namedCells() {
        Map<Character, int[]> m = new HashMap<>();
        row(m, "1234567890", 1, 2);
        row(m, "QWERTYUIOP", 2, 2);
        row(m, "ASDFGHJKL", 3, 2);
        row(m, "ZXCVBNM", 4, 3);
        return m;
    }

    private static void row(Map<Character, int[]> m, String keys, int row, int firstCol) {
        for (int i = 0; i < keys.length(); i++) {
            m.put(keys.charAt(i), new int[]{row, firstCol + i});
        }
    }
}
