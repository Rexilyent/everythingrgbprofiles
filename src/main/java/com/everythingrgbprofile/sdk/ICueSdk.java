package com.everythingrgbprofile.sdk;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;

import java.util.List;

/**
 * JNA bindings for the Corsair iCUE SDK v4 (iCUESDK.x64_2019.dll, SDK 4.0.84).
 * The ABI contract. Get a detail wrong in here and you don't get a compile
 * error, you get memory corruption at a random point in the future.
 *
 * <p>Transcribed directly from {@code iCUESDK.h} and
 * {@code iCUESDKLedIdEnum.h}, then cross-checked against the DLL's actual
 * 21-entry export table. Every function below is a real, undecorated export —
 * verified, not assumed.
 *
 * <p>Replaces the previous CueSdkNative, which mapped the <b>removed</b> CUE
 * SDK v2 surface ({@code CorsairPerformProtocolHandshake},
 * {@code CorsairGetDeviceCount},
 * {@code CorsairSetLedsColorsBufferByDeviceIndex}). Those functions do not
 * exist in any shipping DLL. It could never have linked, on any machine, and
 * nothing about reading it suggested that.
 *
 * <h2>Five ways to quietly destroy everything</h2>
 * None of these produce a compile error. Several produce no error at all until
 * much later, somewhere else entirely.
 *
 * <ul>
 *   <li><b>Calling convention.</b> Plain {@link Library}, NOT StdCallLibrary.
 *       The DLL is PE32+ x86-64, Win64 has exactly one calling convention, and
 *       the exports are undecorated. StdCall here would corrupt the stack on
 *       every single call.</li>
 *   <li><b>Struct arrays must be contiguous.</b> Always
 *       {@code (T[]) new T().toArray(n)}, always pass element 0. A Java array
 *       of separately-constructed Structures puts them anywhere in the heap;
 *       native code walks the array by stride and reads whatever happens to be
 *       adjacent. Same code path, different garbage each run.</li>
 *   <li><b>Callbacks must be strongly referenced</b> for as long as the SDK
 *       might invoke them. Otherwise the JVM collects the object while the
 *       native side still holds its function pointer, and the next callback
 *       lands on freed memory. {@code CueSdkBridge} holds them in fields, with
 *       a comment begging you not to tidy it up.</li>
 *   <li><b>CorsairLedPosition has interior padding.</b> An
 *       {@code unsigned int} followed by two {@code double}s means 4 bytes of
 *       padding after the id — 24 bytes total, not 20. JNA's default native
 *       alignment gets this right on its own. Do not "helpfully" override it
 *       to packed; every field after the id would shift by four bytes.</li>
 *   <li><b>CorsairDeviceId is {@code char[128]} inline in structs</b> but
 *       decays to {@code const char*} as a parameter. Hence a
 *       {@code byte[128]} field in one place and a {@code String} argument in
 *       another, for what looks like the same type. It is not the same type.</li>
 * </ul>
 */
public interface ICueSdk extends Library {

    int CORSAIR_STRING_SIZE_M = 128;
    int CORSAIR_DEVICE_COUNT_MAX = 64;
    int CORSAIR_DEVICE_LEDCOUNT_MAX = 512;

    // --- CorsairError -----------------------------------------------------
    int CE_Success = 0;
    int CE_NotConnected = 1;
    int CE_NoControl = 2;
    int CE_IncompatibleProtocol = 3;
    int CE_InvalidArguments = 4;
    int CE_InvalidOperation = 5;
    int CE_DeviceNotFound = 6;
    int CE_NotAllowed = 7;

    // --- CorsairSessionState ---------------------------------------------
    int CSS_Invalid = 0;
    int CSS_Closed = 1;
    int CSS_Connecting = 2;
    int CSS_Timeout = 3;
    int CSS_ConnectionRefused = 4;
    int CSS_ConnectionLost = 5;
    int CSS_Connected = 6;

    // --- CorsairDeviceType (bitmask) -------------------------------------
    int CDT_Unknown = 0x0000;
    int CDT_Keyboard = 0x0001;
    int CDT_Mouse = 0x0002;
    int CDT_Mousemat = 0x0004;
    int CDT_Headset = 0x0008;
    int CDT_HeadsetStand = 0x0010;
    int CDT_FanLedController = 0x0020;
    int CDT_LedController = 0x0040;
    int CDT_MemoryModule = 0x0080;
    int CDT_Cooler = 0x0100;
    int CDT_Motherboard = 0x0200;
    int CDT_GraphicsCard = 0x0400;
    int CDT_Touchbar = 0x0800;
    int CDT_GameController = 0x1000;
    int CDT_All = 0xFFFFFFFF;

    // --- CorsairAccessLevel ----------------------------------------------
    int CAL_Shared = 0;
    int CAL_ExclusiveLightingControl = 1;
    int CAL_ExclusiveKeyEventsListening = 2;
    int CAL_ExclusiveLightingControlAndKeyEventsListening = 3;

    /**
     * 130. Not arbitrary: iCUE's own lighting draws at 127 and SDK clients
     * default to 128, so 130 puts us on top of both without seizing anything.
     *
     * <p>Sharing the stack rather than taking it is the whole strategy — see
     * {@code CueSdkBridge}'s class doc for why exclusive control is a trap
     * (short version: an unclean exit leaves the user's keyboard hostage).
     */
    int DEFAULT_LAYER_PRIORITY = 130;

    static ICueSdk load(String absoluteDllPath) {
        return Native.load(absoluteDllPath, ICueSdk.class);
    }

    // ---------------------------------------------------------------------
    // Structures
    // ---------------------------------------------------------------------

    /** {@code struct CorsairVersion { int major, minor, patch; }} */
    @Structure.FieldOrder({"major", "minor", "patch"})
    class CorsairVersion extends Structure {
        public int major;
        public int minor;
        public int patch;

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /** {@code struct CorsairSessionDetails { CorsairVersion client, server, serverHost; }} */
    @Structure.FieldOrder({"clientVersion", "serverVersion", "serverHostVersion"})
    class CorsairSessionDetails extends Structure {
        public CorsairVersion clientVersion;
        public CorsairVersion serverVersion;
        public CorsairVersion serverHostVersion;
    }

    /** {@code struct CorsairSessionStateChanged { CorsairSessionState state; CorsairSessionDetails details; }} */
    @Structure.FieldOrder({"state", "details"})
    class CorsairSessionStateChanged extends Structure {
        public int state;
        public CorsairSessionDetails details;

        public static class ByReference extends CorsairSessionStateChanged implements Structure.ByReference {
        }
    }

    /**
     * {@code struct CorsairDeviceInfo { CorsairDeviceType type; CorsairDeviceId id;
     * char serial[128]; char model[128]; int ledCount; int channelCount; }}
     * <p>396 bytes, no interior padding (everything is 4-byte aligned).
     */
    @Structure.FieldOrder({"type", "id", "serial", "model", "ledCount", "channelCount"})
    class CorsairDeviceInfo extends Structure {
        public int type;
        public byte[] id = new byte[CORSAIR_STRING_SIZE_M];
        public byte[] serial = new byte[CORSAIR_STRING_SIZE_M];
        public byte[] model = new byte[CORSAIR_STRING_SIZE_M];
        public int ledCount;
        public int channelCount;

        public String deviceId() {
            return cstring(id);
        }

        public String model() {
            return cstring(model);
        }

        /**
         * Reads a C string out of a fixed 128-byte buffer.
         *
         * <p>The scan for the null terminator is the whole point: {@code new
         * String(raw, UTF_8)} on the raw array would happily give you the
         * model name followed by up to 127 NUL characters, which then
         * propagate into map keys and log lines and look absolutely fine right
         * up until two "identical" device ids don't match.
         */
        private static String cstring(byte[] raw) {
            int end = 0;
            while (end < raw.length && raw[end] != 0) end++;
            return new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code struct CorsairDeviceFilter { int deviceTypeMask; }}
     *
     * <p>Note both constructors call {@code write()} immediately. The struct
     * is passed straight to native code after construction, and without that
     * push the native side reads a zeroed mask — i.e. finds no devices, with
     * no error to explain why.
     */
    @Structure.FieldOrder({"deviceTypeMask"})
    class CorsairDeviceFilter extends Structure {
        public int deviceTypeMask;

        public CorsairDeviceFilter() {
        }

        public CorsairDeviceFilter(int mask) {
            this.deviceTypeMask = mask;
            write();
        }

        public static class ByReference extends CorsairDeviceFilter implements Structure.ByReference {
            public ByReference(int mask) {
                this.deviceTypeMask = mask;
                write();
            }
        }
    }

    /**
     * {@code struct CorsairLedPosition { CorsairLedLuid id; double cx; double cy; }}
     *
     * <p><b>24 bytes, not 20.</b> 4-byte id, then 4 bytes of padding the
     * compiler inserts so the doubles land on an 8-byte boundary, then two
     * 8-byte doubles. Getting this wrong shifts every coordinate you read.
     *
     * <p>cx/cy are in MILLIMETRES for keyboards — real physical positions on
     * a real physical board, which is why {@code KeyGrid} has to normalise
     * them and why the aspect-ratio correction in {@code CoreEmitterPattern}
     * exists at all.
     */
    @Structure.FieldOrder({"id", "cx", "cy"})
    class CorsairLedPosition extends Structure {
        public int id;
        public double cx;
        public double cy;
    }

    /**
     * {@code struct CorsairLedColor { CorsairLedLuid id; unsigned char r, g, b, a; }}
     * <p>8 bytes. Note the <b>alpha channel</b>: 0 is fully translucent, 255
     * fully opaque. iCUE composites this against whatever is beneath in its
     * own layer stack — which is exactly the Tier 1 / Tier 2 transparency
     * model this mod wants, handed to us in hardware rather than having to be
     * faked.
     */
    @Structure.FieldOrder({"id", "r", "g", "b", "a"})
    class CorsairLedColor extends Structure {
        public int id;
        public byte r;
        public byte g;
        public byte b;
        public byte a;
    }

    // ---------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------

    /** {@code typedef void(*CorsairSessionStateChangedHandler)(void*, const CorsairSessionStateChanged*)} */
    interface SessionStateChangedHandler extends Callback {
        void invoke(Pointer context, CorsairSessionStateChanged.ByReference eventData);
    }

    /** {@code typedef void(*CorsairAsyncCallback)(void*, CorsairError)} */
    interface AsyncCallback extends Callback {
        void invoke(Pointer context, int error);
    }

    // ---------------------------------------------------------------------
    // Functions (all 21 exports the DLL actually provides; the ones this mod
    // doesn't use are mapped anyway so the interface stays a faithful record
    // of the ABI)
    // ---------------------------------------------------------------------

    int CorsairConnect(SessionStateChangedHandler onStateChanged, Pointer context);

    int CorsairGetSessionDetails(CorsairSessionDetails details);

    int CorsairDisconnect();

    int CorsairGetDevices(CorsairDeviceFilter.ByReference filter, int sizeMax,
                          CorsairDeviceInfo devices, IntByReference size);

    int CorsairGetDeviceInfo(String deviceId, CorsairDeviceInfo deviceInfo);

    int CorsairGetLedPositions(String deviceId, int sizeMax,
                               CorsairLedPosition ledPositions, IntByReference size);

    int CorsairSetLedColors(String deviceId, int size, CorsairLedColor ledColors);

    int CorsairSetLedColorsBuffer(String deviceId, int size, CorsairLedColor ledColors);

    int CorsairSetLedColorsFlushBufferAsync(AsyncCallback callback, Pointer context);

    int CorsairGetLedColors(String deviceId, int size, CorsairLedColor ledColors);

    int CorsairSetLayerPriority(int priority);

    /**
     * Resolves a printable key character to its LED luid <b>taking the user's
     * logical keyboard layout into account</b> — on an AZERTY board, asking
     * for 'A' correctly returns the luid of the physically-Q key.
     *
     * <p>This single function makes the hand-maintained CorsairLedIdTable
     * unnecessary. Config values like {@code flashKey = "H"} resolve through
     * here instead of a guessed enum transcription.
     */
    int CorsairGetLedLuidForKeyName(String deviceId, byte keyName, IntByReference ledId);

    int CorsairRequestControl(String deviceId, int accessLevel);

    int CorsairReleaseControl(String deviceId);

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    static String errorName(int code) {
        return switch (code) {
            case CE_Success -> "Success";
            case CE_NotConnected -> "NotConnected (iCUE not running, or third-party control disabled in iCUE settings)";
            case CE_NoControl -> "NoControl (another client holds exclusive control)";
            case CE_IncompatibleProtocol -> "IncompatibleProtocol (iCUE too old for this SDK call)";
            case CE_InvalidArguments -> "InvalidArguments";
            case CE_InvalidOperation -> "InvalidOperation";
            case CE_DeviceNotFound -> "DeviceNotFound";
            case CE_NotAllowed -> "NotAllowed (feature disabled in iCUE settings)";
            default -> "Unknown(" + code + ")";
        };
    }

    static String sessionStateName(int state) {
        return switch (state) {
            case CSS_Closed -> "Closed";
            case CSS_Connecting -> "Connecting";
            case CSS_Timeout -> "Timeout";
            case CSS_ConnectionRefused -> "ConnectionRefused";
            case CSS_ConnectionLost -> "ConnectionLost";
            case CSS_Connected -> "Connected";
            default -> "Invalid(" + state + ")";
        };
    }

    /** Allocates a contiguous native struct array — the only correct way to pass one to the SDK. */
    @SuppressWarnings("unchecked")
    static <T extends Structure> T[] array(T prototype, int n) {
        return (T[]) prototype.toArray(n);
    }

    /**
     * Documentation disguised as a method. Returns an empty list and is never
     * called; it exists so the exports this mod deliberately does NOT map are
     * written down somewhere, rather than leaving a future reader to wonder
     * whether they were missed or skipped.
     */
    static List<String> unused() {
        // CorsairSubscribeForEvents / CorsairUnsubscribeFromEvents /
        // CorsairConfigureKeyEvent / CorsairGetDevicePropertyInfo /
        // CorsairReadDeviceProperty / CorsairWriteDeviceProperty /
        // CorsairFreeProperty are the remaining exports. Not mapped: this mod
        // only writes lighting, it never intercepts keys or reads properties.
        return List.of();
    }
}
