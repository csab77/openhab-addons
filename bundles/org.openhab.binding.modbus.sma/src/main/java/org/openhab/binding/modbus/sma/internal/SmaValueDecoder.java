/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.modbus.sma.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Format;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Register;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

/**
 * The {@link SmaValueDecoder} converts raw SMA register contents into values and channel states.
 * <p>
 * SMA registers are big endian with the high word first. Each data type has a dedicated NaN value
 * (DESIGN §5): S16 0x8000, S32 0x80000000, U16/U32/U64 all bits set, and for status values (TAGLIST)
 * 0x00FFFFFD ("information not available").
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
public final class SmaValueDecoder {

    /** Status code "information not available" of TAGLIST registers. */
    public static final long NAN_STATUS = 0x00FFFFFDL;

    private static final String RELEASE_TYPES = "NEABRS";

    private SmaValueDecoder() {
        // static helpers only
    }

    /**
     * Reads the unsigned raw value of a register.
     *
     * @param bytes register contents as returned by the Modbus transport (2 bytes per register)
     * @param byteOffset offset of the register's first byte
     * @param register the register to read
     * @return the raw value, or empty if the bytes do not cover the register
     */
    public static Optional<BigInteger> raw(byte[] bytes, int byteOffset, Register register) {
        int length = register.count() * 2;
        if (byteOffset < 0 || byteOffset + length > bytes.length) {
            return Optional.empty();
        }
        byte[] value = new byte[length + 1]; // leading zero byte keeps the value unsigned
        System.arraycopy(bytes, byteOffset, value, 1, length);
        return Optional.of(new BigInteger(value));
    }

    /** Whether the raw value is the SMA NaN value for the register's data type and format. */
    public static boolean isNaN(BigInteger raw, Register register) {
        int bits = register.type().getBits();
        if (isSigned(register)) {
            return raw.equals(BigInteger.ONE.shiftLeft(bits - 1));
        }
        if (raw.equals(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE))) {
            return true;
        }
        return register.format() == Format.TAGLIST && raw.longValue() == NAN_STATUS;
    }

    /**
     * Decodes and scales a register.
     *
     * @return the scaled value, or empty for the SMA NaN value (or missing bytes)
     */
    public static Optional<BigDecimal> decode(byte[] bytes, int byteOffset, Register register) {
        Optional<BigInteger> raw = raw(bytes, byteOffset, register);
        if (raw.isEmpty() || isNaN(raw.get(), register)) {
            return Optional.empty();
        }
        BigInteger value = raw.get();
        int bits = register.type().getBits();
        if (isSigned(register) && value.testBit(bits - 1)) {
            value = value.subtract(BigInteger.ONE.shiftLeft(bits));
        }
        return Optional.of(new BigDecimal(value, register.format().decimals));
    }

    /**
     * Converts a decoded value into the channel state according to the channel's NaN policy.
     *
     * @param value decoded value, empty for NaN
     * @return the state to publish, or {@code null} to keep the last state (NaN with policy HOLD)
     */
    public static @Nullable State toState(SmaChannel channel, Optional<BigDecimal> value) {
        if (value.isPresent()) {
            return state(value.get(), channel.getUnit());
        }
        return switch (channel.getNanPolicy()) {
            case ZERO -> state(BigDecimal.ZERO, channel.getUnit());
            case UNDEF -> UnDefType.UNDEF;
            case HOLD -> null;
        };
    }

    /**
     * Formats an SMA firmware version (format FW): byte 1 major and byte 2 minor in BCD, byte 3 build,
     * byte 4 release type (N, E, A, B, R, S).
     */
    public static String firmware(long raw) {
        int major = bcd((int) (raw >> 24) & 0xFF);
        int minor = bcd((int) (raw >> 16) & 0xFF);
        int build = (int) (raw >> 8) & 0xFF;
        int type = (int) raw & 0xFF;
        String release = type < RELEASE_TYPES.length() ? String.valueOf(RELEASE_TYPES.charAt(type))
                : String.valueOf(type);
        return major + "." + minor + "." + build + "." + release;
    }

    private static int bcd(int value) {
        return (value >> 4) * 10 + (value & 0x0F);
    }

    private static boolean isSigned(Register register) {
        return register.type().name().startsWith("INT");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static State state(BigDecimal value, @Nullable Unit<?> unit) {
        return unit == null ? new DecimalType(value) : new QuantityType(value, (Unit) unit);
    }
}
