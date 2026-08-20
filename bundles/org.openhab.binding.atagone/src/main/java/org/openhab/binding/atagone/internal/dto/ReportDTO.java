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
 * Gson DTO for the {@code report} block in a {@code retrieve_reply}.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault({})
public class ReportDTO {
    /** Current room temperature (°C). */
    public double room_temp;
    /** Outside temperature (°C) — may be negative; signed arithmetic required. */
    public double outside_temp;
    /** PCB (circuit board) temperature (°C). */
    public double pcb_temp;
    /** Active CH setpoint sent to boiler (°C). */
    public double ch_setpoint;
    /** CH circuit water temperature (°C). */
    public double ch_water_temp;
    /** CH circuit return temperature (°C). */
    public double ch_return_temp;
    /**
     * Boiler status bitmask.
     * Bit 8 (0x100) = flame active, bit 4 (0x08) = DHW active, bit 2 (0x04) = CH active.
     */
    public int boiler_status;
    /** Estimated seconds until room reaches target temperature. */
    public int ch_time_to_temp;
    /** Temperature shown on the thermostat display (°C). */
    public double shown_set_temp;
    /** CH circuit water pressure (bar). */
    public double ch_water_pres;
    /** Total burner run hours. */
    public double burning_hours;
    /** Active device error codes (comma-separated string or empty). */
    public String device_errors = "";
    /** Active boiler error codes (comma-separated string or empty). */
    public String boiler_errors = "";
    /** Weather station temperature (°C). */
    public double weather_temp;
    /** Weather status description. */
    public String weather_status = "";
    /** Average outside temperature (°C). */
    public double tout_avg;
    /** DHW water temperature (°C). */
    public double dhw_water_temp;
    /** DHW temperature setpoint (°C). */
    public double dhw_temp_setp;
    /** Extended boiler diagnostics. */
    public ReportDetailsDTO details;
}
