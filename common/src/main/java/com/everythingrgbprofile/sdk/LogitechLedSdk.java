package com.everythingrgbprofile.sdk;

import com.sun.jna.Library;

/**
 * JNA mapping of Logitech's LED Illumination SDK.
 *
 * <p>G HUB ships this API as {@code sdk_legacy_led_x64.dll} in its install
 * folder; the older Logitech Gaming Software shipped it as
 * {@code LogitechLed.dll}. Same exports either way, so one interface covers
 * both.
 *
 * <h2>Why every "bool" in here is a byte</h2>
 * These functions return a C++ {@code bool}, which is one byte, delivered in
 * the low byte of the return register.
 *
 * <p>JNA's {@code boolean} mapping reads the full 32-bit value, and the upper
 * three bytes of that register are whatever the function happened to leave
 * lying there. Which means a {@code false} can come back as {@code true}, and
 * the backend would cheerfully believe an SDK that had just refused it.
 *
 * <p>Mapping to {@code byte} reads exactly the byte that was actually
 * returned. Test with {@code != 0} and move on with your life.
 */
interface LogitechLedSdk extends Library {

    /** Per-key RGB boards: {@code 1 << LOGI_DEVICETYPE_PERKEY_RGB_ORD}, where the ordinal is 2. */
    int LOGI_DEVICETYPE_PERKEY_RGB = 1 << 2;

    /** 21 x 6 keys, 4 bytes each, in B, G, R, A order. */
    int BITMAP_WIDTH = 21;
    int BITMAP_HEIGHT = 6;
    int BITMAP_SIZE = BITMAP_WIDTH * BITMAP_HEIGHT * 4;

    byte LogiLedInit();

    /** Newer builds only. Callers MUST catch {@link UnsatisfiedLinkError} and fall back to {@link #LogiLedInit}. */
    byte LogiLedInitWithName(String name);

    byte LogiLedSetTargetDevice(int targetDevice);

    byte LogiLedSaveCurrentLighting();

    byte LogiLedRestoreLighting();

    byte LogiLedSetLightingFromBitmap(byte[] bitmap);

    void LogiLedShutdown();
}
