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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.binding.atagone.internal.dto.ScheduleDTO;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for {@link ScheduleJson}, the codec shared by the {@code heating#schedule} /
 * {@code hotwater#schedule} read channels and the {@code setChSchedule}/{@code setDhwSchedule} Thing
 * Actions.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault
class ScheduleJsonTest {

    // Matches the real device shape captured in dev-notes/DEVELOPERS.md and the test fixture.
    private static final double[][][] SAMPLE_ENTRIES = { { { 0, 240, 20.5 }, { 1230, 1440, 20.5 } } };

    @Test
    void toJsonEmitsAllSevenWeekdaysInOrderEvenWithASingleDayOfEntries() {
        String json = ScheduleJson.toJson(22.5, SAMPLE_ENTRIES);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(22.5, root.get("baseTemp").getAsDouble(), 0.001);
        JsonObject days = root.getAsJsonObject("days");
        assertEquals(7, days.size());
        assertTrue(days.has("monday"));
        assertTrue(days.has("sunday"));
        // entries.length == 1 here (Monday only) — every other day must publish an empty array, not
        // be omitted or throw.
        assertEquals(0, days.getAsJsonArray("tuesday").size());
        assertEquals(0, days.getAsJsonArray("sunday").size());
    }

    @Test
    void toJsonKeepsPeriodPositionsUnsorted() {
        // Deliberately out of chronological order — toJson must not sort, since a caller resolves
        // "which period is this" purely from array position.
        double[][][] entries = { { { 480, 1440, 18.0 }, { 0, 240, 19.0 } } };

        JsonArray monday = JsonParser.parseString(ScheduleJson.toJson(22.5, entries)).getAsJsonObject()
                .getAsJsonObject("days").getAsJsonArray("monday");

        assertEquals(480, monday.get(0).getAsJsonObject().get("start").getAsLong());
        assertEquals(0, monday.get(1).getAsJsonObject().get("start").getAsLong());
    }

    @Test
    void toJsonHandlesANullDayEntriesSlotWithoutThrowing() {
        double[][][] entries = new double[2][][];
        entries[0] = new double[][] { { 0, 1440, 18.0 } };
        // entries[1] stays null — a defensively-possible shape mirrored from AtagOneHandler's own
        // handling of a malformed device reply.

        String json = ScheduleJson.toJson(22.5, entries);

        JsonObject days = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("days");
        assertEquals(0, days.getAsJsonArray("tuesday").size());
    }

    @Test
    void roundTripPreservesBaseTempAndAllPeriods() {
        String json = ScheduleJson.toJson(22.5, SAMPLE_ENTRIES);

        ScheduleDTO parsed = ScheduleJson.parse(json, 0.0, new double[7][][]);

        assertNotNull(parsed);
        assertEquals(22.5, parsed.base_temp, 0.001);
        assertEquals(2, parsed.entries[0].length);
        assertArrayEquals(SAMPLE_ENTRIES[0][0], parsed.entries[0][0], 0.001);
        assertArrayEquals(SAMPLE_ENTRIES[0][1], parsed.entries[0][1], 0.001);
        // toJson always emits all 7 weekday keys, so a full round-trip names every day — the other
        // six come back as explicit empty arrays, not left null.
        assertEquals(0, parsed.entries[1].length);
    }

    @Test
    void parseMergesNamedDaysAndFillsTheRestFromCurrentEntries() {
        double[][][] current = new double[7][][];
        current[0] = new double[][] { { 0, 1440, 18.0 } };
        current[1] = new double[][] { { 0, 720, 19.0 }, { 720, 1440, 18.0 } };

        ScheduleDTO parsed = ScheduleJson.parse("{\"days\":{\"monday\":[{\"start\":300,\"end\":900,\"temp\":21.0}]}}",
                22.5, current);

        assertNotNull(parsed);
        assertEquals(1, parsed.entries[0].length);
        assertArrayEquals(new double[] { 300, 900, 21.0 }, parsed.entries[0][0], 0.001);
        // Tuesday wasn't named -> resent byte-for-byte, same array reference even.
        assertSame(current[1], parsed.entries[1]);
    }

    @Test
    void parseFallsBackToCurrentBaseTempWhenOmitted() {
        ScheduleDTO parsed = ScheduleJson.parse("{\"days\":{}}", 22.5, new double[7][][]);

        assertNotNull(parsed);
        assertEquals(22.5, parsed.base_temp, 0.001);
    }

    @Test
    void parseAppliesExplicitBaseTemp() {
        ScheduleDTO parsed = ScheduleJson.parse("{\"baseTemp\":19.5,\"days\":{}}", 22.5, new double[7][][]);

        assertNotNull(parsed);
        assertEquals(19.5, parsed.base_temp, 0.001);
    }

    @Test
    void parseAcceptsAnEmptyObjectAsANoOp() {
        double[][][] current = new double[7][][];
        current[0] = new double[][] { { 0, 1440, 18.0 } };

        ScheduleDTO parsed = ScheduleJson.parse("{}", 22.5, current);

        assertNotNull(parsed);
        assertEquals(22.5, parsed.base_temp, 0.001);
        assertSame(current[0], parsed.entries[0]);
    }

    @Test
    void parseRejectsNonObjectRoot() {
        assertNull(ScheduleJson.parse("[]", 22.5, new double[7][][]));
        assertNull(ScheduleJson.parse("\"hello\"", 22.5, new double[7][][]));
        assertNull(ScheduleJson.parse("not json at all", 22.5, new double[7][][]));
    }

    @Test
    void parseRejectsDaysThatIsNotAnObject() {
        assertNull(ScheduleJson.parse("{\"days\":[]}", 22.5, new double[7][][]));
    }

    @Test
    void parseRejectsUnknownWeekday() {
        assertNull(ScheduleJson.parse("{\"days\":{\"someday\":[]}}", 22.5, new double[7][][]));
    }

    @Test
    void parseRejectsWeekdayBeyondCurrentEntriesLength() {
        // currentEntries has only 2 slots — sunday (index 6) is out of range.
        assertNull(ScheduleJson.parse("{\"days\":{\"sunday\":[]}}", 22.5, new double[2][][]));
    }

    @Test
    void parseRejectsAPeriodMissingAField() {
        assertNull(ScheduleJson.parse("{\"days\":{\"monday\":[{\"start\":0,\"end\":100}]}}", 22.5, new double[7][][]));
    }

    @Test
    void parseRejectsANonNumericField() {
        assertNull(ScheduleJson.parse("{\"days\":{\"monday\":[{\"start\":\"zero\",\"end\":100,\"temp\":20}]}}", 22.5,
                new double[7][][]));
    }

    @Test
    void parseRejectsAnInvalidPeriod() {
        assertNull(ScheduleJson.parse("{\"days\":{\"monday\":[{\"start\":600,\"end\":600,\"temp\":20}]}}", 22.5,
                new double[7][][]));
        assertNull(ScheduleJson.parse("{\"days\":{\"monday\":[{\"start\":600,\"end\":500,\"temp\":20}]}}", 22.5,
                new double[7][][]));
    }

    @Test
    void isValidPeriodAcceptsBoundaryValues() {
        assertTrue(ScheduleJson.isValidPeriod(0, 1440));
        assertTrue(ScheduleJson.isValidPeriod(0, 1));
        assertTrue(ScheduleJson.isValidPeriod(1439, 1440));
    }

    @Test
    void isValidPeriodRejectsZeroLengthOrInvertedOrOutOfRange() {
        assertFalse(ScheduleJson.isValidPeriod(600, 600));
        assertFalse(ScheduleJson.isValidPeriod(600, 500));
        assertFalse(ScheduleJson.isValidPeriod(-1, 100));
        assertFalse(ScheduleJson.isValidPeriod(0, 1441));
    }
}
