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

import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.ModbusBindingConstants;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link SmaBindingConstants} class defines common constants used across the SMA Modbus binding.
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
public final class SmaBindingConstants {

    public static final ThingTypeUID THING_TYPE_INVERTER = new ThingTypeUID(ModbusBindingConstants.BINDING_ID,
            "sma-inverter");
    public static final ThingTypeUID THING_TYPE_HYBRID_INVERTER = new ThingTypeUID(ModbusBindingConstants.BINDING_ID,
            "sma-hybrid-inverter");
    public static final ThingTypeUID THING_TYPE_BATTERY_INVERTER = new ThingTypeUID(ModbusBindingConstants.BINDING_ID,
            "sma-battery-inverter");

    /** All thing types handled by this bundle (must match {@link SmaThingTypes#THING_TYPES}). */
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = SmaThingTypes.THING_TYPES.keySet().stream()
            .map(id -> new ThingTypeUID(ModbusBindingConstants.BINDING_ID, id)).collect(Collectors.toUnmodifiableSet());

    // Thing properties (in addition to the standard vendor, modelId, serialNumber, firmwareVersion)
    public static final String PROPERTY_DEVICE_CLASS = "deviceClass";
    public static final String PROPERTY_NOMINAL_POWER = "nominalPower";

    // Nameplate registers (input registers, read as one block 30051–30060 plus 30231)
    public static final int REG_NAMEPLATE_START = 30051;
    public static final int REG_NAMEPLATE_COUNT = 10;
    public static final int REG_DEVICE_CLASS = 30051;
    public static final int REG_DEVICE_TYPE = 30053;
    public static final int REG_VENDOR = 30055;
    public static final int REG_SERIAL_NUMBER = 30057;
    public static final int REG_FIRMWARE = 30059;
    public static final int REG_NOMINAL_POWER = 30231;

    /** Grid relay code for "closed" (register 30217): the inverter is feeding in. */
    public static final int GRID_RELAY_CLOSED = 51;

    /** Maximum number of registers in one Modbus read request. */
    public static final int MAX_BLOCK_LENGTH = 125;
    /** Maximum number of unused registers between two channels of the same read block (DESIGN §7). */
    public static final int MAX_BLOCK_GAP = 30;

    private SmaBindingConstants() {
        // constants only
    }
}
