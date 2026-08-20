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
 * Gson DTO for the {@code report.details} block in a {@code retrieve_reply}.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault({})
public class ReportDetailsDTO {
    /** Burner modulation level (%). */
    public int rel_mod_level;
    /** Boiler flow temperature (°C). */
    public double boiler_temp;
    /** Boiler return temperature (°C). */
    public double ret_temp;
    /** Central heating water pressure (bar). */
    public double ch_water_pres;
    /** DHW flow rate (L/min). */
    public double dhw_flow_rate;
    /** Minimum modulation level (%). */
    public int min_mod_level;
    /** Maximum boiler temperature (°C). */
    public double max_boiler_temp;
    /** Supply voltage (V). */
    public int voltage;
    /** Supply current (A). */
    public int current;
    /** Power consumption (kW). */
    public double power_kw;
    /** Controller reset count. */
    public int resets;
    /** Memory allocation indicator. */
    public int memory_allocation;
}
