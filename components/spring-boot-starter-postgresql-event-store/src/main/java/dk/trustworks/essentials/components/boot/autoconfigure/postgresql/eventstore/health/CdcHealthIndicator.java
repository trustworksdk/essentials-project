/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.health;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EssentialsEventStoreProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.*;
import org.springframework.boot.actuate.health.*;

import java.time.Instant;
import java.util.Optional;

public class CdcHealthIndicator implements HealthIndicator {

    private final CdcAvailability availability;
    private final Optional<Wal2JsonTailer> tailer;
    private final Optional<CdcDispatcher> dispatcher;
    private final EssentialsEventStoreProperties properties;

    public CdcHealthIndicator(CdcAvailability availability,
                              Optional<Wal2JsonTailer> tailer,
                              Optional<CdcDispatcher> dispatcher,
                              EssentialsEventStoreProperties properties) {
        this.availability = availability;
        this.tailer = tailer;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public Health health() {
        var snapshot = availability.snapshot();
        var mode = properties.getCdc().getMode();

        Status status;
        switch (snapshot.state()) {
            case ACTIVE -> status = Status.UP;
            case FAILED -> status = (mode == CdcMode.REQUIRE) ? Status.DOWN : Status.UP;
            case INACTIVE -> status = Status.UP;
            default -> status = Status.UNKNOWN;
        }

        var builder = Health.status(status)
                            .withDetail("state", snapshot.state().name())
                            .withDetail("mode", mode.name())
                            .withDetail("slot", snapshot.slotName())
                            .withDetail("reason", snapshot.reason())
                            .withDetail("fallbackCount", snapshot.fallbackCount())
                            .withDetail("lastChanged", snapshot.lastChangedEpochMs() == 0
                                                     ? null
                                                     : Instant.ofEpochMilli(snapshot.lastChangedEpochMs()).toString());

        tailer.ifPresent(t -> {
            var s = t.getStatus();
            builder.withDetail("tailer.started", s.started())
                   .withDetail("tailer.slotLockAcquired", s.slotLockAcquired())
                   .withDetail("tailer.lastReceiveLsn", s.lastReceiveLsn())
                   .withDetail("tailer.lastAckedLsn", s.lastAckedLsn())
                   .withDetail("tailer.lastMessageEpochMs", s.lastMessageEpochMs());
        });

        dispatcher.ifPresent(d ->
                builder.withDetail("dispatcher.started", d.isStarted())
        );

        return builder.build();
    }
}
