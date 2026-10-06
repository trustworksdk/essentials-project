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

package dk.trustworks.essentials.components.queue.shardowned;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A handler failing for everything blocks every key with traffic. Each key is reported once already;
 * the WARN must also not repeat per key, or a systemic failure is a WARN per key without end.
 */
class BlockedKeyReportTest {

    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger ownerLogger;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        ownerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OrderedShardOwner.class);
        ownerLogger.setLevel(Level.DEBUG);
        ownerLogger.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        ownerLogger.detachAppender(logged);
        ownerLogger.setLevel(null);
    }

    @Test
    void the_first_blocked_key_warns_and_the_rest_are_counted_into_the_next_warning() {
        var metrics = new ShardOwnerMetrics();

        BlockedKeyReport.keyBlocked(metrics, (short) 3, 13, "key-1", 0);
        BlockedKeyReport.keyBlocked(metrics, (short) 3, 41, "key-2", 0);
        BlockedKeyReport.keyBlocked(metrics, (short) 3, 7, "key-3", 4);

        assertThat(levels()).containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG);
        assertThat(logged.list.getFirst().getFormattedMessage())
                .contains("Queue 3, ordered shard 13: key 'key-1' is blocked at key_order 0")
                .contains("0 other key(s) were blocked since the last of these warnings");

        // The minute is up: the next block warns again, and carries the two held back since.
        metrics.nextBlockedKeyWarningNanos.set(System.nanoTime() - 1);
        BlockedKeyReport.keyBlocked(metrics, (short) 3, 9, "key-4", 0);

        assertThat(levels()).containsExactly(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN);
        assertThat(logged.list.getLast().getFormattedMessage())
                .contains("key 'key-4'")
                .contains("2 other key(s) were blocked since the last of these warnings");
        assertThat(metrics.blockedKeysSinceLastWarning.sum()).as("reset by the warning that reported them").isZero();
    }

    private java.util.List<Level> levels() {
        return logged.list.stream().map(ILoggingEvent::getLevel).toList();
    }
}
