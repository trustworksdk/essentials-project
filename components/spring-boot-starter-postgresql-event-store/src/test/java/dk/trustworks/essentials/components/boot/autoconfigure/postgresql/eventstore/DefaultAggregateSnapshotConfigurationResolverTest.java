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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAggregateSnapshotConfigurationResolverTest {

    @Test
    void annotation_values_override_global_defaults_when_feature_globally_enabled() {
        var properties = new EssentialsEventStoreProperties();
        properties.getSnapshots().setEnabled(true);
        properties.getSnapshots().setDefaultMode(SnapshotExecutionMode.SYNC);
        properties.getSnapshots().setDefaultEveryNEvents(10);
        properties.getSnapshots().setDefaultDeletionMode(SnapshotDeletionMode.DELETE_ALL_HISTORIC);
        properties.getSnapshots().setDefaultKeepLastSnapshots(1);

        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        registry.register(new AggregateSnapshotPolicyDescriptor(AnnotatedAggregate.class,
                                                               Optional.of("Orders"),
                                                               AnnotatedAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));

        var resolver = new DefaultAggregateSnapshotConfigurationResolver(properties, registry);

        var resolved = resolver.resolve(AggregateType.of("Orders"), AnnotatedAggregate.class);

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.mode()).isEqualTo(SnapshotExecutionMode.ASYNC_DURABLE);
        assertThat(resolved.everyNEvents()).isEqualTo(100);
        assertThat(resolved.deletionMode()).isEqualTo(SnapshotDeletionMode.KEEP_LAST_N);
        assertThat(resolved.keepLastSnapshots()).isEqualTo(2);
    }

    @Test
    void global_disabled_overrides_annotation_enabled_default() {
        var properties = new EssentialsEventStoreProperties();
        properties.getSnapshots().setEnabled(false);

        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        registry.register(new AggregateSnapshotPolicyDescriptor(AnnotatedAggregate.class,
                                                               Optional.of("Orders"),
                                                               AnnotatedAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));

        var resolver = new DefaultAggregateSnapshotConfigurationResolver(properties, registry);

        var resolved = resolver.resolve(AggregateType.of("Orders"), AnnotatedAggregate.class);

        assertThat(resolved.enabled()).isFalse();
    }

    @Test
    void per_aggregate_override_can_re_enable_when_global_is_disabled() {
        var properties = new EssentialsEventStoreProperties();
        properties.getSnapshots().setEnabled(false);
        var aggregateOverride = new EssentialsEventStoreProperties.AggregateSnapshotPolicyProperties();
        aggregateOverride.setEnabled(true);
        properties.getSnapshots().getAggregates().put("Orders", aggregateOverride);

        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        registry.register(new AggregateSnapshotPolicyDescriptor(AnnotatedAggregate.class,
                                                               Optional.of("Orders"),
                                                               AnnotatedAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));

        var resolver = new DefaultAggregateSnapshotConfigurationResolver(properties, registry);

        var resolved = resolver.resolve(AggregateType.of("Orders"), AnnotatedAggregate.class);

        assertThat(resolved.enabled()).isTrue();
    }

    @Test
    void per_aggregate_properties_override_annotation_defaults() {
        var properties = new EssentialsEventStoreProperties();
        var aggregateOverride = new EssentialsEventStoreProperties.AggregateSnapshotPolicyProperties();
        aggregateOverride.setEnabled(false);
        aggregateOverride.setMode(SnapshotExecutionMode.ASYNC_IN_MEMORY);
        aggregateOverride.setEveryNEvents(25);
        aggregateOverride.setDeletionMode(SnapshotDeletionMode.DELETE_ALL_HISTORIC);
        aggregateOverride.setKeepLastSnapshots(7);
        properties.getSnapshots().getAggregates().put("Orders", aggregateOverride);

        var registry = new InMemoryAggregateSnapshotPolicyRegistry();
        registry.register(new AggregateSnapshotPolicyDescriptor(AnnotatedAggregate.class,
                                                               Optional.of("Orders"),
                                                               AnnotatedAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));

        var resolver = new DefaultAggregateSnapshotConfigurationResolver(properties, registry);

        var resolved = resolver.resolve(AggregateType.of("Orders"), AnnotatedAggregate.class);

        assertThat(resolved.enabled()).isFalse();
        assertThat(resolved.mode()).isEqualTo(SnapshotExecutionMode.ASYNC_IN_MEMORY);
        assertThat(resolved.everyNEvents()).isEqualTo(25);
        assertThat(resolved.deletionMode()).isEqualTo(SnapshotDeletionMode.DELETE_ALL_HISTORIC);
        assertThat(resolved.keepLastSnapshots()).isEqualTo(7);
    }

    @Test
    void falls_back_to_global_defaults_for_non_annotated_aggregates() {
        var properties = new EssentialsEventStoreProperties();
        properties.getSnapshots().setEnabled(true);
        properties.getSnapshots().setDefaultMode(SnapshotExecutionMode.ASYNC_IN_MEMORY);
        properties.getSnapshots().setDefaultEveryNEvents(75);
        properties.getSnapshots().setDefaultDeletionMode(SnapshotDeletionMode.KEEP_LAST_N);
        properties.getSnapshots().setDefaultKeepLastSnapshots(3);

        var resolver = new DefaultAggregateSnapshotConfigurationResolver(properties, new InMemoryAggregateSnapshotPolicyRegistry());

        var resolved = resolver.resolve(AggregateType.of("Accounts"), PlainAggregate.class);

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.mode()).isEqualTo(SnapshotExecutionMode.ASYNC_IN_MEMORY);
        assertThat(resolved.everyNEvents()).isEqualTo(75);
        assertThat(resolved.deletionMode()).isEqualTo(SnapshotDeletionMode.KEEP_LAST_N);
        assertThat(resolved.keepLastSnapshots()).isEqualTo(3);
    }

    @AggregateSnapshotPolicy(
            aggregateType = "Orders",
            mode = SnapshotExecutionMode.ASYNC_DURABLE,
            everyNEvents = 100,
            deletionMode = SnapshotDeletionMode.KEEP_LAST_N,
            keepLastSnapshots = 2
    )
    private static final class AnnotatedAggregate {
    }

    private static final class PlainAggregate {
    }
}
