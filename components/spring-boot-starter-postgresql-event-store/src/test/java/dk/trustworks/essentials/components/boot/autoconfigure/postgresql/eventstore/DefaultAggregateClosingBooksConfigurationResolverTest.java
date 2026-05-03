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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAggregateClosingBooksConfigurationResolverTest {
    @Test
    void resolves_configuration_using_aggregate_override_before_annotation_and_global_defaults() {
        var properties = new EssentialsEventStoreProperties();
        properties.getClosingBooks().setEnabled(false);
        properties.getClosingBooks().setDefaultTriggerMode(ClosingBooksTriggerMode.ON_ACCESS);
        properties.getClosingBooks().setDefaultPolicy(ClosingBooksDefaultPolicyType.MANUAL_ONLY);
        var override = new EssentialsEventStoreProperties.AggregateClosingBooksPolicyProperties();
        override.setEnabled(true);
        override.setTriggerMode(ClosingBooksTriggerMode.SCHEDULED_SCAN);
        override.setDefaultPolicy(ClosingBooksDefaultPolicyType.TIME_BOUNDARY);
        override.setEventThreshold(250L);
        override.setTimeBoundary(ClosingBooksTimeBoundary.END_OF_MONTH);
        override.setZoneId("Europe/Copenhagen");
        properties.getClosingBooks().getAggregates().put("Orders", override);

        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        registry.register(new AggregateClosingBooksPolicyDescriptor(OrdersAggregate.class,
                                                                   Optional.of("Orders"),
                                                                   OrdersAggregate.class.getAnnotation(AggregateClosingBooksPolicy.class)));

        var resolved = new DefaultAggregateClosingBooksConfigurationResolver(properties, registry)
                .resolve(AggregateType.of("Orders"), OrdersAggregate.class);

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.triggerMode()).isEqualTo(ClosingBooksTriggerMode.SCHEDULED_SCAN);
        assertThat(resolved.defaultPolicy()).isEqualTo(ClosingBooksDefaultPolicyType.TIME_BOUNDARY);
        assertThat(resolved.eventThreshold()).isEqualTo(250L);
        assertThat(resolved.timeBoundary()).isEqualTo(ClosingBooksTimeBoundary.END_OF_MONTH);
        assertThat(resolved.zoneId()).isEqualTo("Europe/Copenhagen");
    }

    @Test
    void resolves_annotation_defaults_when_no_property_override_exists() {
        var properties = new EssentialsEventStoreProperties();
        properties.getClosingBooks().setEnabled(false);
        properties.getClosingBooks().setDefaultTriggerMode(ClosingBooksTriggerMode.ON_ACCESS);
        properties.getClosingBooks().setDefaultPolicy(ClosingBooksDefaultPolicyType.MANUAL_ONLY);

        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        registry.register(new AggregateClosingBooksPolicyDescriptor(OrdersAggregate.class,
                                                                   Optional.of("Orders"),
                                                                   OrdersAggregate.class.getAnnotation(AggregateClosingBooksPolicy.class)));

        var resolved = new DefaultAggregateClosingBooksConfigurationResolver(properties, registry)
                .resolve(AggregateType.of("Orders"), OrdersAggregate.class);

        assertThat(resolved.enabled()).isFalse();
        assertThat(resolved.triggerMode()).isEqualTo(ClosingBooksTriggerMode.EXPLICIT_COMMAND);
        assertThat(resolved.defaultPolicy()).isEqualTo(ClosingBooksDefaultPolicyType.EVENT_COUNT);
        assertThat(resolved.eventThreshold()).isEqualTo(42L);
        assertThat(resolved.timeBoundary()).isEqualTo(ClosingBooksTimeBoundary.END_OF_MONTH);
        assertThat(resolved.zoneId()).isEqualTo("Europe/Copenhagen");
    }

    @Test
    void global_disabled_overrides_annotation_enabled_default() {
        var properties = new EssentialsEventStoreProperties();
        properties.getClosingBooks().setEnabled(false);

        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        registry.register(new AggregateClosingBooksPolicyDescriptor(EnabledByAnnotationAggregate.class,
                                                                    Optional.of("Invoices"),
                                                                    EnabledByAnnotationAggregate.class.getAnnotation(AggregateClosingBooksPolicy.class)));

        var resolved = new DefaultAggregateClosingBooksConfigurationResolver(properties, registry)
                .resolve(AggregateType.of("Invoices"), EnabledByAnnotationAggregate.class);

        assertThat(resolved.enabled()).isFalse();
    }

    @Test
    void per_aggregate_override_can_re_enable_when_global_is_disabled() {
        var properties = new EssentialsEventStoreProperties();
        properties.getClosingBooks().setEnabled(false);
        var override = new EssentialsEventStoreProperties.AggregateClosingBooksPolicyProperties();
        override.setEnabled(true);
        properties.getClosingBooks().getAggregates().put("Invoices", override);

        var registry = new InMemoryAggregateClosingBooksPolicyRegistry();
        registry.register(new AggregateClosingBooksPolicyDescriptor(EnabledByAnnotationAggregate.class,
                                                                    Optional.of("Invoices"),
                                                                    EnabledByAnnotationAggregate.class.getAnnotation(AggregateClosingBooksPolicy.class)));

        var resolved = new DefaultAggregateClosingBooksConfigurationResolver(properties, registry)
                .resolve(AggregateType.of("Invoices"), EnabledByAnnotationAggregate.class);

        assertThat(resolved.enabled()).isTrue();
    }

    @AggregateClosingBooksPolicy(aggregateType = "Orders",
                                 enabled = false,
                                 triggerMode = ClosingBooksTriggerMode.EXPLICIT_COMMAND,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT,
                                 eventThreshold = 42,
                                 timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH,
                                 zoneId = "Europe/Copenhagen")
    static class OrdersAggregate {
    }

    @AggregateClosingBooksPolicy(aggregateType = "Invoices",
                                 triggerMode = ClosingBooksTriggerMode.SCHEDULED_SCAN)
    static class EnabledByAnnotationAggregate {
    }
}
