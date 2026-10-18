package com.everythingrgbprofile.sdk;

import com.sun.jna.platform.win32.COM.util.IUnknown;
import com.sun.jna.platform.win32.COM.util.annotation.ComInterface;
import com.sun.jna.platform.win32.COM.util.annotation.ComMethod;
import com.sun.jna.platform.win32.COM.util.annotation.ComObject;
import com.sun.jna.platform.win32.COM.util.annotation.ComProperty;

/**
 * JNA COM mapping of ASUS's Aura SDK, the {@code aura.sdk.1} object Armoury
 * Crate registers.
 *
 * <p>ASUS ship no header and no documentation with it, just
 * {@code AuraSdk_x64.dll}. Every interface, IID and dispatch id below was
 * read out of the type library compiled into that DLL (Armoury Crate's copy,
 * built August 2024), so the shapes are ASUS's own. What the type library
 * cannot say is what the numbers MEAN, and those gaps are marked where they
 * matter.
 *
 * <p>Calls go through {@code IDispatch} with the dispatch ids pinned, rather
 * than by name. Same result, minus a name lookup per call, and per call is
 * exactly how often lighting gets set.
 *
 * <p>Only the parts this mod uses are mapped. The type library has plenty
 * more (standby lighting, temperature sensors, a whole hardware abstraction
 * layer) and none of it is any of our business.
 */
final class AuraSdk {

    private AuraSdk() {
    }

    /** {@code IAuraSdk3}, the newest of the three SDK interfaces the type library lists. */
    @ComObject(progId = "aura.sdk.1")
    @ComInterface(iid = "{B9A57A8A-7CAF-46D8-960B-2328330FE8C6}")
    interface Sdk extends IUnknown {

        /** 0 asks for every device. The type-specific codes are not in the type library. */
        @ComMethod(dispId = 1)
        DeviceCollection Enumerate(int devType);

        /** Takes lighting control away from Armoury Crate. */
        @ComMethod(dispId = 2)
        void SwitchMode();

        /** Hands it back, and Armoury Crate puts its own lighting back on. The argument is named "reserve". */
        @ComMethod(dispId = 3)
        void ReleaseControl(int reserve);
    }

    @ComInterface(iid = "{87FC56AB-99CA-4FD3-B561-2EEDD719DA57}")
    interface DeviceCollection extends IUnknown {
        @ComProperty(dispId = 2)
        int getCount();

        @ComProperty(dispId = 0)
        Device Item(int index);
    }

    /** {@code IAuraSyncDevice}. */
    @ComInterface(iid = "{6A30D789-F5DA-4F26-BF09-6DFB9DEDF91E}")
    interface Device extends IUnknown {
        @ComProperty(dispId = 7)
        LightCollection getLights();

        @ComProperty(dispId = 8)
        int getType();

        @ComProperty(dispId = 9)
        String getName();

        @ComProperty(dispId = 13)
        int getWidth();

        @ComProperty(dispId = 14)
        int getHeight();

        /** Sends whatever colours have been set on this device's lights. Nothing changes until this runs. */
        @ComMethod(dispId = 1)
        void Apply();
    }

    /**
     * {@code IAuraSyncKeyboard}: a device that also knows where its keys
     * are. Reached by {@code QueryInterface} on a {@link Device}, which simply
     * fails for anything that is not a keyboard.
     */
    @ComInterface(iid = "{87B03DD6-59E6-44F5-8772-56A7494F9900}")
    interface Keyboard extends IUnknown {
        @ComProperty(dispId = 16)
        KeyCollection getKeys();
    }

    @ComInterface(iid = "{003065BC-B562-454E-ADCB-A9B70042D486}")
    interface LightCollection extends IUnknown {
        @ComProperty(dispId = 2)
        int getCount();

        @ComProperty(dispId = 0)
        Light Item(int index);
    }

    @ComInterface(iid = "{81C61F82-4DA0-4081-BDDE-108E1FCDDA7F}")
    interface KeyCollection extends IUnknown {
        @ComProperty(dispId = 2)
        int getCount();

        @ComProperty(dispId = 0)
        Key Item(int index);
    }

    /**
     * {@code IAuraRgbLight}.
     *
     * <p>The channels are set one at a time rather than through the packed
     * {@code Color} property, because the type library says {@code Color} is a
     * 32-bit number and nothing about which byte is which. Three calls cost
     * more than one. A board in the wrong colours costs more than that.
     *
     * <p>Setters take {@code int}, not {@code byte}: a Java byte goes over as
     * a SIGNED COM byte, and anything above 127 would then fail to convert to
     * the unsigned one ASUS wants.
     */
    @ComInterface(iid = "{9AF6260E-4311-417D-B3EF-B85A34CF3244}")
    interface Light extends IUnknown {
        @ComProperty(dispId = 1)
        void setRed(int value);

        @ComProperty(dispId = 2)
        void setGreen(int value);

        @ComProperty(dispId = 3)
        void setBlue(int value);

        @ComProperty(dispId = 4)
        String getName();
    }

    /** {@code IAuraRgbKey}: a {@link Light} plus a key code and a position. */
    @ComInterface(iid = "{95E6C828-6DE4-441E-873A-C44AE850F3A2}")
    interface Key extends IUnknown {
        @ComProperty(dispId = 1)
        void setRed(int value);

        @ComProperty(dispId = 2)
        void setGreen(int value);

        @ComProperty(dispId = 3)
        void setBlue(int value);

        @ComProperty(dispId = 4)
        String getName();

        @ComProperty(dispId = 6)
        int getCode();

        @ComProperty(dispId = 7)
        int getX();

        @ComProperty(dispId = 8)
        int getY();
    }
}
