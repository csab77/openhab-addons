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

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.sma.internal.SmaChannel.PollClass;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.ProbeResult;
import org.openhab.binding.modbus.sma.internal.SmaReadPlan.Block;

/**
 * Tests for {@link SmaReadPlan} with the channels detected on two real devices.
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
class SmaReadPlanTest {

    private static List<Block> plan(String thingType, Map<Integer, ProbeResult> probes) {
        return SmaReadPlan.build(
                SmaChannelDetector.decide(SmaChannelDetector.declaredChannels(thingType), probes, true, 0).selected());
    }

    private static List<String> ranges(List<Block> blocks, boolean holding, PollClass pollClass) {
        return blocks.stream().filter(b -> b.holding() == holding && b.pollClass() == pollClass)
                .map(b -> b.start() + "+" + b.length()).toList();
    }

    @Test
    void hybridInverter() {
        List<Block> blocks = plan("sma-hybrid-inverter", SmaProbeFixtures.STP_SE);

        assertEquals(List.of("30201+48", "30769+100", "30949+34", "31247+2", "31393+4", "31497+2", "33003+2"),
                ranges(blocks, false, PollClass.FAST));
        assertEquals(List.of("30513+12", "30559+42", "30881+2", "31057+2", "31391+14"),
                ranges(blocks, false, PollClass.SLOW));
        assertEquals(List.of("40029+2"), ranges(blocks, true, PollClass.FAST));
        assertEquals(List.of("40187+2"), ranges(blocks, true, PollClass.SLOW));
    }

    @Test
    void pvInverter() {
        List<Block> blocks = plan("sma-inverter", SmaProbeFixtures.STP_3AV);

        assertEquals(List.of("30201+20", "30769+46", "30949+34", "31247+2"), ranges(blocks, false, PollClass.FAST));
        assertEquals(List.of("30225+2", "30513+52", "30599+2", "30881+2"), ranges(blocks, false, PollClass.SLOW));
        assertEquals(List.of("40029+2"), ranges(blocks, true, PollClass.FAST));
        assertTrue(ranges(blocks, true, PollClass.SLOW).isEmpty());
    }

    @Test
    void balanceChannelsShareOneRead() {
        // Without battery registers in between, AC power (30775) and grid draw (30865) are 88 registers apart –
        // more than the gap limit, but they must still be read together.
        Map<SmaChannel, SmaChannel.Register> selected = Map.of(AC_ACTIVE_POWER, AC_ACTIVE_POWER.getCandidates().get(0),
                METER_GRID_DRAW_POWER, METER_GRID_DRAW_POWER.getCandidates().get(0), METER_GRID_FEED_IN_POWER,
                METER_GRID_FEED_IN_POWER.getCandidates().get(0));
        List<Block> blocks = SmaReadPlan.build(selected);

        assertEquals(1, blocks.size());
        assertEquals("30775+94", blocks.get(0).start() + "+" + blocks.get(0).length());
        assertEquals(List.of(AC_ACTIVE_POWER, METER_GRID_DRAW_POWER, METER_GRID_FEED_IN_POWER),
                blocks.get(0).entries().stream().map(SmaReadPlan.Entry::channel).toList());
    }

    @Test
    void slowChannelsInsideFastBlockAreServedThere() {
        List<Block> blocks = plan("sma-hybrid-inverter", SmaProbeFixtures.STP_SE);
        Block block = blocks.stream().filter(b -> b.start() == 30769).findFirst().orElseThrow();

        // battery capacity (30847) and temperature (30849) are slow channels within 30769–30868
        assertTrue(block.entries().stream().anyMatch(e -> e.channel() == BATTERY_TEMPERATURE));
        assertEquals((30849 - 30769) * 2, block.byteOffset(
                block.entries().stream().filter(e -> e.channel() == BATTERY_TEMPERATURE).findFirst().orElseThrow()));
    }

    @Test
    void blocksRespectLimits() {
        for (Block b : plan("sma-hybrid-inverter", SmaProbeFixtures.STP_SE)) {
            assertTrue(b.length() <= SmaBindingConstants.MAX_BLOCK_LENGTH, b.toString());
            assertFalse(b.entries().isEmpty(), b.toString());
            assertEquals(b.start(), b.entries().get(0).register().address(), "block starts at a selected register");
        }
    }
}
