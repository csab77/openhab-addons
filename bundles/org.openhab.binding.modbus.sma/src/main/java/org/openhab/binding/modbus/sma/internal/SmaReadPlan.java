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

import static org.openhab.binding.modbus.sma.internal.SmaBindingConstants.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.sma.internal.SmaChannel.PollClass;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Register;

/**
 * The {@link SmaReadPlan} groups the selected channel registers into Modbus read requests (DESIGN §7).
 * <p>
 * Rules:
 * <ul>
 * <li>Separate blocks per function code (input / holding) and poll class (fast / slow).</li>
 * <li>A block grows while its length stays ≤ {@value SmaBindingConstants#MAX_BLOCK_LENGTH} registers and the gap to
 * the next register is ≤ {@value SmaBindingConstants#MAX_BLOCK_GAP}. Unused registers in between are read and
 * ignored.</li>
 * <li>Balance channels (AC power, grid draw, grid feed-in) are always joined into one block, so the power
 * balance is taken from one consistent snapshot.</li>
 * <li>Slow channels lying inside a fast block are served from that block.</li>
 * </ul>
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
public final class SmaReadPlan {

    /** A channel served by a block, with the register selected for it. */
    public record Entry(SmaChannel channel, Register register) {
    }

    /** One read request. */
    public record Block(boolean holding, PollClass pollClass, int start, int length, List<Entry> entries) {

        /** First register after the block. */
        public int end() {
            return start + length;
        }

        /** Byte offset of the entry's register within the block's data. */
        public int byteOffset(Entry entry) {
            return (entry.register().address() - start) * 2;
        }

        @Override
        public String toString() {
            return (holding ? "holding " : "input ") + pollClass + " " + start + "-" + (end() - 1) + " (" + length
                    + ")";
        }
    }

    private SmaReadPlan() {
        // static helpers only
    }

    /**
     * Builds the read blocks for the selected channels.
     *
     * @param selected channel → register chosen by the channel detection
     * @return the blocks, fast before slow, input before holding, each ordered by address
     */
    public static List<Block> build(Map<SmaChannel, Register> selected) {
        List<Entry> entries = selected.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(e -> e.register().address())).toList();
        List<Block> result = new ArrayList<>();
        List<Block> slowBlocks = new ArrayList<>();
        for (boolean holding : new boolean[] { false, true }) {
            List<Entry> fast = new ArrayList<>();
            List<Entry> slow = new ArrayList<>();
            for (Entry e : entries) {
                if (e.register().isHolding() == holding) {
                    (e.channel().getPollClass() == PollClass.FAST ? fast : slow).add(e);
                }
            }
            List<Builder> fastBuilders = merge(fast);
            List<Entry> slowRest = new ArrayList<>();
            for (Entry e : slow) {
                @Nullable
                Builder covering = fastBuilders.stream().filter(b -> b.covers(e.register())).findFirst().orElse(null);
                if (covering != null) {
                    covering.entries.add(e);
                } else {
                    slowRest.add(e);
                }
            }
            fastBuilders.forEach(b -> result.add(b.build(holding, PollClass.FAST)));
            merge(slowRest).forEach(b -> slowBlocks.add(b.build(holding, PollClass.SLOW)));
        }
        result.addAll(slowBlocks);
        return result;
    }

    private static List<Builder> merge(List<Entry> sorted) {
        List<Builder> builders = new ArrayList<>();
        @Nullable
        Builder current = null;
        for (Entry e : sorted) {
            int address = e.register().address();
            int end = address + e.register().count();
            if (current != null && end - current.start <= MAX_BLOCK_LENGTH
                    && (address - current.end <= MAX_BLOCK_GAP || (e.channel().isBalance() && current.hasBalance))) {
                current.add(e);
            } else {
                current = new Builder(e);
                builders.add(current);
            }
        }
        return builders;
    }

    private static final class Builder {
        final int start;
        int end;
        boolean hasBalance;
        final List<Entry> entries = new ArrayList<>();

        Builder(Entry first) {
            start = first.register().address();
            end = start;
            add(first);
        }

        void add(Entry e) {
            entries.add(e);
            end = Math.max(end, e.register().address() + e.register().count());
            hasBalance |= e.channel().isBalance();
        }

        boolean covers(Register register) {
            return register.address() >= start && register.address() + register.count() <= end;
        }

        Block build(boolean holding, PollClass pollClass) {
            List<Entry> sorted = entries.stream().sorted(Comparator.comparingInt(e -> e.register().address())).toList();
            return new Block(holding, pollClass, start, end - start, sorted);
        }
    }
}
