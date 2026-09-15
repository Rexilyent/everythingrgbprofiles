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
 * <h2>Why every "bool" is a byte here</h2>
 * These functions return C++ {@code bool}, which is one byte, delivered in the
 * low byte of the return register. JNA's {@code boolean} mapping reads a full
 * 32-bit value, and the upper three bytes of that register are whatever the
 * function happened to leave there — so a {@code false} can come back as
 * {@code true} and the backend would believe an SDK that refused it. Mapping to
 * {@code byte} reads exactly the byte that was returned. Test with {@code != 0}.
 */
interface LogitechLedSdk extends Library {

    /** Per-key RGB boards: {@code 1 << LOGI_DEVICETYPE_PERKEY_RGB_ORD}, where the ordinal is 2. */
    int LOGI_DEVICETYPE_PERKEY_RGB = 1 << 2;

    /** 21 x 6 keys, 4 bytes each, in B, G, R, A order. */
    int BITMAP_WIDTH = 21;
    int BITMAP_HEIGHT = 6;
    int BITMAP_SIZE = BITMAP_WIDTH * BITMAP_HEIGHT * 4;

    byte LogiLedInit();

    /** Newer builds only. Callers must catch {@link UnsatisfiedLinkError} and fall back to {@link #LogiLedInit}. */
    byte LogiLedInitWithName(String name);

    byte LogiLedSetTargetDevice(int targetDevice);

    byte LogiLedSaveCurrentLighting();

    byte LogiLedRestoreLighting();

    byte LogiLedSetLightingFromBitmap(byte[] bitmap);

    void LogiLedShutdown();
}
