package com.everythingrgbprofile.sdk;

import com.sun.jna.Library;
import com.sun.jna.platform.win32.WTypes.BSTR;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA mapping of MSI's Mystic Light SDK, {@code MysticLight_SDK_x64.dll}.
 *
 * <p>Only the functions this mod calls are here. Signatures are from MSI's
 * "Mystic Light Software Development Kit", version 1.0.0.08, and
 * the C++ sample that ships next to it.
 *
 * <h2>COM types in a plain C export, because of course</h2>
 * The DLL exports ordinary functions, but they speak COM's string and array
 * types instead of plain C ones:
 * <ul>
 *   <li><b>BSTR</b> going in (the device type) is ours to allocate with
 *       {@code SysAllocString} and ours to free.</li>
 *   <li><b>BSTR</b> coming out arrives through a {@link PointerByReference}
 *       and is ours to free with {@code SysFreeString}.</li>
 *   <li><b>SAFEARRAY</b> coming out (device lists, LED styles) also arrives
 *       through a {@link PointerByReference}, holds BSTRs, and is ours to
 *       free with {@code SafeArrayDestroy}, which takes the strings with it.</li>
 * </ul>
 * Forget any one of those and nothing breaks today. It just leaks a little
 * every time, forever, inside somebody's game. {@link MysticLightBackend}
 * keeps all of it in a couple of helpers so there is one place to get it right.
 *
 * <p>Every {@code DWORD} is a Java {@code int}, and every function returns one
 * of the status codes below.
 */
interface MysticLightSdk extends Library {

    int MLAPI_OK = 0;
    int MLAPI_ERROR = -1;
    int MLAPI_TIMEOUT = -2;
    /** "MSI application not found or installed version not supported." */
    int MLAPI_NO_IMPLEMENTED = -3;
    int MLAPI_NOT_INITIALIZED = -4;
    int MLAPI_INVALID_ARGUMENT = -101;
    int MLAPI_DEVICE_NOT_FOUND = -102;
    int MLAPI_NOT_SUPPORTED = -103;

    int MLAPI_Initialize();

    int MLAPI_Release();

    /** Out: a BSTR. MSI promises it is never null, which is nice of them. */
    int MLAPI_GetErrorMessage(int errorCode, PointerByReference desc);

    /**
     * Out: two parallel SAFEARRAYs of BSTR. The first holds device type
     * strings, the second holds each type's LED count, as a number written out
     * as a string. Yes, really.
     */
    int MLAPI_GetDeviceInfo(PointerByReference devTypes, PointerByReference ledCounts);

    /** Out: a BSTR. */
    int MLAPI_GetDeviceNameEx(BSTR type, int index, PointerByReference name);

    /** Out: a BSTR name and a SAFEARRAY of BSTR style names. */
    int MLAPI_GetLedInfo(BSTR type, int index, PointerByReference name, PointerByReference styles);

    int MLAPI_GetLedColor(BSTR type, int index, IntByReference r, IntByReference g, IntByReference b);

    /** Out: a BSTR. */
    int MLAPI_GetLedStyle(BSTR type, int index, PointerByReference style);

    int MLAPI_SetLedColor(BSTR type, int index, int r, int g, int b);

    int MLAPI_SetLedStyle(BSTR type, int index, BSTR style);
}
