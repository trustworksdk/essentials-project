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
package dk.trustworks.essentials.components.eventlog;

import org.slf4j.*;

import java.util.List;

/**
 * A subscription's per-event TRACE lines, on a logger of its own so it can be switched on for one subscriber:
 * {@code dk.trustworks.essentials.components.eventlog.Subscription.<subscriber>}, every character of the subscriber name
 * outside {@code [A-Za-z0-9_-]} (a dot included, so the name stays one level below {@code Subscription}'s logger)
 * replaced by {@code _}. Created once per subscription; every line is guarded by {@code isTraceEnabled()}, so with TRACE
 * off a page costs one level check.
 */
final class DeliveryTrace {
    private final Logger log;
    private final String subscriber;
    private final String aggregateType;

    DeliveryTrace(String subscriber, String aggregateType) {
        this.subscriber = subscriber;
        this.aggregateType = aggregateType;
        this.log = LoggerFactory.getLogger(loggerName(subscriber));
    }

    static String loggerName(String subscriber) {
        return Subscription.class.getName() + "." + subscriber.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** One event about to be handed to a periodic handler */
    void delivering(StoredEvent event) {
        if (log.isTraceEnabled()) {
            log.trace("delivering {} global_order {} event_order {} to {}", event.eventType(), event.globalOrder(), event.eventOrder(), subscriber);
        }
    }

    /** The events of a page about to be handed to a page handler */
    void delivering(List<StoredEvent> page) {
        if (log.isTraceEnabled()) {
            for (var event : page) {
                log.trace("delivering {} global_order {} event_order {} to {}", event.eventType(), event.globalOrder(), event.eventOrder(), subscriber);
            }
        }
    }

    /** An in-transaction page committed with its resume point */
    void pageCommitted(List<StoredEvent> page, int parked, long resumeFrom, long nanos) {
        if (log.isTraceEnabled()) {
            log.trace("page committed: {} events, global_order {}..{} ({} parked), resume_from {} for {} on {} in {} us", page.size(), page.getFirst().globalOrder(),
                      page.getLast().globalOrder(), parked, resumeFrom, subscriber, aggregateType, nanos / 1000);
        }
    }
}
