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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class GapsNotRecordedWarningsTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private final AtomicLong                  nanoTime = new AtomicLong();
    private       Logger                      logger;
    private       ListAppender<ILoggingEvent> logAppender;
    private       GapsNotRecordedWarnings     warnings;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(GapsNotRecordedWarningsTest.class.getName() + ".log");
        logger.setLevel(Level.DEBUG);
        // Kept off the console: one test logs a few thousand lines
        logger.setAdditive(false);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        warnings = new GapsNotRecordedWarnings(logger, nanoTime::get);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void a_save_that_keeps_failing_WARNs_with_the_stack_trace_once_then_logs_at_DEBUG_with_one_WARN_a_minute_counting_the_failures() {
        var orderView = resumePoint("OrderView");

        // The threshold check runs as often as every 50 ms: two minutes of it, the first check included
        var checks = 2 * 60 * 20 + 1;
        for (int check = 0; check < checks; check++) {
            warnings.notSaved(List.of(orderView), notRecorded(orderView));
            nanoTime.addAndGet(Duration.ofMillis(50).toNanos());
        }

        var warns = logged(Level.WARN);
        assertThat(warns).hasSize(3);
        assertThat(warns.get(0).getThrowableProxy()).isNotNull();
        assertThat(warns.subList(1, 3)).allSatisfy(summary -> {
            assertThat(summary.getThrowableProxy()).isNull();
            assertThat(summary.getFormattedMessage()).contains("OrderView").contains(ORDERS.toString()).contains("1200 time(s)");
        });
        assertThat(logged(Level.DEBUG)).hasSize(checks - 3);
    }

    @Test
    void a_save_that_failed_for_another_subscriber_WARNs_with_the_stack_trace_for_it() {
        var orderView   = resumePoint("OrderView");
        var invoiceView = resumePoint("InvoiceView");
        warnings.notSaved(List.of(orderView, invoiceView), notRecorded(orderView));

        warnings.notSaved(List.of(orderView, invoiceView), notRecorded(invoiceView));

        assertThat(logged(Level.WARN)).hasSize(2).allSatisfy(warn -> assertThat(warn.getThrowableProxy()).isNotNull());
    }

    @Test
    void a_subscriber_whose_resume_point_was_saved_WARNs_with_the_stack_trace_again_when_it_fails_again() {
        var orderView = resumePoint("OrderView");
        warnings.notSaved(List.of(orderView), notRecorded(orderView));
        warnings.saved(List.of(orderView));

        warnings.notSaved(List.of(orderView), notRecorded(orderView));

        assertThat(logged(Level.WARN)).hasSize(2).allSatisfy(warn -> assertThat(warn.getThrowableProxy()).isNotNull());
    }

    private List<ILoggingEvent> logged(Level level) {
        return logAppender.list.stream().filter(event -> level.equals(event.getLevel())).toList();
    }

    private static SubscriptionResumePoint resumePoint(String subscriber) {
        return new SubscriptionResumePoint(SubscriberId.of(subscriber), ORDERS, GlobalEventOrder.of(10), OffsetDateTime.now(Clock.systemUTC()));
    }

    private static GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException notRecorded(SubscriptionResumePoint resumePoint) {
        return GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException.adding(null, resumePoint, new IllegalStateException("gap handler failed"));
    }
}
