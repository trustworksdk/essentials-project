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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.SubscriptionStoppedMicrometerMonitor.SUBSCRIPTION_STOPPED_METRIC;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionStoppedMicrometerMonitorTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("OrderProjection");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    private SimpleMeterRegistry           meterRegistry;
    private EventStoreSubscriptionManager subscriptionManager;
    private EventStoreSubscription        subscription;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        subscriptionManager = mock(EventStoreSubscriptionManager.class);
        subscription = mock(EventStoreSubscription.class);
        when(subscriptionManager.getSubscription(SUBSCRIBER_ID, AGGREGATE_TYPE)).thenReturn(Optional.of(subscription));
    }

    @Test
    void no_gauge_is_registered_before_the_subscription_is_monitored() {
        new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, null);

        assertThat(meterRegistry.find(SUBSCRIPTION_STOPPED_METRIC).gauge()).isNull();
    }

    @Test
    void gauge_follows_the_subscriptions_current_stopped_state() {
        var monitor = new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, null);

        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        assertThat(gaugeValue()).isEqualTo(0.0);

        when(subscription.isStoppedByErrorPolicy()).thenReturn(true);
        assertThat(gaugeValue()).isEqualTo(1.0);

        // Stays 1 for as long as the subscription is stopped - unlike the counter, nothing ages it out
        assertThat(gaugeValue()).isEqualTo(1.0);

        // Started again (resetFrom, fenced-lock hand-over, restart) - read on sampling, no monitoring round needed
        when(subscription.isStoppedByErrorPolicy()).thenReturn(false);
        assertThat(gaugeValue()).isEqualTo(0.0);
    }

    @Test
    void unsubscribed_subscription_reports_0() {
        var monitor = new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, null);
        when(subscription.isStoppedByErrorPolicy()).thenReturn(true);
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        assertThat(gaugeValue()).isEqualTo(1.0);

        when(subscriptionManager.getSubscription(SUBSCRIBER_ID, AGGREGATE_TYPE)).thenReturn(Optional.empty());

        assertThat(gaugeValue()).isEqualTo(0.0);
    }

    @Test
    void resubscribed_subscription_is_read_from_the_manager_not_from_the_instance_first_seen() {
        var monitor = new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, null);
        when(subscription.isStoppedByErrorPolicy()).thenReturn(true);
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);

        var resubscribed = mock(EventStoreSubscription.class);
        when(subscriptionManager.getSubscription(SUBSCRIBER_ID, AGGREGATE_TYPE)).thenReturn(Optional.of(resubscribed));

        assertThat(gaugeValue()).isEqualTo(0.0);
    }

    @Test
    void repeated_monitoring_registers_one_gauge_per_subscription() {
        var otherSubscriberId = SubscriberId.of("InvoiceProjection");
        when(subscriptionManager.getSubscription(otherSubscriberId, AGGREGATE_TYPE)).thenReturn(Optional.empty());
        var monitor = new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, null);

        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        monitor.monitor(otherSubscriberId, AGGREGATE_TYPE);

        assertThat(meterRegistry.find(SUBSCRIPTION_STOPPED_METRIC).gauges()).hasSize(2);
    }

    @Test
    void gauge_carries_the_same_tags_as_the_stopped_by_error_policy_counter_plus_module() {
        var monitor = new SubscriptionStoppedMicrometerMonitor(subscriptionManager, meterRegistry, "orders-module");

        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);

        var gauge = meterRegistry.get(SUBSCRIPTION_STOPPED_METRIC).gauge();
        assertThat(gauge.getId().getTags()).containsExactlyInAnyOrder(Tag.of("subscriber_id", SUBSCRIBER_ID.toString()),
                                                                       Tag.of("aggregate_type", AGGREGATE_TYPE.toString()),
                                                                       Tag.of("Module", "orders-module"));
    }

    @Test
    void without_a_meter_registry_monitoring_is_a_no_op() {
        var monitor = new SubscriptionStoppedMicrometerMonitor(subscriptionManager, null, null);

        assertThatCode(() -> monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE)).doesNotThrowAnyException();
        verifyNoInteractions(subscription);
    }

    @Test
    void requires_a_subscription_manager() {
        assertThatThrownBy(() -> new SubscriptionStoppedMicrometerMonitor(null, meterRegistry, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private double gaugeValue() {
        return meterRegistry.get(SUBSCRIPTION_STOPPED_METRIC)
                            .tag("subscriber_id", SUBSCRIBER_ID.toString())
                            .tag("aggregate_type", AGGREGATE_TYPE.toString())
                            .gauge()
                            .value();
    }
}
