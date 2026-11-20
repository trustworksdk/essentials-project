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

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * The reader loop's thread must never end in a state where {@code submit} will not start another one: an observer
 * that throws inside the restart (or an {@link Error}) used to end the thread with {@code thread} still set, and every
 * later attach waited forever. No database: the {@link DataSource} refuses every connection, so the loop goes straight
 * to its restart path.
 */
class ReaderLoopFailureTest {
    private static final DataSource NO_DATABASE = (DataSource) Proxy.newProxyInstance(ReaderLoopFailureTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                                                      (proxy, method, args) -> {
                                                                                          if (method.getName().equals("getConnection")) {
                                                                                              throw new SQLException("no database in this test", "08001");
                                                                                          }
                                                                                          throw new UnsupportedOperationException(method.getName());
                                                                                      });

    @Test
    void an_observer_throwing_in_the_restart_does_not_leave_attaches_waiting_forever() throws Exception {
        var errors = new AtomicInteger();
        var observer = new EventLogObserver() {
            @Override
            public void onError(String subscriber, String aggregateType, Throwable error) {
                errors.incrementAndGet();
                throw new IllegalStateException("observer bug");
            }
        };
        var loop = new ReaderLoop(NO_DATABASE, EventLogSettings.defaults(), observer);
        var type = loop.join("orders", EventLogStorage.TailStatements.of("el_orders_events"), resultSet -> null, TestSeams.NONE);
        // The first connect fails and the observer throws inside the restart: that ended the thread for good before
        await().atMost(Duration.ofSeconds(10)).until(() -> errors.get() > 0);

        var reply = loop.attach(type, new Inbox(10), "subscriber", TestSeams.Hooks.NONE);

        assertThat(reply.get(10, TimeUnit.SECONDS).accepted()).isFalse();
    }

    @Test
    void the_engine_wraps_a_throwing_observer() {
        var calls = new AtomicInteger();
        var throwing = new EventLogObserver() {
            @Override
            public void onAttach(String subscriber, String aggregateType, long cursor, long watermark) {
                calls.incrementAndGet();
                throw new IllegalStateException("observer bug");
            }
        };
        var safe = SafeEventLogObserver.wrap(throwing);

        assertThatCode(() -> safe.onAttach("subscriber", "orders", 1, 1)).doesNotThrowAnyException();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(SafeEventLogObserver.wrap(safe)).isSameAs(safe);
        assertThat(SafeEventLogObserver.wrap(EventLogObserver.NONE)).isSameAs(EventLogObserver.NONE);
    }
}
