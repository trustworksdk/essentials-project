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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DurableAsyncSnapshotManagerTest {
    @Test
    void start_polls_processor_and_stop_is_idempotent() {
        var processor = mock(PostgresqlAggregateSnapshotJobProcessor.class);
        var manager = new DurableAsyncSnapshotManager(processor,
                                                      new DurableAsyncSnapshotSettings(Duration.ofMillis(10), 25, 1, 3, Duration.ofSeconds(5)));

        manager.start();
        manager.start();

        Awaitility.waitAtMost(Duration.ofSeconds(2))
                  .untilAsserted(() -> verify(processor, atLeastOnce()).processNextBatch(any()));

        manager.stop();
        manager.stop();

        assertThat(manager.isStarted()).isFalse();
    }
}
