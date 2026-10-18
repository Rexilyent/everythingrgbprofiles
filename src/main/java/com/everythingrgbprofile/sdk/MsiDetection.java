package com.everythingrgbprofile.sdk;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Does this PC have anything MSI that lights up, and is Mystic Light awake.
 * Answered without loading a single byte of MSI's code, which is the point.
 *
 * <h2>Why bother</h2>
 * The Mystic Light SDK isn't ours to ship (see {@link MysticLightBackend}), so
 * an MSI owner has to go fetch it. They can't know that unless something
 * tells them, and telling everybody would be spamming a modpack full of people
 * who have never owned anything MSI in their lives. So this decides who gets
 * told.
 *
 * <h2>What counts as MSI</h2>
 * Any one of these is enough:
 * <ul>
 *   <li>Mystic Light is installed, i.e. MSI Center put LEDKeeper2.exe on disk.
 *       Strongest hint there is. Nobody installs that for fun.</li>
 *   <li>The motherboard says Micro-Star in the BIOS info Windows keeps in the
 *       registry.</li>
 *   <li>A USB device with MSI's vendor ID, 1462. Keyboards, mice, coolers,
 *       and the board's own lighting controller all show up here.</li>
 *   <li>A PCI device whose subsystem vendor is 1462, which is how an MSI
 *       graphics card gives itself away.</li>
 * </ul>
 * Windows remembers USB devices that were plugged in once and then never
 * again, so this can say yes for a mouse that went in a drawer two years ago.
 * MSI monitors also show up as a USB device (it's how MSI's on-screen settings
 * app talks to them), RGB or not, and a vendor ID can't tell those apart. So
 * the chat asks "if it has RGB" instead of announcing lighting it can't
 * actually see. Worst case is one chat message they can turn off.
 *
 * <p>All of it is plain registry and file reads. No admin, no WMI, no waiting.
 */
public final class MsiDetection {

    /** What MSI Center's Mystic Light module installs and the SDK goes looking for. */
    private static final Path LEDKEEPER2 = Path.of(
            System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)"),
            "MSI", "MSI Center", "Mystic Light", "LEDKeeper2.exe");

    /**
     * Process names the SDK DLL checks for. Both are spelled out inside MSI's
     * DLL. LEDKeeper.exe is presumably the older one; only LEDKeeper2 has
     * actually been seen running.
     */
    private static final List<String> LEDKEEPER_PROCESSES = List.of("ledkeeper2.exe", "ledkeeper.exe");

    private static final String MSI_VENDOR = "1462";
    private static final Pattern MSI_PCI_SUBSYSTEM = Pattern.compile("SUBSYS_[0-9A-F]{4}" + MSI_VENDOR + "\\b",
            Pattern.CASE_INSENSITIVE);

    private MsiDetection() {
    }

    /**
     * Every reason this PC looks like it has MSI lighting, for the report.
     * Empty means nothing MSI was found, and the player is left alone.
     *
     * <p>Each check catches its own failure. A registry key Windows decided
     * not to have is a "no", never a crash.
     */
    public static List<String> findMsi() {
        List<String> found = new ArrayList<>();
        if (Files.isRegularFile(LEDKEEPER2)) found.add("Mystic Light is installed (" + LEDKEEPER2 + ")");

        String board = registryString("HARDWARE\\DESCRIPTION\\System\\BIOS", "BaseBoardManufacturer");
        if (board != null && board.toLowerCase(Locale.ROOT).contains("micro-star")) {
            String product = registryString("HARDWARE\\DESCRIPTION\\System\\BIOS", "BaseBoardProduct");
            found.add("MSI motherboard (" + (product == null ? board : product) + ")");
        }

        // A USB device with a HID interface shows up under both, same name
        // and all, so HID only gets a look if USB came up empty.
        for (String bus : List.of("USB", "HID")) {
            int before = found.size();
            for (String key : registryKeys("SYSTEM\\CurrentControlSet\\Enum\\" + bus)) {
                if (key.toUpperCase(Locale.ROOT).startsWith("VID_" + MSI_VENDOR)) {
                    found.add("MSI " + bus + " device (" + key + ")");
                }
            }
            if (found.size() > before) break;
        }

        for (String key : registryKeys("SYSTEM\\CurrentControlSet\\Enum\\PCI")) {
            // Every chipset device on an MSI board matches this too. Which is
            // fine, that's an MSI board, but one line about it is plenty.
            if (MSI_PCI_SUBSYSTEM.matcher(key).find()) {
                found.add("MSI PCI device (" + key + ")");
                break;
            }
        }
        return found;
    }

    /**
     * Whether LEDKeeper2 (or its older sibling) is running. Null when Windows
     * wouldn't hand over a process list, which means "no idea", not "no".
     *
     * <p>The SDK only connects while this is running. Without it,
     * {@code MLAPI_Initialize} sits there for about ten seconds and then
     * returns -3. With it, 0 in a few milliseconds. So asking first turns a
     * ten second stall into a check that costs basically nothing.
     *
     * <p>Toolhelp rather than {@link ProcessHandle}, because ProcessHandle
     * can't read the name of a process running as another user, and nobody
     * here knows which user MSI's scheduled task starts it as.
     */
    public static Boolean ledKeeperRunning() {
        WinNT.HANDLE snapshot;
        try {
            snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new WinDef.DWORD(0));
        } catch (Throwable t) {
            return null;
        }
        if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) return null;
        try {
            Tlhelp32.PROCESSENTRY32.ByReference entry = new Tlhelp32.PROCESSENTRY32.ByReference();
            if (!Kernel32.INSTANCE.Process32First(snapshot, entry)) return null;
            do {
                String name = Native.toString(entry.szExeFile).toLowerCase(Locale.ROOT);
                if (LEDKEEPER_PROCESSES.contains(name)) return true;
            } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
            return false;
        } catch (Throwable t) {
            return null;
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }
    }

    private static String registryString(String key, String value) {
        try {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, key, value)) return null;
            return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, value);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] registryKeys(String key) {
        try {
            return Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, key);
        } catch (Throwable t) {
            return new String[0];
        }
    }
}
