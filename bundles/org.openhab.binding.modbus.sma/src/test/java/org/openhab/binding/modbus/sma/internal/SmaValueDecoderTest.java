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

import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.modbus.sma.internal.SmaChannel.*;

import java.math.BigDecimal;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Register;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.UnDefType;

/**
 * Tests for {@link SmaValueDecoder}. Raw values as read from real devices where available.
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
class SmaValueDecoderTest {

    private static byte[] bytes(int... words) {
        byte[] b = new byte[words.length * 2];
        for (int i = 0; i < words.length; i++) {
            b[2 * i] = (byte) (words[i] >> 8);
            b[2 * i + 1] = (byte) words[i];
        }
        return b;
    }

    private static Register first(SmaChannel channel) {
        return channel.getCandidates().get(0);
    }

    private static Optional<BigDecimal> decode(SmaChannel channel, int... words) {
        return SmaValueDecoder.decode(bytes(words), 0, first(channel));
    }

    @Test
    void scalesFixFormats() {
        assertEquals(new BigDecimal("103"), decode(AC_ACTIVE_POWER, 0x0000, 0x0067).orElseThrow());
        assertEquals(new BigDecimal("230.50"), decode(AC_VOLTAGE_L1, 0x0000, 0x5A0A).orElseThrow());
        assertEquals(new BigDecimal("50.01"), decode(AC_FREQUENCY, 0x0000, 0x1389).orElseThrow());
        assertEquals(new BigDecimal("27.6"), decode(DIAGNOSTICS_INTERNAL_TEMPERATURE, 0x0000, 0x0114).orElseThrow());
    }

    @Test
    void decodesSignedValues() {
        // battery current -0.925 A (S32 FIX3)
        assertEquals(new BigDecimal("-0.925"), decode(BATTERY_CURRENT, 0xFFFF, 0xFC63).orElseThrow());
    }

    @Test
    void decodesU64() {
        // total yield 36,804,250 Wh
        assertEquals(new BigDecimal("36804250"),
                decode(ENERGY_TOTAL_YIELD, 0x0000, 0x0000, 0x0231, 0x969A).orElseThrow());
    }

    @Test
    void recognisesNaN() {
        assertTrue(decode(AC_ACTIVE_POWER, 0x8000, 0x0000).isEmpty(), "S32");
        assertTrue(decode(AC_VOLTAGE_L1, 0xFFFF, 0xFFFF).isEmpty(), "U32");
        assertTrue(decode(ENERGY_TOTAL_YIELD, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF).isEmpty(), "U64");
        assertTrue(decode(STATUS_GRID_RELAY, 0x00FF, 0xFFFD).isEmpty(), "TAGLIST information not available");
        // the status NaN code is a valid number for non-TAGLIST registers
        assertEquals(new BigDecimal("16777213"), decode(DIAGNOSTICS_EVENT_COUNT_USER, 0x00FF, 0xFFFD).orElseThrow());
    }

    @Test
    void missingBytesGiveEmpty() {
        assertTrue(SmaValueDecoder.decode(bytes(0x0000), 0, first(AC_ACTIVE_POWER)).isEmpty());
        assertTrue(SmaValueDecoder.decode(bytes(0x0000, 0x0001), 2, first(AC_ACTIVE_POWER)).isEmpty());
    }

    @Test
    void decodesAtOffset() {
        byte[] block = bytes(0x1111, 0x2222, 0x0000, 0x0133);
        assertEquals(new BigDecimal("307"), SmaValueDecoder.decode(block, 4, first(STATUS_HEALTH)).orElseThrow());
    }

    @Test
    void statesFollowNanPolicy() {
        assertEquals(new QuantityType<>(BigDecimal.ZERO, Units.WATT),
                SmaValueDecoder.toState(AC_ACTIVE_POWER, Optional.empty()), "ZERO");
        assertEquals(UnDefType.UNDEF, SmaValueDecoder.toState(AC_VOLTAGE_L1, Optional.empty()), "UNDEF");
        assertNull(SmaValueDecoder.toState(ENERGY_TOTAL_YIELD, Optional.empty()), "HOLD keeps the last state");
    }

    @Test
    void statesCarryUnits() {
        assertEquals(new QuantityType<>(new BigDecimal("27.6"), SIUnits.CELSIUS),
                SmaValueDecoder.toState(DIAGNOSTICS_INTERNAL_TEMPERATURE, Optional.of(new BigDecimal("27.6"))));
        assertEquals(new QuantityType<>(new BigDecimal("53"), Units.PERCENT),
                SmaValueDecoder.toState(BATTERY_STATE_OF_CHARGE, Optional.of(new BigDecimal("53"))));
        assertEquals(new DecimalType(307), SmaValueDecoder.toState(STATUS_HEALTH, Optional.of(new BigDecimal(307))));
    }

    @Test
    void formatsFirmware() {
        // read from the devices: 30059/30060 = 0x0408 0x2704 and 0x0410 0x0A04
        assertEquals("4.8.39.R", SmaValueDecoder.firmware(0x04082704L));
        assertEquals("4.10.10.R", SmaValueDecoder.firmware(0x04100A04L));
        assertEquals("1.7.7.R", SmaValueDecoder.firmware(0x01070704L));
        assertEquals("2.0.1.7", SmaValueDecoder.firmware(0x02000107L));
    }
}
