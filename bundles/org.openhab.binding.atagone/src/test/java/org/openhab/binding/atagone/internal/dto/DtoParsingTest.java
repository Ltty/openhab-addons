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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Verifies Gson DTO parsing against captured fixture JSON.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault
class DtoParsingTest {

    private static final Gson GSON = new GsonBuilder().create();

    private String loadFixture(String name) throws IOException {
        try (@Nullable
        InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, "Fixture not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void retrieveReplyParsesCorrectly() throws IOException {
        String json = loadFixture("retrieve_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject replyObj = root.getAsJsonObject("retrieve_reply");
        RetrieveReplyDTO reply = GSON.fromJson(replyObj, RetrieveReplyDTO.class);

        assertEquals(2, reply.acc_status);
        assertEquals(0, reply.seqnr);
    }

    @Test
    void statusBlockParsesCorrectly() throws IOException {
        String json = loadFixture("retrieve_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RetrieveReplyDTO reply = GSON.fromJson(root.getAsJsonObject("retrieve_reply"), RetrieveReplyDTO.class);

        assertEquals("6808-1401-3109_15-30-001-544", reply.status.device_id);
        assertEquals(766123456L, reply.status.date);
    }

    @Test
    void reportTemperaturesParsedCorrectly() throws IOException {
        String json = loadFixture("retrieve_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RetrieveReplyDTO reply = GSON.fromJson(root.getAsJsonObject("retrieve_reply"), RetrieveReplyDTO.class);

        assertEquals(21.3, reply.report.room_temp, 0.001);
        // Negative outside temperature must remain negative — kozmoz/atag-one-api issue #36
        assertEquals(-3.5, reply.report.outside_temp, 0.001);
        assertEquals(1.52, reply.report.ch_water_pres, 0.001);
        assertEquals(3521.75, reply.report.burning_hours, 0.001);
    }

    @Test
    void reportDetailsParsedCorrectly() throws IOException {
        String json = loadFixture("retrieve_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RetrieveReplyDTO reply = GSON.fromJson(root.getAsJsonObject("retrieve_reply"), RetrieveReplyDTO.class);

        assertEquals(45, reply.report.details.rel_mod_level);
        assertEquals(15, reply.report.details.min_mod_level);
        assertEquals(90.0, reply.report.details.max_boiler_temp, 0.001);
        assertEquals(230, reply.report.details.voltage);
    }

    @Test
    void controlBlockParsedCorrectly() throws IOException {
        String json = loadFixture("retrieve_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RetrieveReplyDTO reply = GSON.fromJson(root.getAsJsonObject("retrieve_reply"), RetrieveReplyDTO.class);

        assertEquals(2, reply.control.ch_mode); // automatic
        assertEquals(0, reply.control.ch_control_mode); // auto (not heat-only)
        assertEquals(60.0, reply.control.dhw_temp_setp, 0.001);
    }

    @Test
    void configurationBlockParsedCorrectly() throws IOException {
        String json = loadFixture("retrieve_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        RetrieveReplyDTO reply = GSON.fromJson(root.getAsJsonObject("retrieve_reply"), RetrieveReplyDTO.class);

        assertEquals(15, reply.configuration.ch_min_set);
        assertEquals(30, reply.configuration.ch_max_set);
        assertEquals(15.0, reply.configuration.ch_vacation_temp, 0.001);
        assertEquals(1, reply.configuration.legionella_prot);
    }

    @Test
    void pairReplyParsesCorrectly() throws IOException {
        String json = loadFixture("pair_reply.json");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        PairReplyDTO reply = GSON.fromJson(root.getAsJsonObject("pair_reply"), PairReplyDTO.class);

        assertEquals(1, reply.acc_status); // pending — user must press Accept
        assertEquals(0, reply.seqnr);
    }

    @Test
    void controlUpdateOmitsNullFields() {
        ControlUpdateDTO update = new ControlUpdateDTO();
        update.ch_mode = 3;
        update.vacation_duration = 604800L;
        update.start_vacation = 830995200L;

        String json = GSON.toJson(update);
        assertFalse(json.contains("ch_control_mode"), "null fields must not appear in JSON");
        assertTrue(json.contains("\"ch_mode\":3"));
        assertTrue(json.contains("\"vacation_duration\":604800"));
        assertTrue(json.contains("\"start_vacation\":830995200"));
    }
}
