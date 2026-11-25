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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.modbus.handler.ModbusEndpointThingHandler;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Format;
import org.openhab.binding.modbus.sma.internal.SmaChannel.Register;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.ProbeResult;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.modbus.AsyncModbusFailure;
import org.openhab.core.io.transport.modbus.AsyncModbusReadResult;
import org.openhab.core.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.core.io.transport.modbus.ModbusFailureCallback;
import org.openhab.core.io.transport.modbus.ModbusReadCallback;
import org.openhab.core.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.core.io.transport.modbus.ModbusRegisterArray;
import org.openhab.core.io.transport.modbus.ModbusWriteCallback;
import org.openhab.core.io.transport.modbus.ModbusWriteRequestBlueprint;
import org.openhab.core.io.transport.modbus.PollTask;
import org.openhab.core.io.transport.modbus.endpoint.ModbusSlaveEndpoint;
import org.openhab.core.io.transport.modbus.endpoint.ModbusTCPSlaveEndpoint;
import org.openhab.core.io.transport.modbus.exception.ModbusSlaveErrorResponseException;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelGroupUID;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;

/**
 * Tests for {@link SmaInverterHandler} against a simulated device: the register contents are derived from the
 * probe results of two real devices ({@link SmaProbeFixtures}); measured values and the serial number are made up.
 * Modbus callbacks run synchronously, regular polls are triggered by the test.
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
@SuppressWarnings("null") // Mockito and parts of the openHAB core API are not designed with null annotations in mind
class SmaInverterHandlerTest {

    private static final ThingUID BRIDGE_UID = new ThingUID("modbus", "tcp", "sma");

    private final List<SmaInverterHandler> handlers = new ArrayList<>();

    @AfterEach
    void disposeHandlers() {
        handlers.forEach(SmaInverterHandler::dispose);
    }

    @Test
    void hybridInverterInOperation() {
        FakeDevice device = new FakeDevice(SmaProbeFixtures.STP_SE);
        Setup s = setup("sma-hybrid-inverter", device);

        assertEquals(59, s.thing().getChannels().size(), "channels after detection");
        assertTrue(hasChannel(s, "dc-b", "power"));
        assertFalse(hasChannel(s, "dc-c", "power"), "DC input C does not exist");
        assertEquals("STP10.0-3SE-40", s.thing().getProperties().get(Thing.PROPERTY_MODEL_ID));
        assertEquals("4.8.39.R", s.thing().getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION));
        assertEquals("Hybrid inverter", s.thing().getProperties().get(SmaBindingConstants.PROPERTY_DEVICE_CLASS));

        device.tickAll();

        verify(s.callback(), atLeastOnce()).statusUpdated(eq(s.thing()),
                argThat(info -> info.getStatus() == ThingStatus.ONLINE));
        assertEquals(new QuantityType<>(new BigDecimal(103), Units.WATT), s.state("ac", "active-power"));
        assertEquals(new QuantityType<>(new BigDecimal(53), Units.PERCENT), s.state("battery", "state-of-charge"));
    }

    @Test
    void readsUseSmaAddressesAndFunctionCodes() {
        FakeDevice device = new FakeDevice(SmaProbeFixtures.STP_SE);
        setup("sma-hybrid-inverter", device);

        Set<String> requests = device.regularPolls.stream().map(p -> p.request().getFunctionCode() + " "
                + p.request().getReference() + "+" + p.request().getDataLength()).collect(Collectors.toSet());
        assertTrue(requests.contains("READ_INPUT_REGISTERS 30769+100"), requests.toString());
        assertTrue(requests.contains("READ_MULTIPLE_REGISTERS 40029+2"), requests.toString());
        assertTrue(device.regularPolls.stream().allMatch(p -> p.request().getUnitID() == 3));
    }

    @Test
    void notInOperationKeepsChannelsPendingUntilFeedingIn() {
        FakeDevice device = new FakeDevice(SmaProbeFixtures.STP_3AV);
        device.set(30217, 311); // grid relay open
        Setup s = setup("sma-inverter", device);

        assertEquals(54, s.thing().getChannels().size(), "42 active + 12 pending (DC C–F)");
        assertTrue(hasChannel(s, "dc-c", "voltage"));
        assertFalse(hasChannel(s, "meter", "grid-draw-power"), "permanent channels are decided at once");

        device.tickAll();
        assertEquals(54, s.thing().getChannels().size(), "still not in operation");

        device.set(30217, 51); // grid relay closed
        device.tickAll();

        assertEquals(42, s.thing().getChannels().size(), "pending channels decided");
        assertFalse(hasChannel(s, "dc-c", "voltage"));
        assertTrue(hasChannel(s, "dc-b", "voltage"));
    }

    @Test
    void unreachableDeviceGoesOffline() {
        FakeDevice device = new FakeDevice(SmaProbeFixtures.STP_SE);
        device.reachable = false;
        Setup s = setup("sma-hybrid-inverter", device);

        verify(s.callback()).statusUpdated(eq(s.thing()),
                argThat(info -> info.getStatus() == ThingStatus.OFFLINE
                        && info.getStatusDetail() == ThingStatusDetail.COMMUNICATION_ERROR
                        && "@text/offline.communication-error [\"Connection refused\"]".equals(info.getDescription())));
        assertTrue(device.regularPolls.isEmpty(), "no polling before the detection succeeded");
    }

    @Test
    void rejectedRegisterIsTreatedLikeNan() {
        Map<Integer, ProbeResult> probes = new HashMap<>(SmaProbeFixtures.STP_SE);
        FakeDevice device = new FakeDevice(probes);
        device.rejected.add(31497); // reactive power, new address: Modbus exception → old address 30805 is NaN too
        Setup s = setup("sma-hybrid-inverter", device);

        assertFalse(hasChannel(s, "ac", "reactive-power"));
        assertEquals(58, s.thing().getChannels().size());
    }

    @Test
    void refreshDoesNotReadCurrentBlocksAgain() {
        FakeDevice device = new FakeDevice(SmaProbeFixtures.STP_SE);
        Setup s = setup("sma-hybrid-inverter", device);
        device.tickAll();
        int before = device.oneTimePolls;

        s.handler().handleCommand(new ChannelUID(s.thing().getUID(), "ac", "active-power"), RefreshType.REFRESH);

        assertEquals(before, device.oneTimePolls);
    }

    @Test
    void invalidConfigurationIsRejected() {
        FakeDevice device = new FakeDevice(SmaProbeFixtures.STP_SE);
        Setup s = setup("sma-hybrid-inverter", device, Map.of("refresh", 0));

        verify(s.callback()).statusUpdated(eq(s.thing()),
                argThat(info -> info.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR));
        assertEquals(0, device.oneTimePolls);
    }

    // ---------------------------------------------------------------------------------------------------------

    private record Setup(SmaInverterHandler handler, ThingHandlerCallback callback, Map<ChannelUID, State> states,
            Thing[] current) {

        Thing thing() {
            return current[0];
        }

        @Nullable
        State state(String group, String channel) {
            return states.get(new ChannelUID(thing().getUID(), group, channel));
        }
    }

    private static boolean hasChannel(Setup s, String group, String channel) {
        ChannelUID uid = new ChannelUID(s.thing().getUID(), group, channel);
        return s.thing().getChannels().stream().anyMatch(c -> c.getUID().equals(uid));
    }

    private Setup setup(String thingType, FakeDevice device) {
        return setup(thingType, device, Map.of());
    }

    private Setup setup(String thingType, FakeDevice device, Map<String, Object> config) {
        ThingTypeUID typeUID = new ThingTypeUID("modbus", thingType);
        ThingUID thingUID = new ThingUID(typeUID, BRIDGE_UID, "inverter");
        List<Channel> channels = SmaChannelDetector.declaredChannels(thingType).stream()
                .map(c -> ChannelBuilder.create(new ChannelUID(thingUID, c.getGroupId(), c.getChannelId())).build())
                .toList();
        Map<String, Object> configuration = new HashMap<>(Map.of("refresh", 15, "slowRefresh", 60, "maxTries", 1));
        configuration.putAll(config);
        Thing thing = ThingBuilder.create(typeUID, thingUID).withBridge(BRIDGE_UID).withChannels(channels)
                .withConfiguration(new Configuration(configuration)).build();

        ModbusEndpointThingHandler endpoint = mock(ModbusEndpointThingHandler.class,
                withSettings().extraInterfaces(BridgeHandler.class));
        when(endpoint.getCommunicationInterface()).thenReturn(device);
        try {
            when(endpoint.getSlaveId()).thenReturn(3);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Bridge bridge = mock(Bridge.class);
        when(bridge.getUID()).thenReturn(BRIDGE_UID);
        when(bridge.getHandler()).thenReturn((BridgeHandler) endpoint);

        Thing[] current = { thing };
        Map<ChannelUID, State> states = new HashMap<>();
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        when(callback.getBridge(BRIDGE_UID)).thenReturn(bridge);
        when(callback.createChannelBuilders(any(), any())).thenAnswer(inv -> {
            ChannelGroupUID group = inv.getArgument(0);
            return Arrays.stream(SmaChannel.values()).filter(c -> c.getGroupId().equals(group.getId()))
                    .map(c -> ChannelBuilder.create(new ChannelUID(group, c.getChannelId()))).toList();
        });
        doAnswer(inv -> {
            current[0] = inv.getArgument(0);
            return null;
        }).when(callback).thingUpdated(any());
        doAnswer(inv -> {
            current[0].setStatusInfo(inv.getArgument(1, ThingStatusInfo.class));
            return null;
        }).when(callback).statusUpdated(any(), any());
        doAnswer(inv -> {
            states.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(callback).stateUpdated(any(), any());

        SmaInverterHandler handler = new SmaInverterHandler(thing);
        handler.setCallback(callback);
        handlers.add(handler);
        handler.initialize();
        return new Setup(handler, callback, states, current);
    }

    /** Device whose registers follow the probe results; callbacks run synchronously. */
    private static class FakeDevice implements ModbusCommunicationInterface {

        private final Map<Integer, Integer> words = new HashMap<>();
        final Set<Integer> rejected = new HashSet<>();
        final List<FakePoll> regularPolls = new ArrayList<>();
        boolean reachable = true;
        int oneTimePolls;

        FakeDevice(Map<Integer, ProbeResult> probes) {
            Map<Integer, Register> registers = new HashMap<>();
            for (SmaChannel channel : SmaChannel.values()) {
                channel.getCandidates().forEach(r -> registers.put(r.address(), r));
            }
            registers.forEach((address, register) -> {
                ProbeResult result = probes.getOrDefault(address, ProbeResult.NAN);
                long value = register.format() == Format.TAGLIST ? 307 : 1;
                if (result == ProbeResult.NAN) {
                    setRaw(register, nan(register));
                } else {
                    setRaw(register, java.math.BigInteger.valueOf(value));
                }
                if (result == ProbeResult.EXCEPTION) {
                    rejected.add(address);
                }
            });
            // nameplate and a few measured values (made up, plausible)
            set(30051, 8009);
            set(30053, 19051);
            set(30055, 461);
            set(30057, 0x12345678);
            set(30059, 0x04082704);
            if (probes.get(30217) == ProbeResult.VALUE) {
                set(30217, 51);
            }
            if (probes.get(30775) == ProbeResult.VALUE) {
                set(30775, 103);
            }
            if (probes.get(30845) == ProbeResult.VALUE) {
                set(30845, 53);
            }
        }

        private static java.math.BigInteger nan(Register register) {
            int bits = register.type().getBits();
            if (register.type().name().startsWith("INT")) {
                return java.math.BigInteger.ONE.shiftLeft(bits - 1);
            }
            if (register.format() == Format.TAGLIST) {
                return java.math.BigInteger.valueOf(SmaValueDecoder.NAN_STATUS);
            }
            return java.math.BigInteger.ONE.shiftLeft(bits).subtract(java.math.BigInteger.ONE);
        }

        private void setRaw(Register register, java.math.BigInteger raw) {
            for (int i = 0; i < register.count(); i++) {
                int shift = 16 * (register.count() - 1 - i);
                words.put(register.address() + i, raw.shiftRight(shift).intValue() & 0xFFFF);
            }
        }

        /** Sets a 32-bit register (two words). */
        void set(int address, long value) {
            words.put(address, (int) (value >> 16) & 0xFFFF);
            words.put(address + 1, (int) value & 0xFFFF);
        }

        private void answer(ModbusReadRequestBlueprint request, ModbusReadCallback result,
                ModbusFailureCallback<ModbusReadRequestBlueprint> failure) {
            if (!reachable) {
                failure.handle(new AsyncModbusFailure<>(request, new Exception("Connection refused")));
                return;
            }
            int start = request.getReference();
            int length = request.getDataLength();
            for (int a = start; a < start + length; a++) {
                if (rejected.contains(a)) {
                    failure.handle(new AsyncModbusFailure<>(request, new IllegalAddress()));
                    return;
                }
            }
            int[] values = new int[length];
            for (int i = 0; i < length; i++) {
                values[i] = words.getOrDefault(start + i, 0xFFFF);
            }
            result.handle(new AsyncModbusReadResult(request, new ModbusRegisterArray(values)));
        }

        /** Runs every registered regular poll once (on a copy: a poll may re-register the polls). */
        void tickAll() {
            for (FakePoll poll : new ArrayList<>(regularPolls)) {
                if (regularPolls.contains(poll)) {
                    answer(poll.request(), poll.resultCallback(), poll.failureCallback());
                }
            }
        }

        @Override
        public ModbusSlaveEndpoint getEndpoint() {
            return new ModbusTCPSlaveEndpoint("192.0.2.1", 502, false);
        }

        @Override
        public Future<?> submitOneTimePoll(ModbusReadRequestBlueprint request, ModbusReadCallback resultCallback,
                ModbusFailureCallback<ModbusReadRequestBlueprint> failureCallback) {
            oneTimePolls++;
            answer(request, resultCallback, failureCallback);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public PollTask registerRegularPoll(ModbusReadRequestBlueprint request, long pollPeriodMillis,
                long initialDelayMillis, ModbusReadCallback resultCallback,
                ModbusFailureCallback<ModbusReadRequestBlueprint> failureCallback) {
            FakePoll poll = new FakePoll(request, resultCallback, failureCallback, getEndpoint());
            regularPolls.add(poll);
            return poll;
        }

        @Override
        public boolean unregisterRegularPoll(PollTask task) {
            return regularPolls.remove(task);
        }

        @Override
        public Future<?> submitOneTimeWrite(ModbusWriteRequestBlueprint request, ModbusWriteCallback resultCallback,
                ModbusFailureCallback<ModbusWriteRequestBlueprint> failureCallback) {
            throw new UnsupportedOperationException("the binding is read only");
        }

        @Override
        public void close() {
        }
    }

    private record FakePoll(ModbusReadRequestBlueprint request, ModbusReadCallback resultCallback,
            ModbusFailureCallback<ModbusReadRequestBlueprint> failureCallback,
            ModbusSlaveEndpoint endpoint) implements PollTask {

        @Override
        public ModbusReadRequestBlueprint getRequest() {
            return request;
        }

        @Override
        public ModbusReadCallback getResultCallback() {
            return resultCallback;
        }

        @Override
        public ModbusFailureCallback<ModbusReadRequestBlueprint> getFailureCallback() {
            return failureCallback;
        }

        @Override
        public ModbusSlaveEndpoint getEndpoint() {
            return endpoint;
        }
    }

    /** Modbus exception 2, illegal data address. */
    private static class IllegalAddress extends ModbusSlaveErrorResponseException {
        private static final long serialVersionUID = 1L;

        @Override
        public int getExceptionCode() {
            return ILLEGAL_DATA_ACCESS;
        }

        @Override
        public @Nullable String getMessage() {
            return "Illegal data address";
        }
    }
}
