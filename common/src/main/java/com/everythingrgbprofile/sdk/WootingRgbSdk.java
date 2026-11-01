package com.everythingrgbprofile.sdk;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * JNA mapping of the Wooting RGB SDK ({@code wooting-rgb-sdk64.dll}), v1.8.0.
 *
 * <p>Only what {@link WootingBackend} actually calls. The DLL exports a pile
 * more (raw USB buffer sends, feature reports, single-key direct writes) and
 * we are not touching any of it. The array functions below already do the
 * whole board in one go, and they already know which of Wooting's three
 * report formats each board speaks. Reimplementing that by hand to save
 * nothing is a hobby, not a feature.
 *
 * <h2>Bools are bytes, again</h2>
 * Same deal as {@link LogitechLedSdk}. These return C {@code bool}, which MSVC
 * makes one byte, and JNA's {@code boolean} would read a whole register of
 * whatever junk is sitting above it. {@code byte}, test {@code != 0}, done.
 * {@code uint8_t} parameters are bytes for the same reason, just without the
 * junk problem.
 *
 * <h2>Call order is load-bearing</h2>
 * {@link #wooting_rgb_kbd_connected} first, always. Until it has run once the
 * SDK's "current device" pointer is NULL, and the array functions dereference
 * it without checking. So calling {@link #wooting_rgb_array_set_full} on a
 * fresh DLL is not an error code. It is the game closing.
 */
interface WootingRgbSdk extends Library {

    int ROWS = 6;
    int COLS = 21;
    /** {@link #wooting_rgb_array_set_full} always wants the full 6x21 RGB, whatever the board's real width. */
    int FULL_BUFFER_SIZE = ROWS * COLS * 3;

    /** {@code WOOTING_DEVICE_TYPE}: the UwU macropad. Everything else is some size of keyboard. */
    int DEVICE_KEYPAD_3KEY = 4;

    /**
     * Enumerates every Wooting device on first call, opens them, selects the
     * first. Later calls just return true without looking again, so this is
     * not a hotplug check, whatever the name suggests.
     */
    byte wooting_rgb_kbd_connected();

    /** How many devices enumeration opened. {@code uint8_t}. */
    byte wooting_usb_device_count();

    /**
     * Per-device info without changing which device is selected. NULL if the
     * index is out of range. The struct is read by offset in
     * {@link WootingBackend}, see there for the layout.
     */
    Pointer wooting_usb_get_device_meta(byte deviceIndex);

    /** Points every following call at this device, buffer included. */
    byte wooting_usb_select_device(byte deviceIndex);

    /** Copies a {@link #FULL_BUFFER_SIZE} R,G,B buffer into the selected device's colour array. Sends nothing. */
    byte wooting_rgb_array_set_full(byte[] colorsBuffer);

    /**
     * Sends the selected device's colour array to the hardware. False means
     * the write failed, and the SDK has already reacted by closing EVERY
     * device it had open, not just this one.
     */
    byte wooting_rgb_array_update_keyboard();

    /** Puts every device back on its own profile colours and closes all handles. Safe with nothing open. */
    byte wooting_rgb_close();
}
