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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.modbus.ModbusBindingConstants;
import org.openhab.binding.modbus.handler.BaseModbusThingHandler;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Format;
import org.openhab.binding.modbus.sma.internal.SmaChannel.PollClass;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Register;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.Decision;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.ProbeResult;
import org.openhab.binding.modbus.sma.internal.SmaReadPlan.Block;
import org.openhab.binding.modbus.sma.internal.SmaReadPlan.Entry;
import org.openhab.binding.modbus.sma.internal.SmaThingTypes.ThingTypeInfo;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusConstants.ValueType;
import org.openhab.core.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.io.transport.modbus.exception.ModbusSlaveErrorResponseException;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelGroupUID;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelGroupTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SmaInverterHandler} reads an SMA inverter of the Speedwire platform via Modbus TCP (read only).
 * <p>
 * Initialization: read the nameplate, probe every candidate register on its own (one request after the other),
 * decide the channels ({@link SmaChannelDetector}), create them and register one regular poll per read block
 * ({@link SmaReadPlan}). Channels that are only valid while feeding in stay pending when the inverter is not in
 * operation; they are probed again and decided at the first poll in operation.
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
public class SmaInverterHandler extends BaseModbusThingHandler {

    private static final long NAMEPLATE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final Logger logger = LoggerFactory.getLogger(SmaInverterHandler.class);

    private SmaConfiguration config = new SmaConfiguration();
    private List<SmaChannel> declared = List.of();
    private Map<SmaChannel, Register> selected = Map.of();
    private Set<SmaChannel> pending = Set.of();
    private List<Block> blocks = List.of();
    private final List<PollTask> polls = new ArrayList<>();
    private final Set<Block> failedBlocks = new LinkedHashSet<>();
    /** Time of the last successful read per block, to skip REFRESH commands for current values. */
    private final Map<Block, Long> lastRead = new HashMap<>();
    private @Nullable ScheduledFuture<?> retryJob;

    /** Incremented on every (re)initialization and dispose; callbacks of an older generation are ignored. */
    private volatile int generation;
    private boolean detectingPending;
    private @Nullable BigDecimal lastGridRelay;
    private boolean lastActivePowerValid;

    public SmaInverterHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void modbusInitialize() {
        config = getConfigAs(SmaConfiguration.class);
        if (config.refresh < 1 || config.slowRefresh < 1 || config.maxTries < 1 || config.mppTrackers < 0) {
            updateStatus(ThingStatusDetail.CONFIGURATION_ERROR,
                    "refresh, slowRefresh and maxTries must be positive, mppTrackers must not be negative");
            return;
        }
        declared = SmaChannelDetector.declaredChannels(thing.getThingTypeUID().getId());
        updateStatus(ThingStatus.UNKNOWN);
        startDetection(++generation);
    }

    @Override
    public void dispose() {
        generation++;
        ScheduledFuture<?> job = retryJob;
        if (job != null) {
            job.cancel(true);
            retryJob = null;
        }
        synchronized (this) {
            polls.clear(); // unregistered by super.dispose()
            failedBlocks.clear();
            lastRead.clear();
            detectingPending = false;
        }
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (!(command instanceof RefreshType)) {
            return; // read only
        }
        int gen = generation;
        long now = System.currentTimeMillis();
        List<Block> stale = new ArrayList<>();
        synchronized (this) {
            // openHAB sends REFRESH for every linked channel at startup: read only blocks older than one interval
            for (Block block : blocks) {
                long age = now - lastRead.getOrDefault(block, 0L);
                if (age > TimeUnit.SECONDS.toMillis(config.refresh)
                        && block.entries().stream().anyMatch(e -> channelUid(e.channel()).equals(channelUID))) {
                    lastRead.put(block, now); // also suppresses further REFRESH until the answer arrives
                    stale.add(block);
                }
            }
        }
        for (Block block : stale) {
            try {
                submitOneTimePoll(request(block.start(), block.length(), block.holding()),
                        result -> onBlock(gen, block, result), failure -> onBlockError(gen, block, failure));
            } catch (IllegalStateException e) {
                logger.debug("Refresh of {} not possible: {}", channelUID, e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Detection

    private void startDetection(int gen) {
        Map<Integer, Register> candidates = candidates(declared);
        logger.debug("{}: reading nameplate and probing {} registers", thing.getUID(), candidates.size());
        try {
            submitOneTimePoll(request(REG_NAMEPLATE_START, REG_NAMEPLATE_COUNT, false), result -> {
                applyNameplate(result);
                new Probe(gen, candidates, probe -> finishDetection(gen, probe)).next();
            }, failure -> communicationFailed(gen, failure));
        } catch (IllegalStateException e) {
            scheduleRetry(gen, e.getMessage());
        }
    }

    private synchronized void finishDetection(int gen, Probe probe) {
        if (gen != generation) {
            return;
        }
        boolean inOperation = SmaChannelDetector.isInOperation(probe.value(30217), probe.isValid(30775));
        Decision decision = SmaChannelDetector.decide(declared, probe.results, inOperation, config.mppTrackers);
        selected = decision.selected();
        pending = decision.pending();
        logger.info("{}: {} channels active, {} waiting for the inverter to feed in, {} not supported: {}",
                thing.getUID(), decision.active().size(), pending.size(), decision.removed().size(),
                names(decision.removed()));
        applyChannels();
        startPolling(gen);
    }

    private void startPendingDetection(int gen) {
        detectingPending = true;
        Set<SmaChannel> toDecide = EnumSet.copyOf(pending);
        logger.debug("{}: inverter feeds in, deciding {} pending channels", thing.getUID(), toDecide.size());
        new Probe(gen, candidates(toDecide), probe -> finishPendingDetection(gen, toDecide, probe)).next();
    }

    private synchronized void finishPendingDetection(int gen, Set<SmaChannel> toDecide, Probe probe) {
        if (gen != generation) {
            return;
        }
        detectingPending = false;
        Decision decision = SmaChannelDetector.decide(toDecide, probe.results, true, config.mppTrackers);
        Map<SmaChannel, Register> newSelected = new EnumMap<>(SmaChannel.class);
        newSelected.putAll(selected);
        decision.removed().forEach(newSelected::remove);
        newSelected.putAll(decision.selected());
        selected = newSelected;
        pending = Set.of();
        logger.info("{}: pending channels decided, {} active, not supported: {}", thing.getUID(),
                decision.active().size(), names(decision.removed()));
        applyChannels();
        startPolling(gen);
    }

    /** Distinct candidate registers of the channels, by address. */
    private static Map<Integer, Register> candidates(Iterable<SmaChannel> channels) {
        Map<Integer, Register> result = new HashMap<>();
        for (SmaChannel channel : channels) {
            channel.getCandidates().forEach(r -> result.putIfAbsent(r.address(), r));
        }
        return result;
    }

    /** Reads registers one after the other, each on its own, and records the kind of result. */
    private class Probe {
        private final int gen;
        private final Deque<Register> todo;
        private final Consumer<Probe> onDone;
        final Map<Integer, ProbeResult> results = new HashMap<>();
        private final Map<Integer, Optional<BigDecimal>> values = new HashMap<>();

        Probe(int gen, Map<Integer, Register> registers, Consumer<Probe> onDone) {
            this.gen = gen;
            this.todo = new ArrayDeque<>(registers.values());
            this.onDone = onDone;
        }

        Optional<BigDecimal> value(int address) {
            return values.getOrDefault(address, Optional.empty());
        }

        boolean isValid(int address) {
            return results.get(address) == ProbeResult.VALUE;
        }

        void next() {
            if (gen != generation) {
                return;
            }
            if (todo.isEmpty()) {
                onDone.accept(this);
                return;
            }
            Register register = todo.removeFirst();
            try {
                submitOneTimePoll(request(register.address(), register.count(), register.isHolding()), result -> {
                    Optional<ModbusRegisterArray> data = result.getRegisters();
                    Optional<BigDecimal> value = data.isPresent()
                            ? SmaValueDecoder.decode(data.get().getBytes(), 0, register)
                            : Optional.empty();
                    values.put(register.address(), value);
                    results.put(register.address(), value.isPresent() ? ProbeResult.VALUE : ProbeResult.NAN);
                    next();
                }, failure -> {
                    if (failure.getCause() instanceof ModbusSlaveErrorResponseException) {
                        results.put(register.address(), ProbeResult.EXCEPTION);
                        next();
                    } else {
                        probeFailed(gen, failure);
                    }
                });
            } catch (IllegalStateException e) {
                scheduleRetry(gen, e.getMessage());
            }
        }
    }

    private synchronized void probeFailed(int gen, AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        if (detectingPending) {
            // keep polling, try again at the next poll in operation
            detectingPending = false;
            logger.debug("{}: deciding pending channels failed: {}", thing.getUID(), failure.getCause().getMessage());
        } else {
            communicationFailed(gen, failure);
        }
    }

    private void communicationFailed(int gen, AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        scheduleRetry(gen, failure.getCause().getMessage());
    }

    private void scheduleRetry(int gen, @Nullable String reason) {
        if (gen != generation) {
            return;
        }
        updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, reason);
        retryJob = scheduler.schedule(() -> {
            if (gen == generation) {
                startDetection(gen);
            }
        }, config.slowRefresh, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Channels

    /** Recreates the thing's channels from the thing type, keeping the selected ones. */
    private void applyChannels() {
        ThingHandlerCallback callback = getCallback();
        ThingTypeInfo info = SmaThingTypes.THING_TYPES.get(thing.getThingTypeUID().getId());
        if (callback == null || info == null) {
            return;
        }
        Set<ChannelUID> keep = selected.keySet().stream().map(this::channelUid).collect(Collectors.toSet());
        List<Channel> channels = new ArrayList<>();
        for (String group : info.groups()) {
            ChannelGroupUID groupUID = new ChannelGroupUID(thing.getUID(), group);
            ChannelGroupTypeUID groupTypeUID = new ChannelGroupTypeUID(ModbusBindingConstants.BINDING_ID,
                    group.startsWith("dc-") ? "sma-dc" : "sma-" + group);
            for (ChannelBuilder builder : callback.createChannelBuilders(groupUID, groupTypeUID)) {
                Channel channel = builder.build();
                if (keep.contains(channel.getUID())) {
                    channels.add(channel);
                }
            }
        }
        Set<ChannelUID> existing = thing.getChannels().stream().map(Channel::getUID).collect(Collectors.toSet());
        Set<ChannelUID> wanted = channels.stream().map(Channel::getUID).collect(Collectors.toSet());
        if (!existing.equals(wanted)) {
            updateThing(editThing().withoutChannels(thing.getChannels()).withChannels(channels).build());
        }
    }

    private ChannelUID channelUid(SmaChannel channel) {
        return new ChannelUID(thing.getUID(), channel.getGroupId(), channel.getChannelId());
    }

    private static String names(Set<SmaChannel> channels) {
        return channels.stream().map(c -> c.getGroupId() + "#" + c.getChannelId()).collect(Collectors.joining(", "));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Polling

    private synchronized void startPolling(int gen) {
        for (PollTask task : polls) {
            try {
                unregisterRegularPoll(task);
            } catch (IllegalStateException e) {
                logger.debug("Unregistering poll failed: {}", e.getMessage());
            }
        }
        polls.clear();
        failedBlocks.clear();
        lastRead.clear();
        blocks = SmaReadPlan.build(selected);
        logger.debug("{}: read blocks {}", thing.getUID(), blocks);
        try {
            for (Block block : blocks) {
                long period = block.pollClass() == PollClass.FAST ? config.refresh
                        : Math.max(config.slowRefresh, config.refresh);
                polls.add(registerRegularPoll(request(block.start(), block.length(), block.holding()),
                        TimeUnit.SECONDS.toMillis(period), 0, result -> onBlock(gen, block, result),
                        failure -> onBlockError(gen, block, failure)));
            }
            polls.add(registerRegularPoll(request(REG_NAMEPLATE_START, REG_NAMEPLATE_COUNT, false),
                    NAMEPLATE_INTERVAL_MILLIS, NAMEPLATE_INTERVAL_MILLIS, this::applyNameplate,
                    failure -> logger.debug("Reading nameplate failed: {}", failure.getCause().getMessage())));
        } catch (IllegalStateException e) {
            scheduleRetry(gen, e.getMessage());
        }
    }

    private synchronized void onBlock(int gen, Block block, AsyncModbusReadResult result) {
        if (gen != generation) {
            return;
        }
        Optional<ModbusRegisterArray> data = result.getRegisters();
        if (data.isEmpty()) {
            return;
        }
        lastRead.put(block, System.currentTimeMillis());
        if (failedBlocks.remove(block)) {
            logger.info("{}: reading {} works again", thing.getUID(), block);
        }
        if (thing.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
        byte[] bytes = data.get().getBytes();
        for (Entry entry : block.entries()) {
            Optional<BigDecimal> value = SmaValueDecoder.decode(bytes, block.byteOffset(entry), entry.register());
            if (entry.channel() == SmaChannel.STATUS_GRID_RELAY) {
                lastGridRelay = value.orElse(null);
            } else if (entry.channel() == SmaChannel.AC_ACTIVE_POWER) {
                lastActivePowerValid = value.isPresent();
            }
            State state = SmaValueDecoder.toState(entry.channel(), value);
            if (state != null) {
                updateState(channelUid(entry.channel()), state);
            }
        }
        if (!pending.isEmpty() && !detectingPending && inOperation()) {
            startPendingDetection(gen);
        }
    }

    /** Operation condition; without a grid relay channel the active power alone decides. */
    private boolean inOperation() {
        return selected.containsKey(SmaChannel.STATUS_GRID_RELAY)
                ? SmaChannelDetector.isInOperation(Optional.ofNullable(lastGridRelay), lastActivePowerValid)
                : lastActivePowerValid;
    }

    private synchronized void onBlockError(int gen, Block block,
            AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        if (gen != generation) {
            return;
        }
        Exception cause = failure.getCause();
        if (cause instanceof ModbusSlaveErrorResponseException) {
            // the device rejects the block, e.g. after a firmware update: its channels get UNDEF
            if (failedBlocks.add(block)) {
                logger.warn("{}: the inverter rejects reading {}: {}. Reinitialize the thing to detect the channels "
                        + "again.", thing.getUID(), block, cause.getMessage());
            }
            for (Entry entry : block.entries()) {
                if (entry.channel().getNanPolicy() != SmaChannel.NanPolicy.HOLD) {
                    updateState(channelUid(entry.channel()), UnDefType.UNDEF);
                }
            }
        } else {
            logger.debug("{}: reading {} failed: {}", thing.getUID(), block, cause.getMessage());
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, cause.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Nameplate

    private void applyNameplate(AsyncModbusReadResult result) {
        Optional<ModbusRegisterArray> data = result.getRegisters();
        if (data.isEmpty()) {
            return;
        }
        byte[] bytes = data.get().getBytes();
        Map<String, String> properties = editProperties();
        nameplate(bytes, REG_DEVICE_CLASS, Format.TAGLIST).ifPresent(code -> {
            properties.put(PROPERTY_DEVICE_CLASS,
                    SmaDeviceTypes.DEVICE_CLASSES.getOrDefault(code.intValue(), "unknown (" + code + ")"));
            checkDeviceClass(code.intValue());
        });
        nameplate(bytes, REG_DEVICE_TYPE, Format.TAGLIST).ifPresent(code -> properties.put(Thing.PROPERTY_MODEL_ID,
                SmaDeviceTypes.MODELS.getOrDefault(code.intValue(), "unknown (" + code + ")")));
        nameplate(bytes, REG_VENDOR, Format.TAGLIST).ifPresent(code -> properties.put(Thing.PROPERTY_VENDOR,
                SmaDeviceTypes.VENDORS.getOrDefault(code.intValue(), "unknown (" + code + ")")));
        nameplate(bytes, REG_SERIAL_NUMBER, Format.RAW)
                .ifPresent(serial -> properties.put(Thing.PROPERTY_SERIAL_NUMBER, serial.toString()));
        nameplate(bytes, REG_FIRMWARE, Format.FW).ifPresent(
                fw -> properties.put(Thing.PROPERTY_FIRMWARE_VERSION, SmaValueDecoder.firmware(fw.longValue())));
        updateProperties(properties);
    }

    private static Optional<BigInteger> nameplate(byte[] bytes, int address, Format format) {
        Register register = new Register(address, ValueType.UINT32, format);
        return SmaValueDecoder.raw(bytes, (address - REG_NAMEPLATE_START) * 2, register)
                .filter(raw -> !SmaValueDecoder.isNaN(raw, register));
    }

    private void checkDeviceClass(int deviceClass) {
        ThingTypeInfo info = SmaThingTypes.THING_TYPES.get(thing.getThingTypeUID().getId());
        if (info != null && info.deviceClass() != deviceClass) {
            String fitting = SmaThingTypes.THING_TYPES.entrySet().stream()
                    .filter(e -> e.getValue().deviceClass() == deviceClass).map(Map.Entry::getKey).findFirst()
                    .orElse("none");
            logger.warn("{}: the device reports device class {} but the thing type expects {}. Fitting thing type: {}",
                    thing.getUID(), deviceClass, info.deviceClass(), fitting);
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Read request; SMA register numbers are sent as they are (no offset). */
    private ModbusReadRequestBlueprint request(int address, int length, boolean holding) {
        return new ModbusReadRequestBlueprint(getSlaveId(),
                holding ? ModbusReadFunctionCode.READ_MULTIPLE_REGISTERS : ModbusReadFunctionCode.READ_INPUT_REGISTERS,
                address, length, config.maxTries);
    }
}
