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

import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Keeps {@code external_inbound_event} from growing without bound (design phase 6 §5): one row per event per consumer,
 * otherwise never deleted. Every {@code trading-demo.simulators.inbound-event-prune-interval} the rows received longer
 * ago than {@code inbound-event-retention} are deleted, in batches so no statement holds many row locks. Runs on every
 * instance; the deletes are idempotent, so two instances pruning at once only share the work.
 */
@Component
public class InboundEventRetention {
    private static final Logger log   = LoggerFactory.getLogger(InboundEventRetention.class);
    private static final int    BATCH = 10_000;

    private final JdbcTemplate             jdbcTemplate;
    private final SimulatorProperties      properties;
    private final ScheduledExecutorService pruning = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("inbound-event-retention").daemon().factory());

    public InboundEventRetention(JdbcTemplate jdbcTemplate, SimulatorProperties properties) {
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
        this.properties = requireNonNull(properties, "No properties provided");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        var interval = properties.getInboundEventPruneInterval().toMillis();
        pruning.scheduleWithFixedDelay(this::pruneSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        pruning.shutdownNow();
    }

    /**
     * @return the rows deleted
     */
    public long prune() {
        var receivedBefore = Timestamp.from(Instant.now().minus(properties.getInboundEventRetention()));
        var deleted        = 0L;
        while (true) {
            var batch = jdbcTemplate.update("""
                                                    DELETE FROM external_inbound_event WHERE ctid IN
                                                        (SELECT ctid FROM external_inbound_event WHERE received_at < ? LIMIT ?)
                                                    """,
                                            receivedBefore, BATCH);
            deleted += batch;
            if (batch < BATCH) {
                return deleted;
            }
        }
    }

    private void pruneSafely() {
        try {
            var deleted = prune();
            if (deleted > 0) {
                log.info("Deleted {} deduplication row(s) older than {}", deleted, properties.getInboundEventRetention());
            }
        } catch (RuntimeException e) {
            log.warn("Pruning external_inbound_event failed; retried in {}", properties.getInboundEventPruneInterval(), e);
        }
    }
}
