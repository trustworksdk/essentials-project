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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EventStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code essentials.event-log.enabled=true} drops the old starter's {@link EventStoreConfiguration} as a whole
 * ({@link EventLogImportFilter}). Every {@code @Bean} it declares is either declared again by this starter (same method
 * name) or listed in {@link #NOT_CARRIED_OVER} with the reason. A bean added to the old starter fails this test until
 * someone decides - which is how {@code annotationBasedInMemoryProjector} and the auto-flush interceptor went missing.
 */
class EventStoreConfigurationParityTest {
    private static final Map<String, String> NOT_CARRIED_OVER = Map.ofEntries(
            Map.entry("eventStoreSubscriptionManager", "the event log's subscriptions replace it; there is deliberately no adapter"),
            Map.entry("eventProcessorDependencies", "classic processors are refused (UnportedEventProcessorGuard); eventLogProcessorDependencies"),
            Map.entry("viewEventProcessorDependencies", "classic processors are refused (UnportedEventProcessorGuard); eventLogProcessorDependencies"),
            Map.entry("notifyEpochSource", "classic engine's subscription notifications"),
            Map.entry("eventStoreNotifyPollingBootstrap", "classic engine's subscription notifications"),
            Map.entry("durableSubscriptionRepository", "classic engine's subscription resume points; the event log keeps el_progress"),
            Map.entry("eventStorePersistenceStrategy", "classic engine's tables; EventLogEventStore appends to el_ tables"),
            Map.entry("eventStreamGapHandler", "the event log needs no gap handler (transaction-visibility watermark)"),
            Map.entry("eventStoreSubscriptionMonitorManager", "classic subscription monitoring; MicrometerEventLogObserver"),
            Map.entry("subscriberGlobalOrderMicrometerMonitor", "classic subscription monitoring; MicrometerEventLogObserver"),
            Map.entry("subscriptionStoppedMicrometerMonitor", "classic subscription monitoring; MicrometerEventLogObserver"),
            Map.entry("subscriptionStatisticsRegistry", "classic subscription statistics"),
            Map.entry("eventStoreSubscriptionObserver", "classic subscription observer; EventLogObserver"),
            Map.entry("subscriptionResetOnPoisonNotifier", "classic engine's CDC"),
            Map.entry("cdcEventStore", "classic engine's CDC"),
            Map.entry("cdcAvailability", "classic engine's CDC"),
            Map.entry("cdcDispatcher", "classic engine's CDC"),
            Map.entry("cdcEventBus", "classic engine's CDC"),
            Map.entry("logicalReplicationToPersistedEventConverter", "classic engine's CDC"),
            Map.entry("pgOutputToPersistedEventConverter", "classic engine's CDC"),
            Map.entry("walGlobalOrdersExtractor", "classic engine's CDC"),
            Map.entry("aggregateTypeResolver", "classic engine's CDC"),
            Map.entry("walMessageFilter", "classic engine's CDC"),
            Map.entry("cdcConsumerGroup", "classic engine's CDC"),
            Map.entry("logicalDecodingPlugin", "classic engine's CDC"),
            Map.entry("pgoutputLogicalDecodingPlugin", "classic engine's CDC"),
            Map.entry("cdcSlotNameProvider", "classic engine's CDC"),
            Map.entry("walReplicationTailer", "classic engine's CDC"),
            Map.entry("cdcInboxRepository", "classic engine's CDC"),
            Map.entry("cdcEffectivenessMonitor", "classic engine's CDC"),
            Map.entry("cdcSlotMetrics", "classic engine's CDC"),
            Map.entry("cdcApi", "classic engine's CDC admin"),
            Map.entry("cdcHealthIndicator", "classic engine's CDC"),
            Map.entry("wal2jsonLogicalDecodingPlugin", "classic engine's CDC"),
            Map.entry("eventStoreApi", "admin over the classic tables; the event log's admin is eventLogSubscriptionsApi"),
            Map.entry("postgresqlEventStoreStatisticsApi", "statistics over the classic tables"),
            Map.entry("essentialsEventStoreStatisticsTables", "statistics over the classic tables"),
            Map.entry("essentialsSubscriptionsStatisticsTables", "statistics over the classic tables"),
            Map.entry("essentialsCdcStatisticsTables", "statistics over the classic tables"),
            Map.entry("essentialsAggregatesStatisticsTables", "statistics over the classic tables"));

    private static Set<String> beanMethods(Class<?>... configurations) {
        return Arrays.stream(configurations)
                     .flatMap(type -> Stream.concat(Stream.of(type), Arrays.stream(type.getDeclaredClasses())))
                     .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                     .filter(method -> method.isAnnotationPresent(Bean.class))
                     .map(Method::getName)
                     .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void every_bean_of_the_old_event_store_configuration_is_declared_again_or_listed_as_not_carried_over() {
        var old     = beanMethods(EventStoreConfiguration.class);
        var carried = beanMethods(EventLogAutoConfiguration.class, EventLogAdminApiAutoConfiguration.class);

        var missing = new TreeSet<>(old);
        missing.removeAll(carried);
        missing.removeAll(NOT_CARRIED_OVER.keySet());

        assertThat(missing).as("old @Bean methods neither declared by the event-log starter nor listed in NOT_CARRIED_OVER").isEmpty();
        assertThat(NOT_CARRIED_OVER.keySet()).as("NOT_CARRIED_OVER entries the old configuration no longer declares").isSubsetOf(old);
    }
}
