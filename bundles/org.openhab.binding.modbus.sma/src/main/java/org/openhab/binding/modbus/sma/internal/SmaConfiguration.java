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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link SmaConfiguration} class contains the thing configuration parameters (config.xml).
 *
 * @author Christian Schreiner - Initial contribution
 */
@NonNullByDefault
public class SmaConfiguration {

    /** Poll interval in seconds for fast values (power, current, voltage, status). */
    public int refresh = 15;
    /** Poll interval in seconds for slow values (counters, temperatures, diagnostics). */
    public int slowRefresh = 60;
    /** Maximum tries per read request. */
    public int maxTries = 3;
    /** Number of DC inputs (MPP trackers); 0 = automatic detection. */
    public int mppTrackers = 0;
}
