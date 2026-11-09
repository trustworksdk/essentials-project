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
import dk.trustworks.essentials.examples.trading.config.TradingDemoSchema;
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
 * The message is an {@link OrderedMessage} keyed by the source's {@code aggregateId}, numbered in the order the events
 * are queued here ({@code external_inbound_event_order}), under a per-key advisory lock held to commit, so one external
 * aggregate's events are numbered in the order they are committed and handled one at a time, in that order. Not by the source's {@code sequence}: webhooks arrive in any order and the catch-up read brings a
 * missed event late, so a lower sequence routinely arrived after a higher one, and the shard-owned engine reported each
 * such key as delivered out of order - a warning meant for ordering bugs, made noise. Arrival order is also all the
 * source's sequence ever gave: the queue delivers what is visible, it never waited for a lower one. A consumer
 * tolerates any order, by comparing the source's sequences (in the envelope) or by idempotency on the source's ids.
 */
@Component
public class ExternalEventIntake {
    private final JdbcTemplate                            jdbcTemplate;
    private final UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    public ExternalEventIntake(JdbcTemplate jdbcTemplate,
                               UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        TradingDemoSchema.ddl(this.jdbcTemplate, """
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
        TradingDemoSchema.ddl(this.jdbcTemplate, "CREATE INDEX IF NOT EXISTS external_inbound_event_received_at ON external_inbound_event (received_at)");
        TradingDemoSchema.ddl(this.jdbcTemplate, "CREATE SEQUENCE IF NOT EXISTS external_inbound_event_order");
        // A database from before numbered messages by the source's sequence: start above every number used, so an
        // aggregate still in flight is not numbered back below its earlier events
        this.jdbcTemplate.queryForObject("""
                                         SELECT setval('external_inbound_event_order',
                                                       GREATEST((SELECT last_value FROM external_inbound_event_order),
                                                                (SELECT coalesce(max(sequence), 0) + 1 FROM external_inbound_event)))
                                         """, Long.class);
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
            // One key at a time until commit, so a key's numbers are committed in the order they are taken: an
            // exchange order's events often arrive on two webhook requests at once, and the later number committed
            // first. Only events of the same aggregate ever wait here
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, consumer + ":" + envelope.aggregateId());
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
            // Taken under the key's lock, in the unit of work that enqueues: number order is commit order
            var order = jdbcTemplate.queryForObject("SELECT nextval('external_inbound_event_order')", Long.class);
            inbox.addMessageReceived(OrderedMessage.of(envelope, envelope.aggregateId(), order));
            return true;
        });
    }
}
