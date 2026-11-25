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

import static java.util.Map.entry;
import static org.openhab.binding.modbus.sma.internal.SmaChannelDetector.ProbeResult.*;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.modbus.sma.internal.SmaChannelDetector.ProbeResult;

/**
 * Probe results of two real devices, read on 2026-11-25 at 10:56 while both were feeding in: every candidate
 * register of the channel catalog read on its own. Only the kind of result is kept, no values.
 * <ul>
 * <li>{@link #STP_SE}: Sunny Tripower 10.0 SE (hybrid inverter with battery and energy meter)</li>
 * <li>{@link #STP_3AV}: Sunny Tripower 10.0-3AV-40 (PV inverter without battery, no energy meter data)</li>
 * </ul>
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
final class SmaProbeFixtures {

    static final Map<Integer, ProbeResult> STP_SE = Map.ofEntries(entry(30201, VALUE), entry(30211, VALUE),
            entry(30213, VALUE), entry(30217, VALUE), entry(30219, NAN), entry(30225, VALUE), entry(30247, VALUE),
            entry(30513, VALUE), entry(30517, VALUE), entry(30521, VALUE), entry(30525, NAN), entry(30529, VALUE),
            entry(30535, VALUE), entry(30559, VALUE), entry(30561, VALUE), entry(30563, VALUE), entry(30581, VALUE),
            entry(30583, VALUE), entry(30599, VALUE), entry(30769, VALUE), entry(30771, VALUE), entry(30773, VALUE),
            entry(30775, VALUE), entry(30777, VALUE), entry(30779, VALUE), entry(30781, VALUE), entry(30783, VALUE),
            entry(30785, VALUE), entry(30787, VALUE), entry(30789, VALUE), entry(30791, VALUE), entry(30793, VALUE),
            entry(30795, VALUE), entry(30803, VALUE), entry(30805, NAN), entry(30813, VALUE), entry(30843, VALUE),
            entry(30845, VALUE), entry(30847, VALUE), entry(30849, VALUE), entry(30851, VALUE), entry(30865, VALUE),
            entry(30867, VALUE), entry(30881, VALUE), entry(30949, VALUE), entry(30953, VALUE), entry(30955, VALUE),
            entry(30957, VALUE), entry(30959, VALUE), entry(30961, VALUE), entry(30963, NAN), entry(30965, NAN),
            entry(30967, NAN), entry(30969, NAN), entry(30971, NAN), entry(30973, NAN), entry(30977, VALUE),
            entry(30979, VALUE), entry(30981, VALUE), entry(31057, VALUE), entry(31209, NAN), entry(31211, NAN),
            entry(31213, NAN), entry(31215, NAN), entry(31217, NAN), entry(31219, NAN), entry(31247, VALUE),
            entry(31391, VALUE), entry(31393, VALUE), entry(31395, VALUE), entry(31397, VALUE), entry(31401, VALUE),
            entry(31497, VALUE), entry(33003, VALUE), entry(34113, VALUE), entry(40029, VALUE), entry(40187, VALUE));

    static final Map<Integer, ProbeResult> STP_3AV = Map.ofEntries(entry(30201, VALUE), entry(30211, VALUE),
            entry(30213, VALUE), entry(30217, VALUE), entry(30219, VALUE), entry(30225, VALUE), entry(30247, NAN),
            entry(30513, VALUE), entry(30517, VALUE), entry(30521, VALUE), entry(30525, VALUE), entry(30529, VALUE),
            entry(30535, VALUE), entry(30559, VALUE), entry(30561, VALUE), entry(30563, VALUE), entry(30581, NAN),
            entry(30583, NAN), entry(30599, VALUE), entry(30769, VALUE), entry(30771, VALUE), entry(30773, VALUE),
            entry(30775, VALUE), entry(30777, VALUE), entry(30779, VALUE), entry(30781, VALUE), entry(30783, VALUE),
            entry(30785, VALUE), entry(30787, VALUE), entry(30789, VALUE), entry(30791, VALUE), entry(30793, VALUE),
            entry(30795, VALUE), entry(30803, VALUE), entry(30805, VALUE), entry(30813, VALUE), entry(30843, NAN),
            entry(30845, NAN), entry(30847, NAN), entry(30849, NAN), entry(30851, NAN), entry(30865, NAN),
            entry(30867, NAN), entry(30881, VALUE), entry(30949, VALUE), entry(30953, VALUE), entry(30955, NAN),
            entry(30957, VALUE), entry(30959, VALUE), entry(30961, VALUE), entry(30963, NAN), entry(30965, NAN),
            entry(30967, NAN), entry(30969, NAN), entry(30971, NAN), entry(30973, NAN), entry(30977, VALUE),
            entry(30979, VALUE), entry(30981, VALUE), entry(31057, NAN), entry(31209, NAN), entry(31211, NAN),
            entry(31213, NAN), entry(31215, NAN), entry(31217, NAN), entry(31219, NAN), entry(31247, VALUE),
            entry(31391, NAN), entry(31393, NAN), entry(31395, NAN), entry(31397, NAN), entry(31401, NAN),
            entry(31497, NAN), entry(33003, NAN), entry(34113, VALUE), entry(40029, VALUE), entry(40187, NAN));

    private SmaProbeFixtures() {
    }
}
