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


package dk.trustworks.essentials.components.foundation.messaging.queue;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HandlerTimeoutOverrun}: counts a handler that outlasted the queue's delivery window, WARNs at most once a minute,
 * and does nothing without a window or inside it.
 */
class HandlerTimeoutOverrunTest {
    private static final QueueName QUEUE = QueueName.of("Orders");

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(HandlerTimeoutOverrun.class)).addAppender(appender);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(HandlerTimeoutOverrun.class)).detachAppender(appender);
    }

    @Test
    void a_handler_inside_the_window_or_without_one_is_not_counted() {
        var overrun = new HandlerTimeoutOverrun();
        var started = System.nanoTime();

        overrun.check(QUEUE, started, Optional.of(Duration.ofMinutes(1)));
        overrun.check(QUEUE, started - Duration.ofHours(1).toNanos(), Optional.empty());

        assertThat(overrun.overruns()).isZero();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void every_overrun_is_counted_and_warned_about_at_most_once_a_minute() {
        var overrun     = new HandlerTimeoutOverrun();
        var longAgo     = System.nanoTime() - Duration.ofSeconds(45).toNanos();

        overrun.check(QUEUE, longAgo, Optional.of(Duration.ofSeconds(30)));
        overrun.check(QUEUE, longAgo, Optional.of(Duration.ofSeconds(30)));
        overrun.check(QUEUE, longAgo, Optional.of(Duration.ofSeconds(30)));

        assertThat(overrun.overruns()).isEqualTo(3);
        assertThat(appender.list).singleElement()
                                 .satisfies(event -> {
                                     assertThat(event.getLevel()).isEqualTo(Level.WARN);
                                     assertThat(event.getFormattedMessage()).contains("[Orders]")
                                                                            .contains("longer than the 30000 ms")
                                                                            .contains("message-handling-timeout");
                                 });
    }
}
