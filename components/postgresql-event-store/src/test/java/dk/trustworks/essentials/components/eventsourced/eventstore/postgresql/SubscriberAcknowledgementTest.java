/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SubscriberAcknowledgementTest {
    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SubscriberAcknowledgement.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void is_not_honoured_until_an_event_store_registers_and_then_passes_acknowledgements_to_it() {
        var acknowledgement = SubscriberAcknowledgement.create();
        var event           = mock(PersistedEvent.class);
        assertThat(acknowledgement.isHonoured()).isFalse();
        acknowledgement.acknowledge(event); // no-op

        var received = new ArrayList<PersistedEvent>();
        acknowledgement.onAcknowledge(received::addAll);
        acknowledgement.acknowledge(event);

        assertThat(acknowledgement.isHonoured()).isTrue();
        assertThat(received).containsExactly(event);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_second_registration_logs_one_warning_naming_the_one_subscription_contract() {
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = new ArrayList<PersistedEvent>();
        acknowledgement.onAcknowledge(received::addAll);

        acknowledgement.onAcknowledge(received::addAll);
        acknowledgement.onAcknowledge(received::addAll);

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage()).contains("ONE subscription");
    }

    @Test
    void a_disposed_registration_receives_no_acknowledgements_and_disposing_it_again_frees_nothing_more() {
        var acknowledgement = SubscriberAcknowledgement.create();
        var disposed        = new ArrayList<PersistedEvent>();
        var registration    = acknowledgement.onAcknowledge(disposed::addAll);
        registration.dispose();
        registration.dispose();
        assertThat(registration.isDisposed()).isTrue();

        var received = new ArrayList<PersistedEvent>();
        acknowledgement.onAcknowledge(received::addAll);
        var event = mock(PersistedEvent.class);
        acknowledgement.acknowledge(event);
        assertThat(disposed).isEmpty();
        assertThat(received).containsExactly(event);
        assertThat(warnings()).isEmpty();

        // Still two active registrations - the second dispose above did not count the first one out twice
        acknowledgement.onAcknowledge(received::addAll);
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void a_registration_after_the_previous_one_was_disposed_is_no_second_subscription() {
        var acknowledgement = SubscriberAcknowledgement.create();
        acknowledgement.onAcknowledge(events -> {
        }).dispose();

        acknowledgement.onAcknowledge(events -> {
        });

        assertThat(warnings()).isEmpty();
    }

    @Test
    void two_concurrent_registrations_log_a_warning() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempts = 200;
            for (var attempt = 0; attempt < attempts; attempt++) {
                var acknowledgement = SubscriberAcknowledgement.create();
                var bothReady       = new CyclicBarrier(2);
                Callable<Disposable> register = () -> {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return acknowledgement.onAcknowledge(events -> {
                    });
                };
                var first  = executor.submit(register);
                var second = executor.submit(register);
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            }

            // One per acknowledgement - none of them missed that the other registration was there
            assertThat(warnings()).hasSize(attempts);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ILoggingEvent> warnings() {
        return logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }
}
