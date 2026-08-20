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
package org.openhab.binding.atagone.internal.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Gson DTO for the {@code configuration} block in a {@code retrieve_reply}.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault({})
public class DeviceConfigDTO {
    /** Minimum CH setpoint (°C). */
    public int ch_min_set;
    /** Maximum CH setpoint (°C). */
    public int ch_max_set;
    /** Minimum DHW setpoint (°C). */
    public int dhw_min_set;
    /** Maximum DHW setpoint (°C). */
    public int dhw_max_set;
    /** CH temperature setpoint during vacation (°C). */
    public double ch_vacation_temp;
    /** DHW temperature setpoint during vacation (°C). */
    public double dhw_vacation_temp;
    /** Frost protection enabled (1=on). */
    public int frost_prot;
    /** Frost protection room temperature threshold (°C). */
    public double frost_prot_temp;
    /** Summer eco mode enabled (1=on). */
    public int summer_eco_mode;
    /** Summer eco mode activation temperature (°C). */
    public double summer_eco_temp;
    /** Legionella protection enabled (1=on). */
    public int legionella_prot;
    /** Vacation start, in ATAG epoch (seconds since 2000-01-01 UTC). */
    public long start_vacation;
    /** Regulation volume. */
    public int reg_vol;
}
