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

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeliveryTrace}: per-event TRACE lines on a logger per subscriber, emitted only when that subscriber's logger is
 * at TRACE, and a sanitised logger name.
 */
class DeliveryTraceTest {
    private final List<Logger>              touched  = new ArrayList<>();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void start() {
        appender.start();
    }

    @AfterEach
    void detach() {
        touched.forEach(logger -> {
            logger.detachAppender(appender);
            logger.setLevel(null);
        });
    }

    private Logger logger(String subscriber, Level level) {
        var logger = (Logger) LoggerFactory.getLogger(DeliveryTrace.loggerName(subscriber));
        logger.setLevel(level);
        logger.addAppender(appender);
        touched.add(logger);
        return logger;
    }

    private static StoredEvent event(long globalOrder, long eventOrder) {
        return new StoredEvent("Orders", globalOrder, eventOrder, UUID.randomUUID(), null, null, "OrderPlaced", 1, OffsetDateTime.now(), "o-1", "{}", "{}", null);
    }

    @Test
    void the_logger_name_is_the_subscriber_below_subscription_sanitised() {
        assertThat(DeliveryTrace.loggerName("orders-view")).isEqualTo("dk.trustworks.essentials.components.eventlog.Subscription.orders-view");
        assertThat(DeliveryTrace.loggerName("billing.Invoice view/1")).isEqualTo("dk.trustworks.essentials.components.eventlog.Subscription.billing_Invoice_view_1");
    }

    @Test
    void trace_lines_are_emitted_only_for_the_subscriber_whose_logger_is_at_trace() {
        logger("traced-view", Level.TRACE);
        logger("quiet-view", Level.DEBUG);
        var traced = new DeliveryTrace("traced-view", "Orders");
        var quiet  = new DeliveryTrace("quiet-view", "Orders");
        var page   = List.of(event(7, 1), event(8, 2));

        quiet.delivering(event(6, 0));
        quiet.delivering(page);
        quiet.pageCommitted(page, 0, 9, 2_000_000);
        assertThat(appender.list).isEmpty();

        traced.delivering(event(6, 0));
        traced.delivering(page);
        traced.pageCommitted(page, 1, 9, 2_000_000);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                                 .containsExactly("delivering OrderPlaced global_order 6 event_order 0 to traced-view",
                                                  "delivering OrderPlaced global_order 7 event_order 1 to traced-view",
                                                  "delivering OrderPlaced global_order 8 event_order 2 to traced-view",
                                                  "page committed: 2 events, global_order 7..8 (1 parked), resume_from 9 for traced-view on Orders in 2000 us");
        assertThat(appender.list).allMatch(line -> line.getLevel() == Level.TRACE);
    }
}
