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

package dk.trustworks.essentials.components.foundation.postgresql;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.reactive.EventBus;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.ConnectionException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiTableChangeListenerTest {

    @Test
    void listenToNotificationsFor_with_invalid_column_name() {
        try (var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(
                mock(Jdbi.class),
                Duration.ofSeconds(30), // Very long poll length to avoid errors from the polling mechanism
                mock(JSONSerializer.class),
                mock(EventBus.class),
                false
        )) {
            assertThatThrownBy(() -> listener.listenToNotificationsFor("where", MultiTableChangeListenerIT.TestTableNotification.class))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.listenToNotificationsFor("1=1", MultiTableChangeListenerIT.TestTableNotification.class))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.listenToNotificationsFor("1; DROP TABLE", MultiTableChangeListenerIT.TestTableNotification.class))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
        }
    }

    @Test
    void unlistenToNotificationsFor_with_invalid_column_name() {
        try (var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(
                mock(Jdbi.class),
                Duration.ofSeconds(30), // Very long poll length to avoid errors from the polling mechanism
                mock(JSONSerializer.class),
                mock(EventBus.class),
                false
        )) {
            assertThatThrownBy(() -> listener.unlistenToNotificationsFor("where"))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.unlistenToNotificationsFor("1=1"))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
            assertThatThrownBy(() -> listener.unlistenToNotificationsFor("1; DROP TABLE"))
                    .isInstanceOf(InvalidTableOrColumnNameException.class);
        }
    }

    /**
     * LN-2: a lost connection used to be logged at DEBUG only. It is now reported at WARN - but a database that stays
     * unreachable fails every poll, so the report is rate-limited rather than repeated every 50 ms.
     */
    @Test
    void a_connection_that_cannot_be_opened_is_reported_at_warn_once_per_interval() {
        var jdbi = mock(Jdbi.class);
        when(jdbi.open()).thenThrow(new ConnectionException(new SQLException("Connection refused", "08001")));
        var logger   = (Logger) LoggerFactory.getLogger(MultiTableChangeListener.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var listener = new MultiTableChangeListener<MultiTableChangeListenerIT.TestTableNotification>(jdbi,
                                                                                                         Duration.ofMillis(10),
                                                                                                         mock(JSONSerializer.class),
                                                                                                         mock(EventBus.class),
                                                                                                         false)) {
            listener.listenToNotificationsFor("table1", MultiTableChangeListenerIT.Table1Notification.class);

            // Many polls, each of which fails to open the connection
            Awaitility.waitAtMost(Duration.ofSeconds(5))
                      .untilAsserted(() -> verify(jdbi, atLeast(20)).open());

            assertThat(appender.list.stream().filter(event -> event.getLevel() == Level.WARN))
                    .hasSize(1)
                    .first()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("lost its LISTEN connection", "[table1]");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
        } finally {
            logger.detachAppender(appender);
        }
    }
}
