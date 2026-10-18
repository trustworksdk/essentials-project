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

package dk.trustworks.essentials.examples.trading.integration;

import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Hands an external event to a consuming slice's {@link Inbox} exactly once per {@code (consumer, eventId)}, whichever
 * way it arrived: a webhook delivery, a redelivery, or the catch-up read of the source's event log.
 * <p>
 * The deduplication row and the {@code Inbox} message are written in one {@code UnitOfWork}, so either both commit or
 * neither does: a rejected or failed attempt leaves no row behind to swallow the retry. That is the simulators'
 * receiver rule "record an id only when the processing succeeded".
 * <p>
 * The message is an {@link OrderedMessage} keyed by the source's {@code aggregateId}, with the source's
 * {@code sequence} as its order. That keeps one external aggregate's events in order when they are queued together.
 * It does <em>not</em> reorder deliveries that arrive out of order (the queue delivers what is visible, in order) — a
 * consumer must tolerate that, and does by comparing sequences or by idempotency on the source's own ids.
 */
@Component
public class ExternalEventIntake {
    private final JdbcTemplate                            jdbcTemplate;
    private final UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    public ExternalEventIntake(JdbcTemplate jdbcTemplate,
                               UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.jdbcTemplate.execute("""
                                          CREATE TABLE IF NOT EXISTS external_inbound_event (
                                              consumer VARCHAR NOT NULL,
                                              event_id VARCHAR NOT NULL,
                                              source VARCHAR NOT NULL,
                                              sequence BIGINT NOT NULL,
                                              event_type VARCHAR NOT NULL,
                                              aggregate_id VARCHAR NOT NULL,
                                              occurred_at TIMESTAMPTZ NOT NULL,
                                              received_at TIMESTAMPTZ NOT NULL,
                                              PRIMARY KEY (consumer, event_id)
                                          )
                                          """);
    }

    /**
     * @param consumer the consuming slice's name; the same event may be consumed by several slices, once each
     * @param envelope an envelope the caller has already validated for its event type
     * @param inbox    the consuming slice's inbox
     * @return {@code true} if the event was new for this consumer and is now queued, {@code false} for a duplicate
     */
    public boolean accept(String consumer, ExternalEventEnvelope envelope, Inbox inbox) {
        requireNonNull(consumer, "No consumer provided");
        requireNonNull(envelope, "No envelope provided");
        requireNonNull(inbox, "No inbox provided");
        return unitOfWorkFactory.withUnitOfWork(() -> {
            var inserted = jdbcTemplate.update("""
                                                       INSERT INTO external_inbound_event (
                                                           consumer, event_id, source, sequence, event_type, aggregate_id, occurred_at, received_at
                                                       )
                                                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                                       ON CONFLICT (consumer, event_id) DO NOTHING
                                                       """,
                                               consumer,
                                               envelope.eventId(),
                                               envelope.source(),
                                               envelope.sequence(),
                                               envelope.eventType(),
                                               envelope.aggregateId(),
                                               Timestamp.from(envelope.occurredAtInstant()),
                                               Timestamp.from(Instant.now()));
            if (inserted == 0) {
                return false;
            }
            inbox.addMessageReceived(OrderedMessage.of(envelope, envelope.aggregateId(), envelope.sequence()));
            return true;
        });
    }
}
