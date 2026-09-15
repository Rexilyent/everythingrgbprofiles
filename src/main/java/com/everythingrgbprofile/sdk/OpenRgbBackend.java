package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.keymap.KeyGrid;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * OpenRGB, over its SDK network protocol.
 *
 * <h2>Where this fits</h2>
 * The mod talks to Corsair, Razer, Logitech and SteelSeries directly, through
 * each vendor's own software, so owners of those brands need nothing extra
 * installed. OpenRGB covers everything else: it already drives ASUS, MSI,
 * Gigabyte, HyperX, Roccat and a long tail of other hardware, so this one
 * backend reaches all of it — for users willing to run OpenRGB's SDK server.
 *
 * <p>It also needs no native library and no vendor DLL: it is a TCP socket to
 * {@code 127.0.0.1:6742} speaking a documented binary protocol. That means no
 * JNA, no architecture-specific binaries to ship, and no chance of the exclusive
 * control problems the Corsair path has to be careful about.
 *
 * <h2>Geometry comes from the zone matrix</h2>
 * Everything in this mod is geometry, so the interesting part of the controller
 * data is the per-zone <b>matrix map</b>: a width x height grid whose cells hold
 * LED indices, with {@code 0xFFFFFFFF} for a hole. A keyboard reported that way
 * converts straight into positions, and every pattern works with no further
 * help.
 *
 * <p>Zones without a matrix — strips, fans, single-colour devices — are laid
 * out as one horizontal line beneath the matrix zones. That is not physically
 * accurate and is not trying to be; it exists so those LEDs light at all rather
 * than being dropped, and so a user can see them and decide whether they want a
 * {@link KeyLayout} for that device.
 *
 * <h2>What is verified and what is not</h2>
 * The framing and the controller-data parser were checked against a <b>live
 * OpenRGB v5 server</b>, by decoding every attached controller and requiring
 * the parse to consume exactly the declared byte count. That is what caught the
 * missing {@code vendor} string, which had silently desynced everything after
 * it.
 *
 * <p>Two things remain unverified, because the only keyboard this project has
 * been tested on is a Corsair K70 RGB RAPIDFIRE:
 *
 * <ul>
 *   <li><b>Matrix geometry.</b> On the test machine OpenRGB reports a GPU, a
 *       monitor and a motherboard — none of which expose a matrix map, because
 *       none of them are keyboards. The K70 itself is held by iCUE, and OpenRGB
 *       does not take over a device iCUE is already driving. So the matrix
 *       branch has never run against real data, and the zone-offset assumption
 *       marked below is still an assumption.</li>
 *   <li><b>Writing colour.</b> No LED has been driven through this path. The
 *       packing is the documented {@code 0x00BBGGRR}, but that is a reading of
 *       the protocol, not an observation.</li>
 * </ul>
 */
public final class OpenRgbBackend implements LightingBackend {

    private static final byte[] MAGIC = {'O', 'R', 'G', 'B'};

    // Packet ids, from the OpenRGB SDK protocol.
    private static final int SET_CLIENT_NAME = 50;
    private static final int REQUEST_CONTROLLER_COUNT = 0;
    private static final int REQUEST_CONTROLLER_DATA = 1;
    private static final int REQUEST_PROTOCOL_VERSION = 40;
    private static final int RGBCONTROLLER_UPDATELEDS = 1050;
    private static final int RGBCONTROLLER_SETCUSTOMMODE = 1053;

    /**
     * The protocol revision this client asks for, and deliberately the floor.
     *
     * <p>OpenRGB serialises controller data at {@code min(client, server)}, and
     * the payload has grown over time: protocol 3 inserted mode brightness
     * fields, 4 added per-zone segments, 5 added something this code does not
     * know about. Every one of those is a chance to desync the parser on
     * hardware nobody here owns, and none of them carry anything this mod uses
     * — zones, the matrix map and LED names all exist at version 1.
     *
     * <p>So this asks for 1 and gets the simplest, most stable shape from any
     * server ever released. Verified against a live OpenRGB v5 server: version
     * 1 parsed all three attached controllers to exactly the declared byte
     * count, while 5 could not be parsed at all.
     */
    private static final int CLIENT_PROTOCOL = 1;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int protocol = 1;
    private boolean connected;

    private KeyGrid grid = KeyGrid.empty();

    /** One controller we are driving. */
    private static final class Device {
        int index;
        String name;
        int ledCount;
        KeyGrid.DeviceClass deviceClass;
        /** Reusable colour buffer, so a frame allocates nothing. */
        int[] colors;
        /** ledRef luid -> index into {@link #colors}. */
        Map<Integer, Integer> slotByLuid = new HashMap<>();
    }

    private final List<Device> devices = new ArrayList<>();
    /** Devices left dark because their type is turned off in [devices], for the diagnosis. */
    private final List<String> skippedByClass = new ArrayList<>();

    /** Set by the test constructor to bypass config, null in normal use. */
    private final String hostOverride;
    private final int portOverride;

    public OpenRgbBackend() {
        this(null, 0);
    }

    /**
     * Builds a backend pointed at an explicit address instead of the config.
     *
     * <p>A test seam, and worth having as public API rather than something a
     * harness reaches in and grabs: the protocol code is the part of this class
     * most likely to be wrong and the only part checkable without the hardware,
     * so verifying it must not require a loaded Forge config. {@code
     * tuning/OpenRgbProbe} uses this to run the real parser against a live
     * server.
     */
    public static OpenRgbBackend pointedAt(String host, int port) {
        return new OpenRgbBackend(host, port);
    }

    private OpenRgbBackend(String host, int port) {
        this.hostOverride = host;
        this.portOverride = port;
    }

    @Override
    public String id() {
        return "openrgb";
    }

    @Override
    public String displayName() {
        return "OpenRGB";
    }

    @Override
    public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        String host = hostOverride != null ? hostOverride : RGBProfileConfig.OPENRGB_HOST.get();
        int port = hostOverride != null ? portOverride : RGBProfileConfig.OPENRGB_PORT.get();
        String where = host + ":" + port;
        boolean socketOpen = false;
        try {
            socket = new Socket();
            // A short timeout on purpose: OpenRGB is either running locally or
            // it is not, and a long stall here delays world load for every user
            // who does not have it.
            socket.connect(new InetSocketAddress(host, port), 1200);
            socketOpen = true;
            socket.setSoTimeout(3000);
            socket.setTcpNoDelay(true);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();

            negotiateProtocol();
            send(0, SET_CLIENT_NAME, "Everything RGB Profiles\0".getBytes(StandardCharsets.UTF_8));

            int count = requestControllerCount();
            KeyGrid.Builder builder = new KeyGrid.Builder();
            int unreadable = 0;
            Throwable firstUnreadable = null;
            for (int i = 0; i < count; i++) {
                try {
                    readController(i, builder, deviceEnabled);
                } catch (Throwable t) {
                    // Throwable, not Exception. One unparseable controller must
                    // not cost the user the rest of their hardware, and the
                    // things that go wrong here are not all Exceptions: a
                    // missing class on an unusual classpath arrives as an
                    // Error and would otherwise take the whole backend down
                    // from inside a loop that looks guarded.
                    unreadable++;
                    if (firstUnreadable == null) firstUnreadable = t;
                    BackendHealth.problem("OpenRGB", "Device " + i + " of " + count
                            + " could not be read and was skipped.", t);
                }
            }
            grid = builder.build();
            connected = !devices.isEmpty();
            if (!connected) {
                shutdown();
                if (count == 0) {
                    throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                            "OpenRGB is running but reports no devices.",
                            "Check your hardware shows in OpenRGB's Devices tab. A device already driven by its "
                                    + "own software (iCUE, Synapse, G HUB) may not show up there.",
                            "Server at " + where + ", protocol v" + protocol);
                }
                if (unreadable == count) {
                    throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                            "OpenRGB listed " + count + " device(s), but this mod could not read any of them.",
                            BackendHealth.SEND_REPORT + " Mention which version of OpenRGB you have.",
                            BackendHealth.describe(firstUnreadable));
                }
                if (!skippedByClass.isEmpty()) {
                    throw new BackendHealth.Unavailable(BackendHealth.State.DISABLED,
                            "OpenRGB's devices are all types turned off in the config: " + skippedByClass + ".",
                            "Turn on the matching setting in the [devices] section of "
                                    + "everythingrgbprofiles-common.toml, for example keyboardEnabled.",
                            null);
                }
                throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                        "OpenRGB's devices report no LEDs this mod can light.",
                        "Check the devices have LEDs in OpenRGB. If they light from OpenRGB but not from here, "
                                + BackendHealth.SEND_REPORT,
                        "Server at " + where + ", " + count + " device(s)");
            }
            StringBuilder summary = new StringBuilder()
                    .append(devices.size()).append(" device(s), ").append(grid.allKeys().size()).append(" LEDs");
            if (unreadable > 0) summary.append("; ").append(unreadable).append(" more could not be read");
            if (!skippedByClass.isEmpty()) summary.append("; skipped as turned off in [devices]: ").append(skippedByClass);
            BackendHealth.record(id(), displayName(), BackendHealth.State.CONNECTED, summary.append('.').toString(),
                    null, "Server at " + where + ", protocol v" + protocol);
            return true;
        } catch (BackendHealth.Unavailable u) {
            BackendHealth.record(id(), displayName(), u);
            return false;
        } catch (Exception e) {
            shutdown();
            diagnoseConnectFailure(e, where, socketOpen);
            return false;
        }
    }

    /**
     * Sorts a failed connection into the three cases a player needs told apart:
     * nothing listening, something else listening, and OpenRGB listening but
     * answering in a way this parser does not follow.
     */
    private void diagnoseConnectFailure(Exception e, String where, boolean socketOpen) {
        boolean customAddress = !"127.0.0.1:6742".equals(where) && !"localhost:6742".equals(where);
        String addressHint = customAddress
                ? " Also check openRgbHost and openRgbPort in [hardware] match OpenRGB's SDK Server tab."
                : "";
        if (!socketOpen) {
            String summary = BackendHealth.hasCause(e, java.net.SocketTimeoutException.class)
                    ? "Nothing answered at " + where + " within 1.2 seconds, so OpenRGB's SDK server is not reachable."
                    : "Nothing is listening at " + where + ", so OpenRGB's SDK server is not running.";
            BackendHealth.recordFailure(id(), displayName(), "while connecting", e, summary,
                    "If you use OpenRGB: open it, go to the SDK Server tab and click Start Server, then restart "
                            + "Minecraft. If you do not use OpenRGB, ignore this line." + addressHint);
            return;
        }
        if (e instanceof ProtocolMismatch) {
            BackendHealth.record(id(), displayName(), BackendHealth.State.REFUSED,
                    "Something is listening at " + where + ", but it is not OpenRGB's SDK server.",
                    "Another program may be using that port. Check the port in OpenRGB's SDK Server tab matches "
                            + "openRgbPort in [hardware].",
                    BackendHealth.describe(e));
            return;
        }
        BackendHealth.record(id(), displayName(), BackendHealth.State.BROKEN,
                "OpenRGB's SDK server is running at " + where + ", but this mod could not understand its reply.",
                BackendHealth.SEND_REPORT + " Mention which version of OpenRGB you have.",
                "Protocol v" + protocol + "\n" + BackendHealth.describe(e));
    }

    /** The reply did not start with OpenRGB's magic bytes: not OpenRGB on the other end. */
    private static final class ProtocolMismatch extends IOException {
        ProtocolMismatch(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------
    // Protocol
    // ---------------------------------------------------------------

    private void negotiateProtocol() throws IOException {
        send(0, REQUEST_PROTOCOL_VERSION, le32(CLIENT_PROTOCOL));
        byte[] body = readPacket(REQUEST_PROTOCOL_VERSION);
        int server = body.length >= 4 ? buf(body).getInt() : 1;
        // Speak the lower of the two. Fields were added over time, so parsing
        // a v2 server as v3 reads past the end of every mode block.
        protocol = Math.min(CLIENT_PROTOCOL, Math.max(1, server));
    }

    private int requestControllerCount() throws IOException {
        send(0, REQUEST_CONTROLLER_COUNT, new byte[0]);
        return buf(readPacket(REQUEST_CONTROLLER_COUNT)).getInt();
    }

    private void readController(int index, KeyGrid.Builder builder,
                                Predicate<KeyGrid.DeviceClass> deviceEnabled) throws IOException {
        send(index, REQUEST_CONTROLLER_DATA, protocol >= 1 ? le32(protocol) : new byte[0]);
        ByteBuffer b = buf(readPacket(REQUEST_CONTROLLER_DATA));

        b.getInt();                       // data_size, already implied by the header
        int type = b.getInt();
        // SIX strings, not five. There is a `vendor` between name and
        // description, and omitting it desyncs everything after this point —
        // the parser then reads a mode name as a 20,000-byte string and gives
        // up. Confirmed by decoding a live server: it is present at every
        // protocol version, so it is not conditional.
        String name = readString(b);
        readString(b);                    // vendor
        readString(b);                    // description
        readString(b);                    // version
        readString(b);                    // serial
        readString(b);                    // location

        skipModes(b);

        // --- zones, which is where the geometry lives -------------------
        int zoneCount = b.getShort() & 0xFFFF;
        List<double[]> positions = new ArrayList<>();   // luid, x, y
        int ledOffset = 0;
        double stripY = 0;
        double matrixBottom = 0;

        for (int z = 0; z < zoneCount; z++) {
            readString(b);                // zone name
            b.getInt();                   // zone type
            b.getInt();                   // leds_min
            b.getInt();                   // leds_max
            int ledsCount = b.getInt();
            int matrixLen = b.getShort() & 0xFFFF;

            if (matrixLen > 0) {
                int height = b.getInt();
                int width = b.getInt();
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        long cell = b.getInt() & 0xFFFFFFFFL;
                        if (cell == 0xFFFFFFFFL) continue;   // hole in the matrix
                        // ASSUMPTION, and the one a mock server cannot confirm:
                        // matrix cells hold ZONE-relative LED indices, so the
                        // global index is the running offset plus the cell. If
                        // a real device ever comes out scrambled, this line is
                        // the first thing to check — the alternative reading is
                        // that cells are already global, in which case drop the
                        // offset.
                        int luid = ledOffset + (int) cell;
                        positions.add(new double[]{luid, col, row});
                    }
                }
                matrixBottom = Math.max(matrixBottom, height);
            } else {
                // No matrix: lay the zone out as one row under everything else,
                // purely so its LEDs exist and can be lit.
                for (int i = 0; i < ledsCount; i++) {
                    positions.add(new double[]{ledOffset + i, i, matrixBottom + 1 + stripY});
                }
                stripY += 1;
            }
            ledOffset += ledsCount;
        }

        int ledCount = b.getShort() & 0xFFFF;
        List<String> ledNames = new ArrayList<>(ledCount);
        for (int i = 0; i < ledCount; i++) {
            ledNames.add(readString(b));
            b.getInt();                   // current value
        }

        KeyGrid.DeviceClass cls = deviceClassOf(type);

        // Writing a layout means matching the backend's own LED names, so the
        // names have to be discoverable. KeyLayout's documentation promises
        // this log exists; this is it.
        if (debugEnabled()) {
            RGBProfileMod.LOGGER.info("RGB Profile: OpenRGB [{}] '{}' ({}), {} LEDs:",
                    index, name, cls, ledNames.size());
            for (int i = 0; i < ledNames.size(); i++) {
                RGBProfileMod.LOGGER.info("    {} = \"{}\"", i, ledNames.get(i));
            }
        }

        if (!deviceEnabled.test(cls)) {
            skippedByClass.add(name + " (" + cls.configKey() + ")");
            return;
        }
        if (positions.isEmpty()) return;

        // A user-supplied layout wins outright. This is the escape hatch for
        // hardware OpenRGB reports as a flat strip when it is physically a
        // keyboard, which is common enough to be worth a first-class path.
        String deviceId = "openrgb:" + index;
        List<double[]> laidOut = KeyLayout.positionsFor(name, ledNames, positions);

        builder.beginDevice(deviceId, name, cls);
        Device dev = new Device();
        dev.index = index;
        dev.name = name;
        dev.ledCount = Math.max(ledCount, ledOffset);
        dev.deviceClass = cls;
        dev.colors = new int[dev.ledCount];
        for (double[] p : laidOut) {
            int luid = (int) p[0];
            if (luid < 0 || luid >= dev.ledCount) continue;
            builder.addLed(luid, p[1], p[2]);
            dev.slotByLuid.put(luid, luid);
        }
        builder.endDevice();
        devices.add(dev);

        // Custom mode is what makes a controller accept direct LED writes at
        // all; without it many devices ignore UPDATELEDS entirely and keep
        // running whatever effect OpenRGB had them on.
        try {
            send(index, RGBCONTROLLER_SETCUSTOMMODE, new byte[0]);
        } catch (IOException e) {
            RGBProfileMod.LOGGER.debug("RGB Profile: OpenRGB device {} refused custom mode.", index, e);
        }
    }

    /**
     * Steps over the mode block without interpreting it.
     *
     * <p>Modes are the most version-dependent part of the payload — brightness
     * fields appear at protocol 3, and more arrives later — which is precisely
     * why {@link #CLIENT_PROTOCOL} pins the conversation to version 1. At that
     * version the block is fixed, and since this mod never changes a mode, the
     * only requirement is to land on the right byte afterwards.
     */
    private void skipModes(ByteBuffer b) {
        int modeCount = b.getShort() & 0xFFFF;
        b.getInt();                       // active mode
        for (int i = 0; i < modeCount; i++) {
            readString(b);                // name
            b.getInt();                   // value
            b.getInt();                   // flags
            b.getInt();                   // speed_min
            b.getInt();                   // speed_max
            b.getInt();                   // colors_min
            b.getInt();                   // colors_max
            b.getInt();                   // speed
            b.getInt();                   // direction
            b.getInt();                   // color_mode
            int colors = b.getShort() & 0xFFFF;
            b.position(b.position() + colors * 4);
        }
    }

    @Override
    public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (!connected) return;
        try {
            for (Device dev : devices) {
                boolean dirty = false;
                for (Map.Entry<KeyGrid.LedRef, RGBColor> e : frame.entrySet()) {
                    KeyGrid.LedRef ref = e.getKey();
                    if (!ref.deviceId().equals("openrgb:" + dev.index)) continue;
                    Integer slot = dev.slotByLuid.get(ref.luid());
                    if (slot == null) continue;
                    RGBColor c = e.getValue();
                    // OpenRGB packs colour as 0x00BBGGRR, so red is the low
                    // byte. Getting this backwards produces a board that works
                    // perfectly and is entirely the wrong colour.
                    int packed = (c.r() & 0xFF) | ((c.g() & 0xFF) << 8) | ((c.b() & 0xFF) << 16);
                    if (dev.colors[slot] != packed) {
                        dev.colors[slot] = packed;
                        dirty = true;
                    }
                }
                // Unlike the Corsair path this DOES track dirtiness, because
                // every update here is a full-device packet over a socket
                // rather than a pointer write. Skipping an unchanged device is
                // the difference between one packet a frame and five.
                if (dirty) sendLeds(dev);
            }
        } catch (IOException e) {
            connected = false;
            BackendHealth.record(id(), displayName(), BackendHealth.State.LOST,
                    "OpenRGB closed the connection mid-game: OpenRGB was closed, or its SDK server was stopped.",
                    "Start OpenRGB's SDK server again, then restart Minecraft to reconnect.",
                    BackendHealth.describe(e));
        }
    }

    private void sendLeds(Device dev) throws IOException {
        ByteBuffer body = ByteBuffer.allocate(4 + 2 + dev.colors.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(0);                   // data_size, patched below
        body.putShort((short) dev.colors.length);
        for (int packed : dev.colors) {
            body.put((byte) (packed & 0xFF));
            body.put((byte) ((packed >> 8) & 0xFF));
            body.put((byte) ((packed >> 16) & 0xFF));
            body.put((byte) 0);
        }
        byte[] arr = body.array();
        ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).putInt(arr.length);
        send(dev.index, RGBCONTROLLER_UPDATELEDS, arr);
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public KeyGrid keyGrid() {
        return grid;
    }

    @Override
    public void shutdown() {
        connected = false;
        devices.clear();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // ---------------------------------------------------------------
    // Wire helpers
    // ---------------------------------------------------------------

    private void send(int deviceId, int packetId, byte[] body) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC);
        header.putInt(deviceId);
        header.putInt(packetId);
        header.putInt(body.length);
        out.write(header.array());
        if (body.length > 0) out.write(body);
        out.flush();
    }

    /**
     * Reads packets until one with the expected id arrives.
     *
     * <p>The skip loop is not defensive padding: OpenRGB pushes an unsolicited
     * {@code DEVICE_LIST_UPDATED} whenever hardware appears or a client
     * connects, and it can land in the middle of our startup conversation. A
     * reader that assumed the next packet was its reply would parse that as
     * controller data and produce confident nonsense.
     */
    private byte[] readPacket(int expectedId) throws IOException {
        for (int guard = 0; guard < 16; guard++) {
            byte[] header = in.readNBytes(16);
            if (header.length < 16) throw new IOException("short header from OpenRGB");
            if (header[0] != 'O' || header[1] != 'R' || header[2] != 'G' || header[3] != 'B') {
                throw new ProtocolMismatch("bad magic from OpenRGB");
            }
            ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            hb.position(4);
            hb.getInt();                  // device id
            int packetId = hb.getInt();
            int size = hb.getInt();
            if (size < 0 || size > 8 * 1024 * 1024) throw new IOException("absurd packet size " + size);
            byte[] body = in.readNBytes(size);
            if (body.length < size) throw new IOException("short body from OpenRGB");
            if (packetId == expectedId) return body;
        }
        throw new IOException("no reply with id " + expectedId + " after 16 packets");
    }

    private static ByteBuffer buf(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] le32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    /** OpenRGB strings: u16 length INCLUDING the null terminator, then the bytes. */
    private static String readString(ByteBuffer b) {
        int len = b.getShort() & 0xFFFF;
        if (len == 0) return "";
        byte[] raw = new byte[len];
        b.get(raw);
        int end = len;
        while (end > 0 && raw[end - 1] == 0) end--;
        return new String(raw, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * Debug flag, read defensively.
     *
     * <p>Wrapped because this class is deliberately runnable outside a loaded
     * game — {@code tuning/OpenRgbProbe} drives it against a live server to
     * check the parser — and reaching a Forge config from there throws an Error
     * rather than an Exception. A logging preference is not worth being able to
     * break a connection over.
     */
    private static boolean debugEnabled() {
        try {
            return RGBProfileConfig.DEBUG_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** OpenRGB device type -> our class. Values from the SDK's device_type enum. */
    private static KeyGrid.DeviceClass deviceClassOf(int type) {
        return switch (type) {
            case 0 -> KeyGrid.DeviceClass.MOTHERBOARD;
            case 1 -> KeyGrid.DeviceClass.MEMORY;
            case 3 -> KeyGrid.DeviceClass.COOLING;
            case 5, 18 -> KeyGrid.DeviceClass.KEYBOARD;
            case 6 -> KeyGrid.DeviceClass.MOUSE;
            case 7 -> KeyGrid.DeviceClass.MOUSEMAT;
            case 8, 9 -> KeyGrid.DeviceClass.HEADSET;
            default -> KeyGrid.DeviceClass.OTHER;
        };
    }
}
