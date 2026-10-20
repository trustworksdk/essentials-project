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

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link ShardRuntime#acquireShared}: a later borrower of a {@link DataSource}'s shared runtime is warned only when it asks
 * for a value the runtime sizes its pumps from - {@code pumpThreads}, {@code readBatchSize}, {@code pollBackstop},
 * {@code maxSweepInterval} - since only those are not applied for it. Every other setting is the borrowing queue's own (its
 * owners are built with the settings it was given), so a queue given its own {@code stuckHandlerTimeout} - the documented
 * way to bound one queue's handlers - must not be told that its setting was ignored.
 */
class ShardRuntimeSharedSettingsTest {
    private ListAppender<ILoggingEvent>   logged;
    private ch.qos.logback.classic.Logger runtimeLogger;
    private DataSource                    dataSource;
    private int                           borrowed;

    @BeforeEach
    void captureLog() {
        logged = new ListAppender<>();
        logged.start();
        runtimeLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ShardRuntime.class);
        runtimeLogger.addAppender(logged);
        // A DataSource of its own per test: the shared runtimes are keyed by its identity.
        dataSource = ShardPumpExitTest.listenableDataSource();
    }

    @AfterEach
    void releaseRuntimeAndLog() {
        for (; borrowed > 0; borrowed--) {
            ShardRuntime.releaseShared(dataSource);
        }
        runtimeLogger.detachAppender(logged);
    }

    @Test
    void a_borrower_whose_settings_differ_only_in_stuck_handler_timeout_is_not_warned() {
        var first  = ShardOwnerSettings.defaults();
        var second = first.withStuckHandlerTimeout(Duration.ofMinutes(2));
        assertThat(second).as("premise: the settings differ").isNotEqualTo(first);

        var runtime = acquire(first);
        assertThat(acquire(second)).as("premise: the second borrower gets the same runtime").isSameAs(runtime);

        assertThat(settingsWarnings()).as("stuckHandlerTimeout is the queue's own, not the runtime's").isEmpty();
    }

    @Test
    void a_borrower_whose_settings_differ_only_in_queue_scoped_values_is_not_warned() {
        var first  = ShardOwnerSettings.defaults();
        var second = ShardOwnerSettings.builder()
                                       .setAckBatchSize(first.ackBatchSize() + 1)
                                       .setHoleExpiry(first.holeExpiry().plusSeconds(1))
                                       .setShedGrace(first.shedGrace().plusSeconds(1))
                                       .setLeaseTtl(first.leaseTtl().plusSeconds(1))
                                       .setWatermarkCap(first.watermarkCap().plusSeconds(1))
                                       .setStuckHandlerTimeout(Duration.ofMinutes(2))
                                       .build();

        acquire(first);
        acquire(second);

        assertThat(settingsWarnings()).isEmpty();
    }

    @Test
    void a_borrower_asking_for_other_pump_threads_is_warned_naming_the_runtime_scoped_values() {
        var first  = ShardOwnerSettings.defaults();
        var second = ShardOwnerSettings.builder().setPumpThreads(first.pumpThreads() + 2).build();

        acquire(first);
        acquire(second);

        var warnings = settingsWarnings();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().getFormattedMessage())
                .contains("pumpThreads", "readBatchSize", "pollBackstop", "maxSweepInterval")
                .contains("pumpThreads=" + first.pumpThreads())
                .contains("pumpThreads=" + second.pumpThreads())
                .as("a value the runtime does not read is not named")
                .doesNotContain("stuckHandlerTimeout");
    }

    @Test
    void a_borrower_asking_for_each_runtime_scoped_value_is_warned() {
        var first = ShardOwnerSettings.defaults();
        var others = List.of(ShardOwnerSettings.builder().setReadBatchSize(first.readBatchSize() + 1).build(),
                             ShardOwnerSettings.builder().setPollBackstop(first.pollBackstop().plusMillis(1)).build(),
                             ShardOwnerSettings.builder().setMaxSweepInterval(first.maxSweepInterval().plusSeconds(1)).build());

        acquire(first);
        others.forEach(this::acquire);

        assertThat(settingsWarnings()).hasSize(others.size());
    }

    private ShardRuntime acquire(ShardOwnerSettings settings) {
        var runtime = ShardRuntime.acquireShared(dataSource, settings);
        borrowed++;
        return runtime;
    }

    private List<ILoggingEvent> settingsWarnings() {
        return logged.list.stream()
                          .filter(event -> event.getLevel() == Level.WARN)
                          .filter(event -> event.getFormattedMessage().contains("A shared ShardRuntime for this DataSource"))
                          .toList();
    }
}
