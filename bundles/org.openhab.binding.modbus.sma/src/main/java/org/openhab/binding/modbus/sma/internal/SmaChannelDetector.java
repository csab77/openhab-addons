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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Detection;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Register;
import org.openhab.binding.modbus.sma.internal.SmaThingTypes.ThingTypeInfo;

/**
 * The {@link SmaChannelDetector} decides which channels a device supports (DESIGN §6).
 * <p>
 * SMA devices return NaN – not a Modbus exception – for registers they do not support, and many registers are NaN
 * at night. The decision is therefore value based:
 * <ul>
 * <li>{@link Detection#PERMANENT} channels: the first candidate returning a valid value is selected; if none does,
 * the channel is removed.</li>
 * <li>{@link Detection#OPERATION} channels are decided the same way, but only while the inverter feeds in. Otherwise
 * they stay pending: the first candidate without exception is read and the decision is repeated later.</li>
 * <li>If a group indicator (battery state of charge, grid draw counter) is not valid, its whole group is removed.</li>
 * <li>A configured number of MPP trackers removes the DC groups beyond it.</li>
 * </ul>
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
public final class SmaChannelDetector {

    /** Result of reading one candidate register on its own. */
    public enum ProbeResult {
        VALUE,
        NAN,
        EXCEPTION
    }

    /**
     * Result of the detection.
     *
     * @param selected channels to create with the register to read (active and pending)
     * @param pending channels whose decision waits for the inverter to feed in
     * @param removed channels the device does not support
     */
    public record Decision(Map<SmaChannel, Register> selected, Set<SmaChannel> pending, Set<SmaChannel> removed) {

        /** Channels decided as supported. */
        public Set<SmaChannel> active() {
            Set<SmaChannel> active = selected.isEmpty() ? EnumSet.noneOf(SmaChannel.class)
                    : EnumSet.copyOf(selected.keySet());
            active.removeAll(pending);
            return active;
        }
    }

    private static final List<String> DC_GROUPS = List.of("dc-a", "dc-b", "dc-c", "dc-d", "dc-e", "dc-f");

    private SmaChannelDetector() {
        // static helpers only
    }

    /** All channels declared by a thing type, in catalog order. */
    public static List<SmaChannel> declaredChannels(String thingTypeId) {
        ThingTypeInfo info = SmaThingTypes.THING_TYPES.get(thingTypeId);
        if (info == null) {
            return List.of();
        }
        return Arrays.stream(SmaChannel.values()).filter(c -> info.groups().contains(c.getGroupId())).toList();
    }

    /**
     * Whether the inverter is feeding in: grid relay closed and active power valid.
     *
     * @param gridRelay decoded grid relay status (register 30217), empty for NaN
     * @param activePowerValid whether the active power (30775) returned a valid value
     */
    public static boolean isInOperation(Optional<BigDecimal> gridRelay, boolean activePowerValid) {
        return activePowerValid
                && gridRelay.map(v -> v.intValue() == SmaBindingConstants.GRID_RELAY_CLOSED).orElse(false);
    }

    /**
     * Decides the channels.
     *
     * @param declared channels to decide (all channels of the thing type, or the pending ones later)
     * @param probes result per register address; addresses without result count as exception
     * @param inOperation whether the inverter feeds in (see {@link #isInOperation})
     * @param mppTrackers configured number of DC inputs, 0 for automatic
     */
    public static Decision decide(Collection<SmaChannel> declared, Map<Integer, ProbeResult> probes,
            boolean inOperation, int mppTrackers) {
        Map<SmaChannel, Register> selected = new EnumMap<>(SmaChannel.class);
        Set<SmaChannel> pending = EnumSet.noneOf(SmaChannel.class);
        Set<SmaChannel> removed = EnumSet.noneOf(SmaChannel.class);

        for (SmaChannel channel : declared) {
            int dcIndex = DC_GROUPS.indexOf(channel.getGroupId());
            if (mppTrackers > 0 && dcIndex >= mppTrackers) {
                removed.add(channel);
                continue;
            }
            @Nullable
            Register valid = null;
            @Nullable
            Register withoutException = null;
            for (Register candidate : channel.getCandidates()) {
                ProbeResult result = probes.getOrDefault(candidate.address(), ProbeResult.EXCEPTION);
                if (result == ProbeResult.VALUE) {
                    valid = candidate;
                    break;
                }
                if (result == ProbeResult.NAN && withoutException == null) {
                    withoutException = candidate;
                }
            }
            if (valid != null) {
                selected.put(channel, valid);
            } else if (channel.getDetection() == Detection.OPERATION && !inOperation && withoutException != null) {
                selected.put(channel, withoutException);
                pending.add(channel);
            } else {
                removed.add(channel);
            }
        }

        for (SmaChannel indicator : declared) {
            if (indicator.isIndicator() && (removed.contains(indicator) || pending.contains(indicator))) {
                for (SmaChannel channel : declared) {
                    if (channel.getGroupId().equals(indicator.getGroupId())) {
                        selected.remove(channel);
                        pending.remove(channel);
                        removed.add(channel);
                    }
                }
            }
        }
        return new Decision(Collections.unmodifiableMap(selected), Collections.unmodifiableSet(pending),
                Collections.unmodifiableSet(removed));
    }
}
