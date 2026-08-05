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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateSnapshotPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TestConfiguration.class));

    @Test
    void binds_snapshot_properties_and_per_aggregate_overrides() {
        contextRunner
                .withPropertyValues(
                        "essentials.eventstore.snapshots.enabled=true",
                        "essentials.eventstore.snapshots.snapshot-table-name=custom_snapshots",
                        "essentials.eventstore.snapshots.default-mode=async-durable",
                        "essentials.eventstore.snapshots.default-every-n-events=42",
                        "essentials.eventstore.snapshots.default-deletion-mode=keep-last-n",
                        "essentials.eventstore.snapshots.default-keep-last-snapshots=4",
                        "essentials.eventstore.snapshots.durable.enabled=false",
                        "essentials.eventstore.snapshots.durable.job-table-name=custom_snapshot_jobs",
                        "essentials.eventstore.snapshots.durable.poll-interval=3s",
                        "essentials.eventstore.snapshots.durable.batch-size=12",
                        "essentials.eventstore.snapshots.durable.worker-threads=5",
                        "essentials.eventstore.snapshots.durable.max-retries=8",
                        "essentials.eventstore.snapshots.durable.retry-delay=9s",
                        "essentials.eventstore.snapshots.aggregates.Orders.enabled=true",
                        "essentials.eventstore.snapshots.aggregates.Orders.mode=async-in-memory",
                        "essentials.eventstore.snapshots.aggregates.Orders.every-n-events=21",
                        "essentials.eventstore.snapshots.aggregates.Orders.deletion-mode=delete-all-historic",
                        "essentials.eventstore.snapshots.aggregates.Orders.keep-last-snapshots=6"
                )
                .run(context -> {
                    var properties = context.getBean(EssentialsEventStoreProperties.class);
                    var snapshots = properties.getSnapshots();

                    assertThat(snapshots.isEnabled()).isTrue();
                    assertThat(snapshots.getSnapshotTableName()).isEqualTo("custom_snapshots");
                    assertThat(snapshots.getDefaultMode()).isEqualTo(SnapshotExecutionMode.ASYNC_DURABLE);
                    assertThat(snapshots.getDefaultEveryNEvents()).isEqualTo(42);
                    assertThat(snapshots.getDefaultDeletionMode()).isEqualTo(SnapshotDeletionMode.KEEP_LAST_N);
                    assertThat(snapshots.getDefaultKeepLastSnapshots()).isEqualTo(4);

                    assertThat(snapshots.getDurable().isEnabled()).isFalse();
                    assertThat(snapshots.getDurable().getJobTableName()).isEqualTo("custom_snapshot_jobs");
                    assertThat(snapshots.getDurable().getPollInterval()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(snapshots.getDurable().getBatchSize()).isEqualTo(12);
                    assertThat(snapshots.getDurable().getWorkerThreads()).isEqualTo(5);
                    assertThat(snapshots.getDurable().getMaxRetries()).isEqualTo(8);
                    assertThat(snapshots.getDurable().getRetryDelay()).isEqualTo(Duration.ofSeconds(9));

                    var orderPolicy = snapshots.getAggregates().get("Orders");
                    assertThat(orderPolicy).isNotNull();
                    assertThat(orderPolicy.getEnabled()).isTrue();
                    assertThat(orderPolicy.getMode()).isEqualTo(SnapshotExecutionMode.ASYNC_IN_MEMORY);
                    assertThat(orderPolicy.getEveryNEvents()).isEqualTo(21);
                    assertThat(orderPolicy.getDeletionMode()).isEqualTo(SnapshotDeletionMode.DELETE_ALL_HISTORIC);
                    assertThat(orderPolicy.getKeepLastSnapshots()).isEqualTo(6);
                });
    }

    @Configuration
    @EnableConfigurationProperties(EssentialsEventStoreProperties.class)
    static class TestConfiguration {
    }
}
