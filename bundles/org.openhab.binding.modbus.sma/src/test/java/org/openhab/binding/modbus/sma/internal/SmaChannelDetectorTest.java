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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;
import static org.openhab.binding.modbus.sma.internal.SmaChannel.*;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.Decision;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.ProbeResult;

/**
 * Tests for {@link SmaChannelDetector} with the probe results of two real devices.
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
class SmaChannelDetectorTest {

    private static final Set<SmaChannel> DC_C_TO_F = EnumSet.of(DC_C_CURRENT, DC_C_VOLTAGE, DC_C_POWER, DC_D_CURRENT,
            DC_D_VOLTAGE, DC_D_POWER, DC_E_CURRENT, DC_E_VOLTAGE, DC_E_POWER, DC_F_CURRENT, DC_F_VOLTAGE, DC_F_POWER);

    private static Decision decide(String thingType, Map<Integer, ProbeResult> probes, boolean inOperation,
            int mppTrackers) {
        return SmaChannelDetector.decide(SmaChannelDetector.declaredChannels(thingType), probes, inOperation,
                mppTrackers);
    }

    @Test
    void declaredChannelsFollowThingType() {
        assertEquals(73, SmaChannelDetector.declaredChannels("sma-hybrid-inverter").size());
        assertEquals(60, SmaChannelDetector.declaredChannels("sma-inverter").size());
        assertTrue(SmaChannelDetector.declaredChannels("sma-battery-inverter").stream()
                .noneMatch(c -> c.getGroupId().startsWith("dc-")));
        assertTrue(SmaChannelDetector.declaredChannels("unknown").isEmpty());
    }

    @Test
    void hybridInverterInOperation() {
        Decision d = decide("sma-hybrid-inverter", SmaProbeFixtures.STP_SE, true, 0);

        assertEquals(59, d.active().size());
        assertTrue(d.pending().isEmpty());
        Set<SmaChannel> expectedRemoved = EnumSet.copyOf(DC_C_TO_F);
        expectedRemoved.add(STATUS_DERATING_REASON);
        expectedRemoved.add(ENERGY_FEED_IN_TIME);
        assertEquals(expectedRemoved, d.removed());
        assertEquals(31497, requireNonNull(d.selected().get(AC_REACTIVE_POWER)).address());
        assertEquals(30513, requireNonNull(d.selected().get(ENERGY_TOTAL_YIELD)).address());
        assertEquals(30953, requireNonNull(d.selected().get(DIAGNOSTICS_INTERNAL_TEMPERATURE)).address());
        assertTrue(d.active().contains(BATTERY_STATE_OF_CHARGE));
        assertTrue(d.active().contains(METER_GRID_DRAW_POWER));
    }

    @Test
    void pvInverterInOperation() {
        Decision d = decide("sma-inverter", SmaProbeFixtures.STP_3AV, true, 0);

        assertEquals(42, d.active().size());
        assertTrue(d.pending().isEmpty());
        Set<SmaChannel> expectedRemoved = EnumSet.copyOf(DC_C_TO_F);
        expectedRemoved.addAll(EnumSet.of(METER_GRID_DRAW_POWER, METER_GRID_FEED_IN_POWER, METER_GRID_DRAW_ENERGY,
                METER_GRID_FEED_IN_ENERGY, STATUS_RUN_STATUS, STATUS_EVENT_NUMBER));
        assertEquals(expectedRemoved, d.removed());
        // 31497 is listed for this device but NaN, the old address 30805 delivers the value
        assertEquals(30805, requireNonNull(d.selected().get(AC_REACTIVE_POWER)).address());
    }

    @Test
    void notInOperationKeepsOperationChannelsPending() {
        Decision d = decide("sma-inverter", SmaProbeFixtures.STP_3AV, false, 0);

        assertEquals(42, d.active().size());
        assertEquals(DC_C_TO_F, d.pending());
        assertEquals(6, d.removed().size());
        assertTrue(d.removed().contains(STATUS_RUN_STATUS), "permanent channels are decided at once");
        assertEquals(54, d.selected().size(), "pending channels are created and read");

        Decision hybrid = decide("sma-hybrid-inverter", SmaProbeFixtures.STP_SE, false, 0);
        assertEquals(13, hybrid.pending().size());
        assertTrue(hybrid.pending().contains(STATUS_DERATING_REASON));
        assertEquals(EnumSet.of(ENERGY_FEED_IN_TIME), hybrid.removed());
    }

    @Test
    void pendingChannelsAreDecidedLater() {
        Decision night = decide("sma-inverter", SmaProbeFixtures.STP_3AV, false, 0);
        Decision day = SmaChannelDetector.decide(night.pending(), SmaProbeFixtures.STP_3AV, true, 0);

        assertTrue(day.selected().isEmpty());
        assertEquals(DC_C_TO_F, day.removed());
    }

    @Test
    void indicatorRemovesGroup() {
        Map<Integer, ProbeResult> probes = new java.util.HashMap<>(SmaProbeFixtures.STP_SE);
        probes.put(30845, ProbeResult.NAN); // battery state of charge
        Decision d = decide("sma-hybrid-inverter", probes, true, 0);

        assertTrue(d.active().stream().noneMatch(c -> "battery".equals(c.getGroupId())));
        assertTrue(d.removed().contains(BATTERY_CHARGE_POWER));
    }

    @Test
    void mppTrackersLimitDcGroups() {
        Decision d = decide("sma-hybrid-inverter", SmaProbeFixtures.STP_SE, true, 1);

        assertTrue(d.active().contains(DC_A_POWER));
        assertTrue(d.removed().contains(DC_B_POWER));
        assertEquals(56, d.active().size());
    }

    @Test
    void missingProbeCountsAsException() {
        Decision d = SmaChannelDetector.decide(EnumSet.of(AC_ACTIVE_POWER), Map.of(), false, 0);

        assertEquals(EnumSet.of(AC_ACTIVE_POWER), d.removed());
    }

    @Test
    void operationNeedsClosedRelayAndValidPower() {
        assertTrue(SmaChannelDetector.isInOperation(Optional.of(new BigDecimal(51)), true));
        assertFalse(SmaChannelDetector.isInOperation(Optional.of(new BigDecimal(311)), true));
        assertFalse(SmaChannelDetector.isInOperation(Optional.empty(), true));
        assertFalse(SmaChannelDetector.isInOperation(Optional.of(new BigDecimal(51)), false));
    }
}
