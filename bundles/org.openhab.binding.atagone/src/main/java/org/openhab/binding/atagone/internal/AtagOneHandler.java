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
package org.openhab.binding.atagone.internal;

import static org.openhab.binding.atagone.internal.AtagOneBindingConstants.*;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.atagone.internal.api.AtagEpoch;
import org.openhab.binding.atagone.internal.api.AtagOneApiClient;
import org.openhab.binding.atagone.internal.api.AtagOneCommunicationException;
import org.openhab.binding.atagone.internal.dto.ControlUpdateDTO;
import org.openhab.binding.atagone.internal.dto.RetrieveReplyDTO;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the ATAG ONE thermostat Thing: pairing, polling, channel updates, and command dispatch.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault
public class AtagOneHandler extends BaseThingHandler {

    private static final int PAIRING_RETRY_S = 5;
    private static final int POST_COMMAND_DELAY_S = 2;

    private final Logger logger = LoggerFactory.getLogger(AtagOneHandler.class);
    private final HttpClient httpClient;

    private AtagOneConfiguration config = new AtagOneConfiguration();
    private @Nullable AtagOneApiClient apiClient;
    private @Nullable ScheduledFuture<?> pollJob;
    private @Nullable ScheduledFuture<?> pairingJob;
    private volatile boolean disposing = false;

    private final Map<String, State> stateMap = Collections.synchronizedMap(new HashMap<>());

    public AtagOneHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        disposing = false;
        config = getConfigAs(AtagOneConfiguration.class);
        if (config.hostname.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.hostname-missing");
            return;
        }
        stateMap.clear();
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::connect);
    }

    @Override
    public void dispose() {
        disposing = true;
        stopPollJob();
        ScheduledFuture<?> pairing = pairingJob;
        if (pairing != null) {
            pairing.cancel(true);
            pairingJob = null;
        }
    }

    // ── Command handling ──────────────────────────────────────────────────────

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (disposing) {
            return;
        }
        AtagOneApiClient client = apiClient;
        if (client == null) {
            return;
        }

        if (command instanceof RefreshType) {
            stateMap.clear();
            scheduler.execute(this::poll);
            return;
        }

        if (getThing().getStatus() != ThingStatus.ONLINE) {
            return;
        }

        ControlUpdateDTO control = new ControlUpdateDTO();
        if (!buildControlUpdate(channelUID.getId(), command, control)) {
            logger.debug("Unhandled command {} for channel {}", command, channelUID.getId());
            return;
        }

        stopPollJob();
        try {
            client.updateControl(control);
        } catch (AtagOneCommunicationException e) {
            logger.warn("Command failed for {}: {}", channelUID.getId(), e.getMessage());
        }
        startPollJob(POST_COMMAND_DELAY_S);
    }

    private boolean buildControlUpdate(String channelId, Command command, ControlUpdateDTO dto) {
        switch (channelId) {
            case CHANNEL_TARGET_TEMPERATURE:
                if (command instanceof QuantityType<?> qt) {
                    QuantityType<?> celsius = qt.toUnit(SIUnits.CELSIUS);
                    if (celsius == null) {
                        return false;
                    }
                    dto.ch_mode_temp = celsius.doubleValue();
                    return true;
                }
                return false;

            case CHANNEL_HVAC_MODE:
                if (command instanceof StringType s) {
                    dto.ch_control_mode = "auto".equalsIgnoreCase(s.toString()) ? CH_CONTROL_MODE_AUTO
                            : CH_CONTROL_MODE_HEAT;
                    return true;
                }
                return false;

            case CHANNEL_PRESET_MODE:
                if (command instanceof StringType s) {
                    Integer mode = CH_MODE_BY_NAME.get(s.toString().toLowerCase());
                    if (mode == null) {
                        return false;
                    }
                    dto.ch_mode = mode;
                    dto.ch_mode_duration = 0L;
                    return true;
                }
                return false;

            case CHANNEL_DHW_TARGET_TEMPERATURE:
                if (command instanceof QuantityType<?> qt) {
                    QuantityType<?> celsius = qt.toUnit(SIUnits.CELSIUS);
                    if (celsius == null) {
                        return false;
                    }
                    dto.dhw_temp_setp = celsius.doubleValue();
                    return true;
                }
                return false;

            case CHANNEL_EXTEND_DURATION:
                if (command instanceof QuantityType<?> qt) {
                    QuantityType<?> seconds = qt.toUnit(Units.SECOND);
                    if (seconds == null) {
                        return false;
                    }
                    dto.ch_mode = CH_MODE_EXTEND;
                    dto.extend_duration = seconds.longValue();
                    return true;
                }
                return false;

            case CHANNEL_FIREPLACE_DURATION:
                if (command instanceof QuantityType<?> qt) {
                    QuantityType<?> seconds = qt.toUnit(Units.SECOND);
                    if (seconds == null) {
                        return false;
                    }
                    dto.ch_mode = CH_MODE_FIREPLACE;
                    dto.fireplace_duration = seconds.longValue();
                    return true;
                }
                return false;

            default:
                return false;
        }
    }

    // ── Connection / pairing ──────────────────────────────────────────────────

    private void connect() {
        if (disposing) {
            return;
        }
        String clientId = resolveClientId();
        boolean needsPairing = clientId.isEmpty();
        if (needsPairing) {
            clientId = generateClientId();
            logger.info("Generated new client ID {}", clientId);
        }
        AtagOneApiClient client = new AtagOneApiClient(httpClient, config.hostname, config.port, clientId);
        apiClient = client;
        if (needsPairing) {
            doPair(client, clientId);
        } else {
            startPollJob(0);
        }
    }

    private void doPair(AtagOneApiClient client, String clientId) {
        if (disposing) {
            return;
        }
        try {
            int accStatus = client.pair();
            switch (accStatus) {
                case 2: // explicitly granted
                case 0: // open-LAN firmware — auto-accepted without user prompt
                    logger.info("ATAG ONE paired (acc_status={}), persisting clientId", accStatus);
                    persistClientId(clientId);
                    startPollJob(0);
                    break;
                case 1: // pending — user must press Accept on the thermostat display
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "@text/offline.conf-pending.press-accept");
                    if (!disposing) {
                        pairingJob = scheduler.schedule(() -> doPair(client, clientId), PAIRING_RETRY_S,
                                TimeUnit.SECONDS);
                    }
                    break;
                case 3: // denied — terminal, no retry
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "@text/offline.conf-error.pairing-denied");
                    break;
                default:
                    logger.warn("Unexpected acc_status={} during pairing", accStatus);
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Unexpected pairing response (acc_status=" + accStatus + ")");
            }
        } catch (AtagOneCommunicationException e) {
            logger.debug("Pairing error: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            if (!disposing) {
                pairingJob = scheduler.schedule(() -> doPair(client, clientId), PAIRING_RETRY_S, TimeUnit.SECONDS);
            }
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private void poll() {
        if (disposing) {
            return;
        }
        AtagOneApiClient client = apiClient;
        if (client == null) {
            return;
        }
        try {
            RetrieveReplyDTO r = client.retrieve();
            updateChannels(r);
            goOnline();
        } catch (AtagOneCommunicationException e) {
            logger.debug("Poll failed: {}", e.getMessage());
            goOffline(ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private synchronized void startPollJob(int initialDelaySeconds) {
        stopPollJob();
        if (disposing) {
            return;
        }
        pollJob = scheduler.scheduleWithFixedDelay(this::poll, initialDelaySeconds, config.refreshInterval,
                TimeUnit.SECONDS);
    }

    private synchronized void stopPollJob() {
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(false);
            pollJob = null;
        }
    }

    // ── Channel updates ───────────────────────────────────────────────────────

    private void updateChannels(RetrieveReplyDTO r) {
        // Report — temperatures
        updateIfChanged(CHANNEL_ROOM_TEMPERATURE, new QuantityType<>(r.report.room_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_OUTSIDE_TEMPERATURE, new QuantityType<>(r.report.outside_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_CH_WATER_TEMPERATURE, new QuantityType<>(r.report.ch_water_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_CH_RETURN_TEMPERATURE, new QuantityType<>(r.report.ch_return_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_CH_WATER_PRESSURE, new QuantityType<>(r.report.ch_water_pres, Units.BAR));
        updateIfChanged(CHANNEL_CH_SETPOINT, new QuantityType<>(r.report.ch_setpoint, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_DHW_TEMPERATURE, new QuantityType<>(r.report.dhw_water_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_SHOWN_SET_TEMPERATURE, new QuantityType<>(r.report.shown_set_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_AVERAGE_OUTSIDE_TEMPERATURE, new QuantityType<>(r.report.tout_avg, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_PCB_TEMPERATURE, new QuantityType<>(r.report.pcb_temp, SIUnits.CELSIUS));

        // Report — boiler state
        boolean flame = (r.report.boiler_status & BOILER_STATUS_FLAME) != 0;
        boolean chActive = (r.report.boiler_status & BOILER_STATUS_CH_ACTIVE) != 0;
        boolean dhwActive = (r.report.boiler_status & BOILER_STATUS_DHW_ACTIVE) != 0;
        updateIfChanged(CHANNEL_FLAME, OnOffType.from(flame));
        updateIfChanged(CHANNEL_BURNER_TARGET, new StringType(dhwActive ? "dhw" : chActive ? "ch" : "none"));
        updateIfChanged(CHANNEL_MODULATION_LEVEL, new QuantityType<>(r.report.details.rel_mod_level, Units.PERCENT));
        updateIfChanged(CHANNEL_BURNING_HOURS, new QuantityType<>(r.report.burning_hours, Units.HOUR));
        updateIfChanged(CHANNEL_TIME_TO_TARGET, new QuantityType<>(r.report.ch_time_to_temp, Units.SECOND));
        updateIfChanged(CHANNEL_DEVICE_ERRORS, new StringType(r.report.device_errors));
        updateIfChanged(CHANNEL_BOILER_ERRORS, new StringType(r.report.boiler_errors));

        // Report — advanced diagnostics
        updateIfChanged(CHANNEL_WIFI_SIGNAL, new DecimalType(-r.report.rssi));
        double voltage = r.report.voltage > 1000 ? r.report.voltage / 1000.0 : r.report.voltage;
        updateIfChanged(CHANNEL_VOLTAGE, new DecimalType(voltage));
        updateIfChanged(CHANNEL_CURRENT, new DecimalType(r.report.current));
        updateIfChanged(CHANNEL_POWER_CONSUMPTION, new DecimalType(r.report.power_cons));
        updateIfChanged(CHANNEL_DHW_FLOW_RATE, new DecimalType(r.report.dhw_flow_rate));
        updateIfChanged(CHANNEL_RESETS, new DecimalType(r.report.resets));
        updateIfChanged(CHANNEL_MEMORY_ALLOCATION, new DecimalType(r.report.memory_allocation));
        updateIfChanged(CHANNEL_BOILER_TEMPERATURE, new QuantityType<>(r.report.details.boiler_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_BOILER_RETURN_TEMPERATURE,
                new QuantityType<>(r.report.details.boiler_return_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_MODULATION_MIN, new QuantityType<>(r.report.details.min_mod_level, Units.PERCENT));
        updateIfChanged(CHANNEL_MAX_BOILER_TEMPERATURE,
                new QuantityType<>(r.report.details.max_boiler_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_REPORT_TIME, new DateTimeType(AtagEpoch.toZonedDateTime(r.report.report_time)));

        // Control — setpoints and modes
        updateIfChanged(CHANNEL_TARGET_TEMPERATURE, new QuantityType<>(r.control.ch_mode_temp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_HVAC_MODE,
                new StringType(CH_CONTROL_MODE_NAMES.getOrDefault(r.control.ch_control_mode, "heat")));
        updateIfChanged(CHANNEL_PRESET_MODE, new StringType(CH_MODE_NAMES.getOrDefault(r.control.ch_mode, "manual")));
        updateIfChanged(CHANNEL_PRESET_MODE_DURATION, new QuantityType<>(r.control.ch_mode_duration, Units.SECOND));
        updateIfChanged(CHANNEL_DHW_TARGET_TEMPERATURE, new QuantityType<>(r.control.dhw_temp_setp, SIUnits.CELSIUS));
        updateIfChanged(CHANNEL_DHW_MODE, new DecimalType(r.control.dhw_mode));
        updateIfChanged(CHANNEL_EXTEND_DURATION, new QuantityType<>(r.control.extend_duration, Units.SECOND));
        updateIfChanged(CHANNEL_FIREPLACE_DURATION, new QuantityType<>(r.control.fireplace_duration, Units.SECOND));
        updateIfChanged(CHANNEL_WEATHER_STATUS,
                new StringType(WEATHER_STATUS_NAMES.getOrDefault(r.control.weather_status, "unknown")));

        // Configuration — vacation temperature
        updateIfChanged(CHANNEL_VACATION_TEMPERATURE,
                new QuantityType<>(r.configuration.ch_vacation_temp, SIUnits.CELSIUS));

        // Vacation / extend / fireplace remaining duration
        int mode = r.control.ch_mode;
        if (mode == CH_MODE_HOLIDAY && r.control.vacation_duration > 0 && r.configuration.start_vacation > 0) {
            ZonedDateTime vacStart = AtagEpoch.toZonedDateTime(r.configuration.start_vacation);
            ZonedDateTime vacEnd = vacStart.plusSeconds(r.control.vacation_duration);
            updateIfChanged(CHANNEL_VACATION_START, new DateTimeType(vacStart));
            updateIfChanged(CHANNEL_VACATION_END, new DateTimeType(vacEnd));
            updateIfChanged(CHANNEL_VACATION_REMAINING, new QuantityType<>(r.control.vacation_duration, Units.SECOND));
            updateIfChanged(CHANNEL_EXTEND_REMAINING, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_FIREPLACE_REMAINING, UnDefType.UNDEF);
        } else if (mode == CH_MODE_EXTEND) {
            updateIfChanged(CHANNEL_VACATION_START, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_VACATION_END, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_VACATION_REMAINING, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_EXTEND_REMAINING, new QuantityType<>(r.control.ch_mode_duration, Units.SECOND));
            updateIfChanged(CHANNEL_FIREPLACE_REMAINING, UnDefType.UNDEF);
        } else if (mode == CH_MODE_FIREPLACE) {
            updateIfChanged(CHANNEL_VACATION_START, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_VACATION_END, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_VACATION_REMAINING, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_EXTEND_REMAINING, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_FIREPLACE_REMAINING, new QuantityType<>(r.control.ch_mode_duration, Units.SECOND));
        } else {
            updateIfChanged(CHANNEL_VACATION_START, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_VACATION_END, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_VACATION_REMAINING, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_EXTEND_REMAINING, UnDefType.UNDEF);
            updateIfChanged(CHANNEL_FIREPLACE_REMAINING, UnDefType.UNDEF);
        }
    }

    private void updateIfChanged(String channelId, State state) {
        State previous = stateMap.put(channelId, state);
        if (!state.equals(previous)) {
            updateState(channelId, state);
        }
    }

    // ── Status helpers ────────────────────────────────────────────────────────

    private void goOnline() {
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
    }

    private void goOffline(ThingStatusDetail detail, @Nullable String reason) {
        updateStatus(ThingStatus.OFFLINE, detail, reason);
    }

    // ── Client ID lifecycle ───────────────────────────────────────────────────

    private String resolveClientId() {
        if (!config.clientId.isBlank()) {
            return config.clientId;
        }
        String prop = getThing().getProperties().get(PROPERTY_CLIENT_ID);
        return prop != null ? prop : "";
    }

    private void persistClientId(String clientId) {
        // Persist in thing properties — works for all Thing types.
        updateProperty(PROPERTY_CLIENT_ID, clientId);
        // Also persist in the configuration JSONDB for managed (UI-created) Things.
        Configuration cfg = editConfiguration();
        cfg.put("clientId", clientId);
        updateConfiguration(cfg);
    }

    private static String generateClientId() {
        byte[] bytes = new byte[6];
        new java.security.SecureRandom().nextBytes(bytes);
        // Locally-administered, unicast MAC-style identifier.
        bytes[0] = (byte) ((bytes[0] | 0x02) & 0xFE);
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X", bytes[0] & 0xFF, bytes[1] & 0xFF, bytes[2] & 0xFF,
                bytes[3] & 0xFF, bytes[4] & 0xFF, bytes[5] & 0xFF);
    }
}
