package com.everythingrgbprofile.sdk;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.OaIdl.SAFEARRAY;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.Variant;
import com.sun.jna.platform.win32.WTypes.BSTR;
import com.sun.jna.platform.win32.WTypes.VARTYPEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * MSI Mystic Light, through MSI's Mystic Light SDK.
 *
 * <h2>Where the DLL comes from</h2>
 * Not from this mod. MSI hands the SDK out as a download, every page of its
 * documentation says "Confidential" across the top, and nobody has asked MSI
 * whether bundling it is fine. So it is not bundled.
 *
 * <p>MSI Center does not install it either. The DLL is a client that talks to
 * MSI Center's Mystic Light service, and the service is all MSI Center puts on
 * disk. Checked with the Mystic Light module actually installed, too: no copy
 * of the SDK anywhere. So a player who wants this downloads MSI's SDK and drops
 * {@code MysticLight_SDK_x64.dll} into {@code config/everythingrgbprofiles/native},
 * or points {@code mysticLightSdkPath} in [hardware] at it.
 *
 * <p>They'd never guess that on their own, so when the DLL is missing and
 * {@link MsiDetection} finds MSI hardware (or msi was picked by name in the
 * config), {@link #sdkWanted()} says so and the chat offers MSI's download
 * link. Once, and it can be told to go away.
 *
 * <h2>And only MSI's DLL, thanks</h2>
 * That folder is under the game directory, which any program on the machine
 * can write to without asking, and modpacks ship whole config folders. A
 * backend that loads whatever DLL has the right name is a backend that runs
 * whatever native code a pack, or anything else, felt like leaving there.
 * Corsair gets around this by carrying its own copy in the jar and comparing
 * byte for byte (see {@code CueSdkBridge#extractBundledDll}). This one can't,
 * since the whole point above is that it's not ours to carry. So it carries the
 * SHA-256 of MSI's file instead, and anything that doesn't match is refused
 * before it gets anywhere near {@code Native.load}. Same comparison, minus the
 * part where we redistribute something stamped "Confidential".
 *
 * <p>The catch: a new SDK release is refused too, until its hash is added to
 * {@link #KNOWN_DLL_SHA256}. Annoying. Much less annoying than the alternative.
 *
 * <p>Which makes that DLL being present the opt-in. No DLL, and this backend
 * reports "not installed" in no time at all and never loads anything.
 *
 * <h2>Initialize takes its sweet time failing</h2>
 * With the DLL present and Mystic Light absent, {@code MLAPI_Initialize}
 * blocks for about ten seconds before returning -3, "MSI application not
 * found". Measured twice: once with MSI Center but no Mystic Light module,
 * once with the module installed but LEDKeeper2.exe not running. Start
 * LEDKeeper2 and the same call returns 0 in about 5 ms.
 *
 * <p>LEDKeeper2 is started by a scheduled task, not a service, so "installed"
 * and "running" are two different things here. Cool. Love that.
 *
 * <p>So before touching the SDK this asks {@link MsiDetection#ledKeeperRunning}
 * first, and a sleeping Mystic Light costs milliseconds instead of holding up
 * every backend after it for ten seconds. If Windows won't say what's running,
 * the SDK gets asked anyway and the ten seconds come back. Fair trade.
 *
 * <h2>Zones, not keys</h2>
 * The SDK has no idea where anything is. It reports device TYPES, a count of
 * LED areas per type, and a display name and list of styles per area. No
 * positions. Not even for keyboards.
 *
 * <p>So each area becomes one LED, and each device a single row of them, in
 * the order MSI lists them. Fine for a motherboard's handful of headers or a
 * GPU strip, and exactly as wrong as it sounds for a keyboard. A
 * {@link KeyLayout} matched against the area names can place them properly,
 * and the names are in the report for whoever writes one.
 *
 * <p>The SDK does have a per-LED path ({@code MLAPI_SetLedColors} under the
 * "Direct Lighting Control" style), but its reference is vague about how LED
 * names map to areas, and guessing at that blind is how you get a backend that
 * paints the wrong LEDs with total confidence. It stays unused until somebody
 * with the hardware can show what it actually reports.
 *
 * <h2>What has been checked</h2>
 * The only keyboard this project has been tested on is a Corsair K70 RGB
 * RAPIDFIRE, and no MSI RGB hardware has been anywhere near this code. The
 * checked part is: the DLL loads through JNA, and
 * {@code MLAPI_Initialize} without Mystic Light returns {@code -3} after the
 * stall described above, which lands in the "not running" diagnosis below.
 *
 * <h2>The time we built a fake MSI PC to find any of this out</h2>
 * No MSI hardware, and MSI Center won't hand out its Mystic Light module
 * without it. So, naturally, an entire Windows 11 virtual machine got
 * convinced it was an MSI PC. Normal Tuesday behavior.
 *
 * <ol>
 *   <li><b>The VM.</b> VirtualBox, Windows 11, Guest Additions for a shared
 *       folder to get results back out. Nothing else installed.</li>
 *   <li><b>The disguise.</b> VirtualBox lets you set the SMBIOS strings
 *       Windows reads its motherboard info from, via {@code VBoxManage
 *       setextradata} on {@code VBoxInternal/Devices/efi/0/Config/Dmi*}.
 *       Board and system vendor became "Micro-Star International Co., Ltd.",
 *       the board became "MAG B650 TOMAHAWK MAX WIFI II (MS-7E62)", and the
 *       BIOS version became 5.A32. The MS-7E62 and 5.A32 come straight off
 *       MSI's BIOS download for that board, which is named
 *       {@code 7E62v5A32}. An AMD board, because the host is AMD and an AMD
 *       CPU on an Intel board is the kind of detail that gives the game
 *       away.</li>
 *   <li><b>MSI Center bought it.</b> It installed, and offered Mystic Light
 *       along with every other module. So MSI Center decides what to offer
 *       from the motherboard model alone, and never checks whether any
 *       lighting is actually plugged in.</li>
 *   <li><b>What the module installed.</b> Everything lands in
 *       {@code Program Files (x86)\MSI\MSI Center\Mystic Light}, including
 *       {@code LEDKeeper2.exe}, plus two services (LightKeeperService and
 *       Mystic_Light_Service) and a scheduled task, "MSI Task Host -
 *       LEDKeeper2_Host", which is the thing that actually starts LEDKeeper2.
 *       Every exe, dll and sys under Program Files, ProgramData, AppData and
 *       the drivers folder was hashed afterwards. No copy of the SDK DLL
 *       anywhere.</li>
 *   <li><b>What the SDK looks for.</b> The text inside MSI's own DLL
 *       names {@code LEDKeeper.exe}, {@code LEDKeeper2.exe}, and the registry
 *       keys {@code SOFTWARE\MSI\MysticLight2\SDK} and
 *       {@code SOFTWARE\MSI\Mystic_Light_SDK} (both also under
 *       WOW6432Node). Once LEDKeeper2 had started,
 *       {@code HKLM\SOFTWARE\WOW6432Node\MSI\Mystic_Light_SDK} was there, with
 *       no values in it. Nobody checked whether it was there before, so
 *       don't read too much into that.</li>
 *   <li><b>Poking the SDK.</b> Not with this class: the VM had no Java, so a
 *       small PowerShell script loaded MSI's DLL directly and called the same
 *       functions this class does. With LEDKeeper2 not running,
 *       {@code MLAPI_Initialize} returned -3 after 10,199 ms. After
 *       starting the scheduled task, it returned 0 in 5 ms (19 ms on a second
 *       run). The SDK from 2022 talks to the current MSI Center just fine.</li>
 *   <li><b>And then -2.</b> {@code MLAPI_GetDeviceInfo} returned -2, a
 *       timeout, after 61 to 62 seconds. Three tries in a row, 20 seconds
 *       apart, same result every time. MSI's message for -2 is "MLAPI_Initialize
 *       is timeout.", even when Initialize is the call that worked. Thanks, MSI.
 *       Best guess is LEDKeeper2 waiting on hardware that a VM simply doesn't
 *       have. Either way it's why {@link #openWithDeadline} exists.</li>
 * </ol>
 *
 * <p>Two honest caveats. The VM ran in VirtualBox's slow fallback mode (the
 * host has Hyper-V running underneath), so every timing above is
 * approximate. The 10 and 62 second figures were the same on every run, which
 * smells more like fixed timeouts than a slow VM, but that's an inference,
 * not a measurement. And with no lighting to find, none of this says anything
 * about what a real MSI device list looks like.
 *
 * <p>Everything past a successful initialise (enumeration, style switching,
 * writing colours, restoring them) follows MSI's reference and has never run
 * against real hardware. Experimental, in the most literal sense.
 */
public final class MysticLightBackend implements LightingBackend {

    public static final String DLL_NAME = "MysticLight_SDK_x64.dll";

    /**
     * SHA-256 of every {@link #DLL_NAME} this backend agrees to load, lowercase
     * hex. Before adding one, check the file is Authenticode-signed by
     * Micro-Star International, or this list is just decoration.
     */
    private static final Set<String> KNOWN_DLL_SHA256 = Set.of(
            // SDK 1.0.0.08, file version 1.0.0.8.
            "ad1bd5a464c120f086839631cf9cb7a56e8e7373209158e66c628a89be06d166");

    /**
     * Frame-rate cap. Every changed area costs one SDK call into MSI's
     * service, and nobody here has ever timed one, so this is a cautious
     * guess rather than a measurement. Raise it once someone has.
     */
    private static final long MIN_PUSH_NANOS = 1_000_000_000L / 30;

    /** Consecutive pushes refused before the session counts as gone. About two seconds at the cap. */
    private static final int LOST_AFTER_FAILED_PUSHES = 60;

    /**
     * Styles under which an area should simply show the colour it is given,
     * in preference order, matched without case.
     *
     * <p>MSI's reference never lists style names. "No animation" is the one
     * its own sample switches to when it puts an LED back to plain white, so
     * it goes first. The other two are common names for the same idea and are
     * a guess. An area offering none of them is left on its current style,
     * which may well animate over whatever this mod sends.
     */
    private static final List<String> STATIC_STYLES = List.of("No animation", "Steady", "Static");

    /** Where MSI hands the SDK out. Same zip since August 2022, DLL inside matches {@link #KNOWN_DLL_SHA256}. */
    public static final String SDK_DOWNLOAD_URL = "https://download.msi.com/uti_exe/Mystic_light_SDK.zip";

    /**
     * Why this player should be offered the SDK download, or null if they
     * shouldn't. Written on the SDK worker thread, read by the chat on the
     * client thread, hence volatile.
     */
    private static volatile String sdkWantedBecause;

    private static final String NOT_RUNNING_SUMMARY =
            "The Mystic Light SDK did not answer, so MSI Center's Mystic Light is probably not running.";
    private static final String NOT_RUNNING_FIX = "Start MSI Center, then restart Minecraft.";

    /**
     * How long connecting gets before it's abandoned. Initialize answers in
     * milliseconds, and with nothing to find the device list takes 62
     * seconds, so anything in between separates the two. Nobody has timed a
     * device list on real MSI hardware, so this is generous on purpose.
     */
    private static final long OPEN_DEADLINE_MILLIS = 10_000;

    private MysticLightSdk sdk;
    /** Set when connect gave up waiting on the opener thread. Guarded by {@link #abandonLock}. */
    private boolean abandoned;
    private final Object abandonLock = new Object();
    /** Which native call the opener is in, for the "gave up" diagnosis. */
    private volatile String openingStep = "starting";
    private boolean initialised;
    private boolean connected;
    private KeyGrid grid = KeyGrid.empty();

    /** One device type MSI reported, and the areas we drive on it. */
    private static final class Device {
        String type;
        /** {@link #type} as a BSTR, allocated once and passed to every call. Freed in shutdown. */
        BSTR bstr;
        String id;
        String name;
        KeyGrid.DeviceClass deviceClass;
        Area[] areas;
    }

    private static final class Area {
        int index;
        String name;
        /** Packed 0xRRGGBB of what we last asked for, -1 before the first frame. */
        int color = -1;
        boolean dirty;
        /** Refused with a "this LED cannot do that" code. Not asked again this session. */
        boolean dead;
        String originalStyle;
        /** Packed 0xRRGGBB from before we touched it, or -1 if it could not be read. */
        int originalColor = -1;
        boolean styleChanged;
    }

    private final List<Device> devices = new ArrayList<>();
    private final Map<String, Device> devicesById = new HashMap<>();
    /** Devices left dark because their type is turned off in [devices], for the diagnosis. */
    private final List<String> skippedByClass = new ArrayList<>();
    /** Everything MSI told us, one line per device or area, for the report. */
    private final StringBuilder inventory = new StringBuilder();

    private boolean pending;
    private long lastPushNanos;
    private int failedPushesInARow;
    private String lastFailure;

    /** Set by the test seam to bypass config, null in normal use. */
    private final Path dllOverride;

    public MysticLightBackend() {
        this(null);
    }

    private MysticLightBackend(Path dll) {
        this.dllOverride = dll;
    }

    /**
     * Builds a backend that loads this exact DLL instead of looking for one.
     *
     * <p>A test seam, same reasoning as {@code OpenRgbBackend.pointedAt}:
     * {@code tuning/MysticLightProbe} uses it to run the real connection code
     * outside a loaded game, where reading the Forge config would throw.
     */
    public static MysticLightBackend loading(Path dll) {
        return new MysticLightBackend(dll);
    }

    /**
     * Non-null when the SDK DLL is missing and this player looks like they'd
     * want it: MSI hardware found, or msi picked by name in the config. The
     * string says which, for the chat message. Null otherwise, including when
     * this backend never ran because some other backend was picked by name.
     */
    public static String sdkWanted() {
        return sdkWantedBecause;
    }

    @Override
    public String id() {
        return "msi";
    }

    @Override
    public String displayName() {
        return "MSI Mystic Light";
    }

    @Override
    public boolean connect(Path workDir, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        try {
            Path dll = findDll(workDir);
            verifyDll(dll);
            if (Boolean.FALSE.equals(MsiDetection.ledKeeperRunning())) {
                throw new BackendHealth.Unavailable(BackendHealth.State.NOT_RUNNING,
                        "MSI Center's Mystic Light is not running (LEDKeeper2.exe isn't), so the SDK was not asked.",
                        "Open MSI Center and click into Mystic Light so it starts, then restart Minecraft.",
                        "SDK: " + dll + "\nNo LEDKeeper2.exe or LEDKeeper.exe in the process list.");
            }
            List<Path> libraries = List.of(dll);
            NativeCrashGuard.checkBefore(workDir, id(), displayName(), libraries);
            openWithDeadline(workDir, dll, libraries, deviceEnabled);
        } catch (Throwable t) {
            // Throwable, because what a native library throws when it fails is
            // usually an Error rather than an Exception.
            BackendHealth.recordFailure(id(), displayName(), "while connecting", t,
                    NOT_RUNNING_SUMMARY, NOT_RUNNING_FIX);
            shutdown();
            return false;
        }

        connected = true;
        int leds = grid.allKeys().size();
        StringBuilder summary = new StringBuilder()
                .append(devices.size()).append(" device(s), ").append(leds).append(" LED area(s)");
        if (!skippedByClass.isEmpty()) summary.append("; skipped as turned off in [devices]: ").append(skippedByClass);
        summary.append(". Each area is one LED to this mod, laid out in a row, because the SDK reports no positions.");
        BackendHealth.record(id(), displayName(), BackendHealth.State.CONNECTED, summary.toString(),
                null, inventory.toString());
        return true;
    }

    /**
     * {@link #openNative}, on its own thread, with a deadline.
     *
     * <h2>Why a whole extra thread</h2>
     * With Mystic Light running and nothing for it to find,
     * {@code MLAPI_GetDeviceInfo} blocks for about 62 seconds and then returns
     * -2. Every time. Three tries in a row, all over 61 seconds. In auto mode
     * that's a full minute where no backend lights anything, because they
     * connect one after another and this one's last. Somebody with an MSI
     * monitor, MSI Center, and the SDK would just get a dark keyboard and a
     * very long think about their choices.
     *
     * <p>A native call can't be cancelled. So it runs over there, this thread
     * waits {@link #OPEN_DEADLINE_MILLIS}, and past that the call is abandoned:
     * left to finish whenever MSI feels like it, after which its own thread
     * releases the SDK. Nothing else touches the SDK in the meantime, because
     * {@link #shutdown} does nothing while {@link #abandoned} is set. Two
     * threads in MSI's DLL at once is a crash nobody needs to find out about.
     *
     * <p>The crash guard marker stays put until the abandoned call returns. If
     * the game gets killed from outside during that minute, next launch will
     * think MSI crashed it and skip MSI once. Same false positive the guard
     * already owns up to, just a slightly bigger window for it.
     */
    private void openWithDeadline(Path workDir, Path dll, List<Path> libraries,
                                  Predicate<KeyGrid.DeviceClass> deviceEnabled) throws Throwable {
        FutureTask<Void> opening = new FutureTask<>(() -> {
            // Loading the DLL and everything up to the end of enumeration is
            // native code that can take the game down with it, so it all runs
            // inside the crash guard.
            NativeCrashGuard.enter(workDir, id(), libraries);
            try {
                openNative(dll, deviceEnabled);
            } finally {
                NativeCrashGuard.exit(workDir, id());
            }
            return null;
        });
        Thread opener = new Thread(() -> {
            opening.run();
            synchronized (abandonLock) {
                if (abandoned) {
                    // Nobody's waiting for this anymore. Clean up and leave.
                    abandoned = false;
                    shutdown();
                }
            }
        }, "RGB Profile MSI connect");
        opener.setDaemon(true);
        opener.start();

        try {
            opening.get(OPEN_DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        } catch (TimeoutException | InterruptedException e) {
            boolean gaveUp;
            synchronized (abandonLock) {
                // It might have finished in the gap between the timeout and
                // this lock. Then it's just done, no abandoning required.
                gaveUp = !opening.isDone();
                if (gaveUp) abandoned = true;
            }
            // The local, not the field: once the opener gets out it resets the
            // field, and reading that here would turn "gave up" into "worked".
            if (!gaveUp) {
                try {
                    opening.get();
                    return;
                } catch (ExecutionException late) {
                    throw late.getCause();
                }
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                    "MSI Center's Mystic Light is running, but did not list any devices within "
                            + OPEN_DEADLINE_MILLIS / 1000 + " seconds, so MSI lighting was skipped.",
                    "If you have MSI RGB hardware, open Mystic Light in MSI Center and check it shows up there, "
                            + "then restart Minecraft. If your only MSI hardware has no lighting, ignore this.",
                    "SDK: " + dll + "\nStill inside " + openingStep + " when the deadline passed.");
        }
    }

    private void openNative(Path dll, Predicate<KeyGrid.DeviceClass> deviceEnabled)
            throws BackendHealth.Unavailable {
        openingStep = "Native.load";
        // Same as the other native backends: use the jnidispatch inside JNA's
        // own jar rather than any system-wide copy.
        System.setProperty("jna.nosys", "true");
        try {
            sdk = Native.load(dll.toString(), MysticLightSdk.class);
        } catch (UnsatisfiedLinkError e) {
            // 32-bit Java is BackendHealth's to explain, and it does that better.
            if ("32".equals(System.getProperty("sun.arch.data.model"))) throw e;
            throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                    "The Mystic Light SDK was found but could not be loaded.",
                    "Make sure the file is MSI's 64-bit " + DLL_NAME + ", not the 32-bit MysticLight_SDK.dll. "
                            + "If it is, " + BackendHealth.SEND_REPORT,
                    dll + "\n" + BackendHealth.describe(e));
        }

        long started = System.nanoTime();
        openingStep = "MLAPI_Initialize";
        int rc = sdk.MLAPI_Initialize();
        long millis = (System.nanoTime() - started) / 1_000_000;
        String initDetail = "SDK: " + dll + "\nMLAPI_Initialize -> " + status(rc) + " after " + millis + " ms";
        if (rc == MysticLightSdk.MLAPI_NO_IMPLEMENTED) {
            // Normally caught by the LEDKeeper2 check before it gets here. Landing
            // here means Windows wouldn't list processes, or LEDKeeper2 was up
            // and the SDK still said no, which nobody has seen happen yet.
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_RUNNING,
                    "The Mystic Light SDK is here, but MSI Center's Mystic Light is not running, not installed, "
                            + "or a version the SDK does not support.",
                    "Install MSI Center, add Mystic Light from its Features tab, open Mystic Light once, "
                            + "then restart Minecraft.",
                    initDetail);
        }
        if (rc == MysticLightSdk.MLAPI_TIMEOUT) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_RUNNING,
                    "MSI Center's Mystic Light did not answer in time.",
                    "Make sure MSI Center is running and has finished starting, then restart Minecraft.",
                    initDetail);
        }
        if (rc != MysticLightSdk.MLAPI_OK) {
            throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                    "MSI Center's Mystic Light refused to start the SDK.",
                    "Try restarting MSI Center. If that does not help, " + BackendHealth.SEND_REPORT,
                    initDetail);
        }
        initialised = true;
        inventory.append(initDetail).append('\n');

        enumerate(deviceEnabled);
    }

    // ---------------------------------------------------------------
    // Enumeration
    // ---------------------------------------------------------------

    private void enumerate(Predicate<KeyGrid.DeviceClass> deviceEnabled) throws BackendHealth.Unavailable {
        PointerByReference typesOut = new PointerByReference();
        PointerByReference countsOut = new PointerByReference();
        openingStep = "MLAPI_GetDeviceInfo";
        int rc = sdk.MLAPI_GetDeviceInfo(typesOut, countsOut);
        openingStep = "reading MSI's device list";
        if (rc == MysticLightSdk.MLAPI_TIMEOUT) {
            // The answer MSI gives after its minute of silence when there's
            // nothing to find. Only reachable here if the deadline got raised
            // past a minute, but it should still say the right thing.
            throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                    "MSI Center's Mystic Light is running, but timed out listing its devices.",
                    "If you have MSI RGB hardware, open Mystic Light in MSI Center and check it shows up there, "
                            + "then restart Minecraft. If your only MSI hardware has no lighting, ignore this.",
                    inventory + "MLAPI_GetDeviceInfo -> " + status(rc));
        }
        if (rc != MysticLightSdk.MLAPI_OK) {
            throw new BackendHealth.Unavailable(BackendHealth.State.REFUSED,
                    "MSI Center's Mystic Light started, then would not list its devices.",
                    "Try restarting MSI Center. If that does not help, " + BackendHealth.SEND_REPORT,
                    inventory + "MLAPI_GetDeviceInfo -> " + status(rc));
        }
        // Both arrays are ours now, so both get taken (and destroyed) before
        // anything else can throw.
        List<String> types = takeStrings(typesOut.getValue());
        List<String> counts = takeStrings(countsOut.getValue());
        inventory.append(types.size()).append(" device type(s)\n");

        int unreadable = 0;
        Throwable firstUnreadable = null;
        for (int i = 0; i < types.size(); i++) {
            String count = i < counts.size() ? counts.get(i) : null;
            try {
                readDevice(types.get(i), count, deviceEnabled);
            } catch (Throwable t) {
                // One device MSI describes oddly must not cost the player the
                // rest of their hardware.
                unreadable++;
                if (firstUnreadable == null) firstUnreadable = t;
                inventory.append("  ").append(types.get(i)).append(": could not be read, ").append(t).append('\n');
                BackendHealth.problem(displayName(), "Device type \"" + types.get(i)
                        + "\" could not be read and was skipped.", t);
            }
        }

        if (devices.isEmpty()) {
            if (types.isEmpty()) {
                throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                        "MSI Center's Mystic Light is running but reports no devices.",
                        "Check your hardware shows up in MSI Center's Mystic Light page.",
                        inventory.toString());
            }
            if (unreadable == types.size()) {
                throw new BackendHealth.Unavailable(BackendHealth.State.BROKEN,
                        "Mystic Light listed " + types.size() + " device(s), but this mod could not read any of them.",
                        BackendHealth.SEND_REPORT + " Mention which version of MSI Center you have.",
                        inventory + BackendHealth.describe(firstUnreadable));
            }
            if (!skippedByClass.isEmpty()) {
                throw new BackendHealth.Unavailable(BackendHealth.State.DISABLED,
                        "Mystic Light's devices are all types turned off in the config: " + skippedByClass + ".",
                        "Turn on the matching setting in the [devices] section of "
                                + "everythingrgbprofiles-common.toml, for example motherboardEnabled.",
                        inventory.toString());
            }
            throw new BackendHealth.Unavailable(BackendHealth.State.NO_DEVICES,
                    "Mystic Light's devices report no LEDs this mod can light.",
                    "If they light up from MSI Center but not from here, " + BackendHealth.SEND_REPORT,
                    inventory.toString());
        }

        // Geometry last, once every native read is done, so a device that
        // failed halfway never leaves the builder half-open.
        KeyGrid.Builder builder = new KeyGrid.Builder();
        for (Device d : devices) {
            List<String> names = new ArrayList<>(d.areas.length);
            List<double[]> row = new ArrayList<>(d.areas.length);
            for (Area a : d.areas) {
                names.add(a.name);
                row.add(new double[]{a.index, a.index, 0});
            }
            List<double[]> laidOut = KeyLayout.positionsFor(d.name, names, row);
            builder.beginDevice(d.id, d.name, d.deviceClass);
            for (double[] p : laidOut) builder.addLed((int) p[0], p[1], p[2]);
            builder.endDevice();
        }
        grid = builder.build();
    }

    private void readDevice(String type, String countText, Predicate<KeyGrid.DeviceClass> deviceEnabled) {
        int count = parseCount(countText);
        KeyGrid.DeviceClass cls = deviceClassOf(type);
        BSTR bstr = OleAuto.INSTANCE.SysAllocString(type);
        boolean kept = false;
        try {
            String name = type;
            PointerByReference nameOut = new PointerByReference();
            if (sdk.MLAPI_GetDeviceNameEx(bstr, 0, nameOut) == MysticLightSdk.MLAPI_OK) {
                String friendly = takeString(nameOut.getValue());
                if (friendly != null && !friendly.isBlank()) name = friendly;
            }
            inventory.append("  ").append(type).append(" \"").append(name).append("\" (").append(cls)
                    .append("), ").append(countText).append(" LED area(s)\n");
            if (debugEnabled()) {
                RGBProfileMod.LOGGER.info("RGB Profile: Mystic Light {} '{}' ({}), {} areas:", type, name, cls, count);
            }

            if (!deviceEnabled.test(cls)) {
                skippedByClass.add(name + " (" + cls.configKey() + ")");
                return;
            }
            if (count <= 0) return;

            Device d = new Device();
            d.type = type;
            d.bstr = bstr;
            d.id = "msi:" + type;
            d.name = name;
            d.deviceClass = cls;
            d.areas = new Area[count];
            // Kept from here on, so shutdown owns the BSTR and can restore
            // anything readArea changes even if a later area throws.
            devices.add(d);
            devicesById.put(d.id, d);
            kept = true;
            for (int i = 0; i < count; i++) {
                try {
                    d.areas[i] = readArea(d, i);
                } catch (Throwable t) {
                    // A dead placeholder keeps the slot, so one odd area costs
                    // that area rather than the whole device.
                    Area dead = new Area();
                    dead.index = i;
                    dead.name = "Area " + i;
                    dead.dead = true;
                    d.areas[i] = dead;
                    inventory.append("    ").append(i).append(": could not be read, ").append(t).append('\n');
                    BackendHealth.problem(displayName(), name + " area " + i + " could not be read and was skipped.", t);
                }
            }
        } finally {
            if (!kept) OleAuto.INSTANCE.SysFreeString(bstr);
        }
    }

    /** Reads one area, remembers how it was, and puts it on a style that shows a plain colour. */
    private Area readArea(Device d, int index) {
        Area a = new Area();
        a.index = index;
        a.name = "Area " + index;

        List<String> styles = List.of();
        PointerByReference nameOut = new PointerByReference();
        PointerByReference stylesOut = new PointerByReference();
        int rc = sdk.MLAPI_GetLedInfo(d.bstr, index, nameOut, stylesOut);
        if (rc == MysticLightSdk.MLAPI_OK) {
            String n = takeString(nameOut.getValue());
            if (n != null && !n.isBlank()) a.name = n;
            styles = takeStrings(stylesOut.getValue());
        }

        PointerByReference styleOut = new PointerByReference();
        if (sdk.MLAPI_GetLedStyle(d.bstr, index, styleOut) == MysticLightSdk.MLAPI_OK) {
            a.originalStyle = takeString(styleOut.getValue());
        }
        IntByReference r = new IntByReference(), g = new IntByReference(), b = new IntByReference();
        if (sdk.MLAPI_GetLedColor(d.bstr, index, r, g, b) == MysticLightSdk.MLAPI_OK) {
            a.originalColor = (r.getValue() & 0xFF) << 16 | (g.getValue() & 0xFF) << 8 | (b.getValue() & 0xFF);
        }

        String target = pickStaticStyle(styles);
        String styleResult;
        if (target == null) {
            styleResult = "no static style offered, left as is";
        } else if (target.equalsIgnoreCase(a.originalStyle)) {
            styleResult = "already static";
        } else {
            BSTR t = OleAuto.INSTANCE.SysAllocString(target);
            try {
                int set = sdk.MLAPI_SetLedStyle(d.bstr, index, t);
                a.styleChanged = set == MysticLightSdk.MLAPI_OK;
                styleResult = "set \"" + target + "\" -> " + status(set);
            } finally {
                OleAuto.INSTANCE.SysFreeString(t);
            }
        }

        inventory.append("    ").append(index).append(" \"").append(a.name).append("\"")
                .append(rc == MysticLightSdk.MLAPI_OK ? "" : " (GetLedInfo -> " + status(rc) + ")")
                .append(", was ").append(a.originalStyle)
                .append(a.originalColor < 0 ? "" : String.format(" #%06X", a.originalColor))
                .append(", styles ").append(styles)
                .append(", ").append(styleResult).append('\n');
        if (debugEnabled()) {
            RGBProfileMod.LOGGER.info("    {} = \"{}\" styles {} ({})", index, a.name, styles, styleResult);
        }
        return a;
    }

    private static String pickStaticStyle(List<String> offered) {
        for (String want : STATIC_STYLES) {
            for (String s : offered) {
                if (s.trim().equalsIgnoreCase(want)) return s;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Frames
    // ---------------------------------------------------------------

    @Override
    public void applyFrame(Map<KeyGrid.LedRef, RGBColor> frame) {
        if (!connected) return;
        for (Map.Entry<KeyGrid.LedRef, RGBColor> e : frame.entrySet()) {
            Device d = devicesById.get(e.getKey().deviceId());
            if (d == null) continue;
            int i = e.getKey().luid();
            if (i < 0 || i >= d.areas.length) continue;
            Area a = d.areas[i];
            RGBColor c = e.getValue();
            int packed = (c.r() & 0xFF) << 16 | (c.g() & 0xFF) << 8 | (c.b() & 0xFF);
            if (a.color != packed) {
                a.color = packed;
                a.dirty = true;
                pending = true;
            }
        }

        long now = System.nanoTime();
        if (!pending || now - lastPushNanos < MIN_PUSH_NANOS) return;
        lastPushNanos = now;
        try {
            push();
        } catch (Throwable t) {
            connected = false;
            BackendHealth.recordFailure(id(), displayName(), "while sending a frame", t,
                    NOT_RUNNING_SUMMARY, NOT_RUNNING_FIX);
        }
    }

    /**
     * Sends every changed area, one call each.
     *
     * <p>Codes that are about one LED (bad argument, no such device, not
     * supported) retire that area for the session, with one line in the
     * report, instead of failing the same way thirty times a second forever.
     * Anything else is about the session itself, so the push stops there and
     * gets retried next frame, and enough of those in a row means MSI Center
     * has gone away.
     */
    private void push() {
        for (Device d : devices) {
            for (Area a : d.areas) {
                if (!a.dirty || a.dead) continue;
                int c = a.color;
                int rc = sdk.MLAPI_SetLedColor(d.bstr, a.index, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                if (rc == MysticLightSdk.MLAPI_OK) {
                    a.dirty = false;
                } else if (rc == MysticLightSdk.MLAPI_INVALID_ARGUMENT
                        || rc == MysticLightSdk.MLAPI_DEVICE_NOT_FOUND
                        || rc == MysticLightSdk.MLAPI_NOT_SUPPORTED) {
                    a.dead = true;
                    a.dirty = false;
                    BackendHealth.problem(displayName(), d.name + " area " + a.index + " (\"" + a.name
                            + "\") refused a colour and will be left alone: " + status(rc) + ".", null);
                } else {
                    lastFailure = d.type + " area " + a.index + ": " + status(rc);
                    if (++failedPushesInARow >= LOST_AFTER_FAILED_PUSHES) {
                        connected = false;
                        BackendHealth.record(id(), displayName(), BackendHealth.State.LOST,
                                "MSI Center stopped accepting lighting mid-game: closed, restarting or updating.",
                                "Once it is running again, restart Minecraft to reconnect.",
                                "Refused " + failedPushesInARow + " frames in a row. Last: " + lastFailure);
                    }
                    return;
                }
            }
        }
        failedPushesInARow = 0;
        pending = false;
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public KeyGrid keyGrid() {
        return grid;
    }

    /**
     * Puts every area back the way it was found, releases the SDK and frees
     * the BSTRs. Colour first, then style, which is the order MSI's own
     * sample uses when it resets an LED.
     */
    @Override
    public void shutdown() {
        connected = false;
        synchronized (abandonLock) {
            // An abandoned connect is still inside MSI's DLL. Its own thread
            // runs this again once it gets out. See openWithDeadline.
            if (abandoned) return;
        }
        for (Device d : devices) {
            if (sdk != null && initialised) {
                for (Area a : d.areas) {
                    // Null only if shutdown runs mid-enumeration, after a throw.
                    if (a == null) continue;
                    try {
                        if (a.originalColor >= 0) {
                            int c = a.originalColor;
                            sdk.MLAPI_SetLedColor(d.bstr, a.index, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                        }
                        if (a.styleChanged && a.originalStyle != null) {
                            BSTR s = OleAuto.INSTANCE.SysAllocString(a.originalStyle);
                            try {
                                sdk.MLAPI_SetLedStyle(d.bstr, a.index, s);
                            } finally {
                                OleAuto.INSTANCE.SysFreeString(s);
                            }
                        }
                    } catch (Throwable ignored) {
                        // Best effort. Shutdown runs from a finally, and one
                        // area refusing must not keep the rest from being put back.
                    }
                }
            }
            try {
                OleAuto.INSTANCE.SysFreeString(d.bstr);
            } catch (Throwable ignored) {
            }
        }
        devices.clear();
        devicesById.clear();
        if (sdk != null && initialised) {
            try {
                sdk.MLAPI_Release();
            } catch (Throwable ignored) {
            }
        }
        initialised = false;
    }

    // ---------------------------------------------------------------
    // COM strings and arrays
    // ---------------------------------------------------------------

    /** Reads a BSTR the SDK handed us and frees it. Null in, null out. */
    private static String takeString(Pointer p) {
        if (p == null) return null;
        try {
            return p.getWideString(0);
        } finally {
            OleAuto.INSTANCE.SysFreeString(new BSTR(p));
        }
    }

    /**
     * Reads a one-dimensional SAFEARRAY of BSTR the SDK handed us, then
     * destroys it, strings and all.
     *
     * <p>The element type is checked rather than assumed. Reading an array of
     * something else as string pointers would not throw, it would read memory
     * at whatever address the numbers happened to spell.
     */
    private static List<String> takeStrings(Pointer p) {
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        SAFEARRAY sa = new SAFEARRAY(p);
        try {
            VARTYPEByReference vt = new VARTYPEByReference();
            OleAuto.INSTANCE.SafeArrayGetVartype(sa, vt);
            int type = vt.getValue().intValue();
            if (type != Variant.VT_BSTR) {
                throw new IllegalStateException("Expected an array of strings from the SDK, got VARTYPE " + type);
            }
            if (sa.cDims.intValue() != 1) {
                throw new IllegalStateException("Expected a 1-D array from the SDK, got " + sa.cDims.intValue() + "-D");
            }
            int n = sa.rgsabound[0].cElements.intValue();
            Pointer data = sa.pvData.getPointer();
            for (int i = 0; i < n; i++) {
                Pointer s = data.getPointer((long) i * Native.POINTER_SIZE);
                out.add(s == null ? "" : s.getWideString(0));
            }
        } finally {
            OleAuto.INSTANCE.SafeArrayDestroy(sa);
        }
        return out;
    }

    /** A status code with MSI's own wording, for diagnoses. Never throws. */
    private String status(int rc) {
        String text = null;
        try {
            PointerByReference desc = new PointerByReference();
            sdk.MLAPI_GetErrorMessage(rc, desc);
            text = takeString(desc.getValue());
        } catch (Throwable ignored) {
        }
        return text == null || text.isBlank() ? String.valueOf(rc) : rc + " (" + text.trim() + ")";
    }

    // ---------------------------------------------------------------
    // Finding the DLL, and other small things
    // ---------------------------------------------------------------

    /**
     * The configured path if there is one, otherwise the native folder.
     *
     * <p>A configured path that does not exist is a setting problem and says
     * so. Silently falling back would leave someone wondering why the path they
     * typed is being ignored.
     */
    private Path findDll(Path workDir) throws BackendHealth.Unavailable {
        if (dllOverride != null) return dllOverride;

        String configured = configuredPath();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Files.isDirectory(p)) p = p.resolve(DLL_NAME);
            if (Files.isRegularFile(p)) return p;
            throw new BackendHealth.Unavailable(BackendHealth.State.MISCONFIGURED,
                    "mysticLightSdkPath in [hardware] points at a file that does not exist.",
                    "Point it at " + DLL_NAME + ", or at the folder holding it.",
                    "Looked for: " + p);
        }

        Path p = workDir.resolve(DLL_NAME);
        if (Files.isRegularFile(p)) return p;

        // No DLL. Worth a chat message only if this player has MSI stuff, or
        // literally typed msi into the config. Everyone else gets the usual
        // quiet "not installed" and never hears the word Mystic.
        List<String> found = MsiDetection.findMsi();
        String because = null;
        if (!found.isEmpty()) {
            because = found.get(0);
        } else if (explicitlySelected()) {
            because = "lightingBackend is set to msi";
        }
        sdkWantedBecause = because;
        String detail = "Looked for: " + p
                + (found.isEmpty() ? "\nNo MSI hardware found." : "\nMSI hardware found:\n  " + String.join("\n  ", found));
        if (because != null) {
            throw new BackendHealth.Unavailable(BackendHealth.State.NOT_INSTALLED,
                    "MSI hardware found (" + because + "), but MSI's Mystic Light SDK is missing, so it was not tried.",
                    "Run /rgbprofiles msi for MSI's download link and where to put " + DLL_NAME + ".",
                    detail);
        }
        throw new BackendHealth.Unavailable(BackendHealth.State.NOT_INSTALLED,
                "MSI's Mystic Light SDK was not found, so MSI lighting was not tried.",
                "If you have MSI lighting: install MSI Center with Mystic Light, download MSI's Mystic Light SDK, "
                        + "and put " + DLL_NAME + " in config/everythingrgbprofiles/native. If not, ignore this line.",
                detail);
    }

    /** Read defensively for the same reason as {@link #debugEnabled}. */
    private static boolean explicitlySelected() {
        try {
            return "msi".equalsIgnoreCase(RGBProfileConfig.LIGHTING_BACKEND.get().trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Refuses anything that isn't byte for byte a DLL MSI shipped. See "And
     * only MSI's DLL, thanks" up top for why this exists at all.
     *
     * <p>The test seam goes through here too. The probe gets no special
     * treatment, it's pointed at MSI's file like everyone else.
     */
    private static void verifyDll(Path dll) throws BackendHealth.Unavailable {
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dll));
            hash = HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BackendHealth.Unavailable(BackendHealth.State.MISCONFIGURED,
                    "The Mystic Light SDK file could not be read, so it was not loaded.",
                    "Check that " + DLL_NAME + " is not open in another program, then restart Minecraft.",
                    dll + "\n" + BackendHealth.describe(e));
        }
        if (!KNOWN_DLL_SHA256.contains(hash)) {
            throw new BackendHealth.Unavailable(BackendHealth.State.MISCONFIGURED,
                    "The " + DLL_NAME + " found is not a version this mod recognises, so it was not loaded.",
                    "Use " + DLL_NAME + " from MSI's Mystic Light SDK 1.0.0.08, downloaded from MSI directly. "
                            + "If you got it from MSI and it is a newer version, " + BackendHealth.SEND_REPORT,
                    dll + "\nSHA-256: " + hash);
        }
    }

    /** Wrapped for the same reason as {@link #debugEnabled}. */
    private static String configuredPath() {
        try {
            return RGBProfileConfig.MYSTIC_LIGHT_SDK_PATH.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Debug flag, read defensively, because {@code tuning/MysticLightProbe}
     * runs this class outside a loaded game, where reaching a Forge config
     * throws an Error.
     */
    private static boolean debugEnabled() {
        try {
            return RGBProfileConfig.DEBUG_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int parseCount(String text) {
        if (text == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * MSI type string -> our class.
     *
     * <p>MSI's reference does not list the type strings, so this matches on
     * pieces of them instead of exact values. Keyboard is checked first, for
     * the same reason as {@code DeviceClass.fromCorsairType}: if a type reads
     * as a keyboard at all, it is a keyboard. Anything unrecognised, graphics
     * cards included, lands in OTHER, which [devices] gates as
     * otherDevicesEnabled.
     */
    static KeyGrid.DeviceClass deviceClassOf(String type) {
        String t = type.toUpperCase(Locale.ROOT);
        if (t.contains("KEYBOARD") || t.contains("_KB")) return KeyGrid.DeviceClass.KEYBOARD;
        if (t.contains("MOUSEPAD") || t.contains("MOUSEMAT") || t.contains("_PAD")) return KeyGrid.DeviceClass.MOUSEMAT;
        if (t.contains("MOUSE")) return KeyGrid.DeviceClass.MOUSE;
        if (t.contains("HEADSET")) return KeyGrid.DeviceClass.HEADSET;
        if (t.contains("DRAM") || t.contains("MEMORY")) return KeyGrid.DeviceClass.MEMORY;
        if (t.contains("COOLER") || t.contains("FAN") || t.contains("AIO")) return KeyGrid.DeviceClass.COOLING;
        if (t.contains("_MB") || t.contains("MAINBOARD") || t.contains("MOTHERBOARD")) {
            return KeyGrid.DeviceClass.MOTHERBOARD;
        }
        return KeyGrid.DeviceClass.OTHER;
    }
}
