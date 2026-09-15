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

import static org.openhab.binding.atagone.internal.AtagOneBindingConstants.WEEKDAY_BY_NAME;
import static org.openhab.binding.atagone.internal.AtagOneBindingConstants.WEEKDAY_NAMES;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.atagone.internal.dto.ScheduleDTO;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * JSON codec for the whole-week CH/DHW schedule shape shared by the {@code heating#schedule} /
 * {@code hotwater#schedule} read channels and the {@code setChSchedule}/{@code setDhwSchedule} Thing
 * Actions.
 * <p>
 * {@code days.<weekday>} array order is exactly {@code entries[dayIndex]} order, unmodified — a
 * caller (the schedule-editing UI this exists for) resolves "which period did I just edit" purely
 * from its position in this array, then calls {@code setChSchedulePeriod}/etc. with that same index.
 * Reordering or filtering periods here would silently misdirect a caller's next edit onto the wrong
 * period on the live boiler.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault
public final class ScheduleJson {

    private static final Gson GSON = new GsonBuilder().create();

    private ScheduleJson() {
    }

    /**
     * Serializes a schedule to the documented JSON shape — all seven weekdays always present, in
     * monday..sunday order, each day's periods in {@code entries[dayIndex]} order unmodified.
     */
    public static String toJson(double baseTemp, double[][][] entries) {
        JsonObject root = new JsonObject();
        root.addProperty("baseTemp", baseTemp);
        JsonObject days = new JsonObject();
        for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
            JsonArray periods = new JsonArray();
            double @Nullable [][] dayEntries = dayIndex < entries.length ? entries[dayIndex] : null;
            if (dayEntries != null) {
                for (double[] period : dayEntries) {
                    JsonObject p = new JsonObject();
                    p.addProperty("start", (long) period[0]);
                    p.addProperty("end", (long) period[1]);
                    p.addProperty("temp", period[2]);
                    periods.add(p);
                }
            }
            days.add(WEEKDAY_NAMES.get(dayIndex + 1), periods);
        }
        root.add("days", days);
        return GSON.toJson(root);
    }

    /**
     * Parses a (possibly partial) schedule write. Weekdays absent from {@code days} are filled from
     * {@code currentEntries} unchanged — the device requires the whole schedule object on every
     * write regardless, so this lets a caller save just the day(s) it actually edited in one call.
     *
     * @return the composed schedule ready to send, or {@code null} if the input is malformed, names
     *         an unrecognized weekday, names a weekday beyond {@code currentEntries.length}, or
     *         contains a period failing {@link #isValidPeriod}
     */
    @Nullable
    public static ScheduleDTO parse(String json, double currentBaseTemp, double[][][] currentEntries) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            return null;
        }

        double baseTemp = currentBaseTemp;
        if (root.has("baseTemp")) {
            Double parsedBaseTemp = numberOrNull(root, "baseTemp");
            if (parsedBaseTemp == null) {
                return null;
            }
            baseTemp = parsedBaseTemp;
        }

        double[][][] entries = currentEntries.clone();
        if (root.has("days")) {
            JsonElement daysElement = root.get("days");
            if (!daysElement.isJsonObject()) {
                return null;
            }
            for (Map.Entry<String, JsonElement> dayEntry : daysElement.getAsJsonObject().entrySet()) {
                Integer weekdayNumber = WEEKDAY_BY_NAME.get(dayEntry.getKey().toLowerCase());
                if (weekdayNumber == null) {
                    return null;
                }
                int dayIndex = weekdayNumber - 1;
                if (dayIndex < 0 || dayIndex >= currentEntries.length) {
                    return null;
                }
                double[][] dayPeriods = parseDayPeriods(dayEntry.getValue());
                if (dayPeriods == null) {
                    return null;
                }
                entries[dayIndex] = dayPeriods;
            }
        }

        ScheduleDTO schedule = new ScheduleDTO();
        schedule.base_temp = baseTemp;
        schedule.entries = entries;
        return schedule;
    }

    private static double @Nullable [][] parseDayPeriods(JsonElement periodsElement) {
        if (!periodsElement.isJsonArray()) {
            return null;
        }
        JsonArray periodsJson = periodsElement.getAsJsonArray();
        double[][] dayPeriods = new double[periodsJson.size()][];
        for (int i = 0; i < periodsJson.size(); i++) {
            JsonElement periodElement = periodsJson.get(i);
            if (!periodElement.isJsonObject()) {
                return null;
            }
            JsonObject p = periodElement.getAsJsonObject();
            Double start = numberOrNull(p, "start");
            Double end = numberOrNull(p, "end");
            Double temp = numberOrNull(p, "temp");
            if (start == null || end == null || temp == null || !isValidPeriod(start, end)) {
                return null;
            }
            dayPeriods[i] = new double[] { start, end, temp };
        }
        return dayPeriods;
    }

    @Nullable
    private static Double numberOrNull(JsonObject obj, String field) {
        if (!obj.has(field)) {
            return null;
        }
        JsonElement element = obj.get(field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsDouble();
    }

    /**
     * {@code 0 <= start < end <= 1440} — matches every observed device schedule (never wraps
     * midnight, never zero-length). Applied to both this codec and the four per-period Thing Actions
     * in {@link AtagOneHandler#composeSchedulePeriodChange}, so both write paths reject the same
     * malformed period.
     */
    public static boolean isValidPeriod(double startMinutes, double endMinutes) {
        return startMinutes >= 0 && endMinutes <= 1440 && startMinutes < endMinutes;
    }
}
