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
package dk.trustworks.essentials.components.eventlog.observability.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.components.eventlog.observability.micrometer.MicrometerEventLogObserver.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MicrometerEventLogObserver} driven through its callbacks: the lag gauge, the counters and the page timer, tagged
 * per subscription, with the optional module tag.
 */
class MicrometerEventLogObserverTest {
    private static final String VIEW   = "orders-view";
    private static final String ORDERS = "Orders";

    private final SimpleMeterRegistry        registry = new SimpleMeterRegistry();
    private final MicrometerEventLogObserver observer = new MicrometerEventLogObserver(registry, "orders-module");

    private double lag(String subscriber) {
        return registry.get(LAG_METRIC).tag(SUBSCRIBER_TAG, subscriber).tag(AGGREGATE_TYPE_TAG, ORDERS).gauge().value();
    }

    @Test
    void a_standby_reports_no_lag_and_a_stopped_subscription_no_meters() {
        observer.onProgressSaved(VIEW, ORDERS, 5);
        observer.onReaderCursor(ORDERS, 10);
        assertThat(lag(VIEW)).isEqualTo(6.0);

        // Lost the lease: another node delivers now, so this one's frozen position must not read as a growing lag
        observer.onLeaseLost(VIEW, ORDERS, "fence moved");
        observer.onReaderCursor(ORDERS, 1_000);
        assertThat(lag(VIEW)).isZero();
        // Owns it again and saves: the lag is back
        observer.onProgressSaved(VIEW, ORDERS, 990);
        assertThat(lag(VIEW)).isEqualTo(11.0);
        observer.onLeaseSuspended(VIEW, ORDERS);
        assertThat(lag(VIEW)).isZero();

        observer.onStopped(VIEW, ORDERS);
        assertThat(registry.find(LAG_METRIC).tag(SUBSCRIBER_TAG, VIEW).gauges()).isEmpty();
        assertThat(registry.find(FAILED_METRIC).tag(SUBSCRIBER_TAG, VIEW).counters()).isEmpty();
        // Started again: fresh meters
        observer.onProgressSaved(VIEW, ORDERS, 1_001);
        assertThat(lag(VIEW)).isZero();
    }

    @Test
    void two_instances_of_one_subscriber_keep_their_shared_meters_until_the_last_one_stops() {
        // Instances of one subscriber on one type share their meters (same tags); the first stop used to remove them
        // under the instance still running, whose counters then restarted from zero
        observer.onStarted(VIEW, ORDERS);
        observer.onStarted(VIEW, ORDERS);
        observer.onError(VIEW, ORDERS, new IllegalStateException("boom"));

        observer.onStopped(VIEW, ORDERS);
        assertThat(registry.get(FAILED_METRIC).tag(SUBSCRIBER_TAG, VIEW).counter().count()).as("the other instance still runs").isEqualTo(1);

        observer.onStopped(VIEW, ORDERS);
        assertThat(registry.find(FAILED_METRIC).tag(SUBSCRIBER_TAG, VIEW).counters()).isEmpty();
    }

    @Test
    void a_lease_callback_after_the_stop_registers_no_meters_again() {
        // A renewal pass in flight when the subscription stops reports the loss after onStopped: it used to register
        // six meters that nothing removed
        observer.onStarted(VIEW, ORDERS);
        observer.onProgressSaved(VIEW, ORDERS, 5);
        observer.onStopped(VIEW, ORDERS);

        observer.onLeaseLost(VIEW, ORDERS, "fence moved");
        observer.onLeaseSuspended(VIEW, ORDERS);

        assertThat(registry.find(LAG_METRIC).tag(SUBSCRIBER_TAG, VIEW).gauges()).isEmpty();
        assertThat(registry.find(LEASE_LOST_METRIC).tag(SUBSCRIBER_TAG, VIEW).counters()).isEmpty();
    }

    @Test
    void the_oldest_candidate_age_is_a_time_gauge_per_aggregate_type_set_on_every_read() {
        observer.onRead(ORDERS, true, 1_000, 3, 1, 1, java.time.Duration.ofMillis(1500));
        var gauge = registry.get(OLDEST_CANDIDATE_AGE_METRIC).tag(AGGREGATE_TYPE_TAG, ORDERS).tag(MODULE_TAG_NAME, "orders-module").timeGauge();
        assertThat(gauge.value(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(1500);

        observer.onRead(ORDERS, false, 1_000, 0, 0, 0, java.time.Duration.ZERO);
        assertThat(gauge.value(java.util.concurrent.TimeUnit.MILLISECONDS)).as("no candidate waits").isZero();
        assertThat(registry.find(OLDEST_CANDIDATE_AGE_METRIC).timeGauges()).as("one gauge per type, registered once").hasSize(1);
    }

    @Test
    void the_lag_is_the_events_between_the_saved_resume_point_and_the_readers_cursor() {
        observer.onProgressSaved(VIEW, ORDERS, 1);
        // No read yet: the tail is unknown, which reads as no lag
        assertThat(lag(VIEW)).isZero();

        observer.onReaderCursor(ORDERS, 10);
        // Resuming from 1 with 10 read: events 1..10 are still to be delivered
        assertThat(lag(VIEW)).isEqualTo(10.0);

        observer.onProgressSaved(VIEW, ORDERS, 11);
        assertThat(lag(VIEW)).isZero();

        observer.onReaderCursor(ORDERS, 15);
        assertThat(lag(VIEW)).isEqualTo(5.0);

        // A resume point ahead of the cursor (a reset forward) is no lag, never negative
        observer.onProgressSaved(VIEW, ORDERS, 100);
        assertThat(lag(VIEW)).isZero();
    }

    @Test
    void two_subscriptions_on_one_type_share_the_cursor_and_keep_their_own_lag() {
        observer.onReaderCursor(ORDERS, 50);
        observer.onProgressSaved(VIEW, ORDERS, 41);
        observer.onProgressSaved("notifier", ORDERS, 51);

        assertThat(lag(VIEW)).isEqualTo(10.0);
        assertThat(lag("notifier")).isZero();
    }

    @Test
    void failures_parks_and_lost_leases_are_counted_per_subscription() {
        observer.onError(VIEW, ORDERS, new IllegalStateException("boom"));
        observer.onError(VIEW, ORDERS, new IllegalStateException("boom"));
        observer.onParked(VIEW, ORDERS, 7, null, 3);
        observer.onParked(VIEW, ORDERS, 9, 7L, 0);
        observer.onLeaseLost(VIEW, ORDERS, "fence");
        observer.onRenewalFailed(new IllegalStateException("no connection"));

        assertThat(registry.get(FAILED_METRIC).tag(SUBSCRIBER_TAG, VIEW).counter().count()).isEqualTo(2);
        assertThat(registry.get(PARKED_METRIC).tag(SUBSCRIBER_TAG, VIEW).counter().count()).isEqualTo(2);
        assertThat(registry.get(LEASE_LOST_METRIC).tag(SUBSCRIBER_TAG, VIEW).counter().count()).isEqualTo(1);
        assertThat(registry.get(RENEWAL_FAILED_METRIC).counter().count()).isEqualTo(1);
        assertThat(registry.get(FAILED_METRIC).counter().getId().getTag(MODULE_TAG_NAME)).isEqualTo("orders-module");
    }

    @Test
    void pages_are_timed_by_outcome() {
        observer.onPageCommitted(VIEW, ORDERS, 50, TimeUnit.MILLISECONDS.toNanos(4));
        observer.onPageCommitted(VIEW, ORDERS, 50, TimeUnit.MILLISECONDS.toNanos(6));
        observer.onPageFailed(VIEW, ORDERS, 50, TimeUnit.MILLISECONDS.toNanos(2), new IllegalStateException("boom"));

        var committed = registry.get(PAGE_METRIC).tag(SUBSCRIBER_TAG, VIEW).tag(OUTCOME_TAG, "committed").timer();
        assertThat(committed.count()).isEqualTo(2);
        assertThat(committed.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(10.0);
        assertThat(registry.get(PAGE_METRIC).tag(OUTCOME_TAG, "failed").timer().count()).isEqualTo(1);
    }

    @Test
    void the_meters_of_a_subscription_are_registered_once() {
        for (var i = 0; i < 100; i++) {
            observer.onPageCommitted(VIEW, ORDERS, 1, 1);
            observer.onProgressSaved(VIEW, ORDERS, i);
        }
        assertThat(registry.find(PAGE_METRIC).timers()).hasSize(2);
        assertThat(registry.find(LAG_METRIC).gauges()).hasSize(1);
    }

    @Test
    void without_a_module_tag_the_tag_is_omitted() {
        var plain = new MicrometerEventLogObserver(new SimpleMeterRegistry());
        plain.onError(VIEW, ORDERS, new IllegalStateException("boom"));
        assertThat(plain).hasToString("MicrometerEventLogObserver");
    }
}
