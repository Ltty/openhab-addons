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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles the ATAG ONE thermostat thing. Pairing, polling, and command dispatch are implemented
 * in Phase 3; this stub establishes the constructor contract for the factory.
 *
 * @author Florian Lettner - Initial contribution
 */
@NonNullByDefault
public class AtagOneHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(AtagOneHandler.class);

    @SuppressWarnings("unused")
    private final HttpClient httpClient;

    private @Nullable AtagOneConfiguration config;

    public AtagOneHandler(Thing thing, HttpClient httpClient) {
        super(thing);
        this.httpClient = httpClient;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Implemented in Phase 3.
    }

    @Override
    public void initialize() {
        AtagOneConfiguration cfg = getConfigAs(AtagOneConfiguration.class);
        config = cfg;
        logger.debug("Initialising ATAG ONE handler for {}", cfg.hostname);
        updateStatus(ThingStatus.UNKNOWN);
        // Full pairing + polling loop implemented in Phase 3.
        scheduler.execute(() -> updateStatus(ThingStatus.OFFLINE));
    }
}
