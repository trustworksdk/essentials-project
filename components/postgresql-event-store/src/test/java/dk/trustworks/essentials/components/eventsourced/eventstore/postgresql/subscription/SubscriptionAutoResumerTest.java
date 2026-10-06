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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.*;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SubscriptionAutoResumerTest {
    private static final GlobalEventOrder AT    = GlobalEventOrder.of(7);
    private static final GlobalEventOrder OTHER = GlobalEventOrder.of(9);

    private VirtualTimeScheduler    scheduler;
    private EventStoreSubscription  subscription;
    private AtomicBoolean           shuttingDown;
    private SubscriptionAutoResumer autoResumer;

    @BeforeEach
    void setup() {
        scheduler = VirtualTimeScheduler.create();
        subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(SubscriberId.of("Subscriber"));
        when(subscription.aggregateType()).thenReturn(AggregateType.of("Orders"));
        when(subscription.resumeIfStoppedByErrorPolicy()).thenReturn(true);
        shuttingDown = new AtomicBoolean();
        autoResumer = new SubscriptionAutoResumer(subscription, shuttingDown::get, scheduler);
    }

    @AfterEach
    void cleanup() {
        scheduler.dispose();
    }

    private static SubscriptionErrorPolicy policy(SubscriptionErrorPolicy.AutoResume autoResume) {
        return SubscriptionErrorPolicy.stop().withAutoResume(autoResume);
    }

    @Test
    void a_stop_is_resumed_after_the_initial_delay() {
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(60))));
        assertThat(autoResumer.isResumePending()).isTrue();

        scheduler.advanceTimeBy(Duration.ofSeconds(9));
        verify(subscription, never()).resumeIfStoppedByErrorPolicy();

        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(subscription, times(1)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.isResumePending()).isFalse();
        assertThat(autoResumer.resumesAt(AT)).isEqualTo(1);
    }

    @Test
    void the_delay_doubles_with_every_stop_at_the_same_event_and_starts_over_at_another_event() {
        var policy = policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(25)));
        autoResumer.stoppedAt(AT, policy);
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        verify(subscription, times(1)).resumeIfStoppedByErrorPolicy();

        // Stopped at the same event again: 20 s
        autoResumer.stoppedAt(AT, policy);
        scheduler.advanceTimeBy(Duration.ofSeconds(19));
        verify(subscription, times(1)).resumeIfStoppedByErrorPolicy();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(subscription, times(2)).resumeIfStoppedByErrorPolicy();

        // And again: capped at 25 s
        autoResumer.stoppedAt(AT, policy);
        scheduler.advanceTimeBy(Duration.ofSeconds(24));
        verify(subscription, times(2)).resumeIfStoppedByErrorPolicy();
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(subscription, times(3)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.resumesAt(AT)).isEqualTo(3);

        // Another event: back to 10 s
        autoResumer.stoppedAt(OTHER, policy);
        assertThat(autoResumer.resumesAt(OTHER)).isZero();
        scheduler.advanceTimeBy(Duration.ofSeconds(10));
        verify(subscription, times(4)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.resumesAt(OTHER)).isEqualTo(1);
    }

    @Test
    void with_max_attempts_the_event_is_skipped_once_it_has_been_resumed_that_many_times() {
        var policy = policy(SubscriptionErrorPolicy.AutoResume.skippingAfter(2, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isFalse();

        autoResumer.stoppedAt(AT, policy);
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isFalse();

        autoResumer.stoppedAt(AT, policy);
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(subscription, times(2)).resumeIfStoppedByErrorPolicy();
        // Resumed twice at the event - the next give-up skips it
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isTrue();
        // Only that event
        assertThat(autoResumer.skipInsteadOfStopping(OTHER, policy)).isFalse();
    }

    @Test
    void without_max_attempts_an_event_is_never_skipped() {
        var policy = policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(1)));
        for (int i = 0; i < 50; i++) {
            autoResumer.stoppedAt(AT, policy);
            scheduler.advanceTimeBy(Duration.ofSeconds(1));
            assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isFalse();
        }
        verify(subscription, times(50)).resumeIfStoppedByErrorPolicy();
    }

    @Test
    void a_disabled_auto_resume_schedules_nothing_and_never_skips() {
        var policy = SubscriptionErrorPolicy.stop().withoutAutoResume();
        autoResumer.stoppedAt(AT, policy);
        assertThat(autoResumer.isResumePending()).isFalse();
        scheduler.advanceTimeBy(Duration.ofHours(1));
        verify(subscription, never()).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isFalse();
    }

    @Test
    void a_cancelled_resume_never_runs() {
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(10))));
        autoResumer.cancel();
        assertThat(autoResumer.isResumePending()).isFalse();
        scheduler.advanceTimeBy(Duration.ofMinutes(1));
        verify(subscription, never()).resumeIfStoppedByErrorPolicy();
    }

    @Test
    void nothing_is_resumed_once_the_application_is_shutting_down() {
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(10))));
        shuttingDown.set(true);
        scheduler.advanceTimeBy(Duration.ofMinutes(1));
        verify(subscription, never()).resumeIfStoppedByErrorPolicy();

        // Nor scheduled
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(10))));
        assertThat(autoResumer.isResumePending()).isFalse();
    }

    @Test
    void a_reset_forgets_the_count() {
        var policy = policy(SubscriptionErrorPolicy.AutoResume.skippingAfter(1, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        autoResumer.stoppedAt(AT, policy);
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isTrue();

        autoResumer.reset();
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isFalse();
        assertThat(autoResumer.resumesAt(AT)).isZero();
    }

    @Test
    void a_stop_awaits_recovery_until_the_subscriber_is_done_with_the_failed_event_or_a_later_one() {
        assertThat(autoResumer.isAwaitingRecovery()).isFalse();
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(1))));
        assertThat(autoResumer.isAwaitingRecovery()).isTrue();

        // Resumed: still retrying the failed event
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(subscription, times(1)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.isAwaitingRecovery()).isTrue();

        // An older gap fill handled out of order says nothing about the failed event
        autoResumer.movedPast(GlobalEventOrder.of(AT.longValue() - 1));
        assertThat(autoResumer.isAwaitingRecovery()).isTrue();

        autoResumer.movedPast(AT);
        assertThat(autoResumer.isAwaitingRecovery()).isFalse();

        // A later event counts too - e.g. the last event of a batch that started at the failed one
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(1))));
        autoResumer.movedPast(OTHER);
        assertThat(autoResumer.isAwaitingRecovery()).isFalse();
    }

    @Test
    void without_auto_resume_a_stop_still_awaits_recovery_so_a_resume_by_hand_is_not_reported_as_recovered_too_early() {
        autoResumer.stoppedAt(AT, SubscriptionErrorPolicy.stop().withoutAutoResume());
        assertThat(autoResumer.isAwaitingRecovery()).isTrue();
        autoResumer.movedPast(AT);
        assertThat(autoResumer.isAwaitingRecovery()).isFalse();
    }

    @Test
    void a_resume_by_hand_cancels_the_pending_resume_but_still_awaits_recovery() {
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(10))));
        autoResumer.cancel();
        assertThat(autoResumer.isResumePending()).isFalse();
        assertThat(autoResumer.isAwaitingRecovery()).isTrue();
    }

    @Test
    void a_stopped_subscription_no_longer_awaits_recovery_and_its_pending_resume_never_runs() {
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(10))));
        autoResumer.subscriptionStopped();
        assertThat(autoResumer.isResumePending()).isFalse();
        assertThat(autoResumer.isAwaitingRecovery()).isFalse();
        scheduler.advanceTimeBy(Duration.ofMinutes(1));
        verify(subscription, never()).resumeIfStoppedByErrorPolicy();
    }

    @Test
    void a_reset_no_longer_awaits_recovery() {
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(10), Duration.ofSeconds(10))));
        autoResumer.reset();
        assertThat(autoResumer.isAwaitingRecovery()).isFalse();
    }

    @Test
    void a_resume_that_throws_is_tried_again_later() {
        when(subscription.resumeIfStoppedByErrorPolicy()).thenThrow(new IllegalStateException("Database unreachable"))
                                                         .thenReturn(true);
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(10))));
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        verify(subscription, times(1)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.isResumePending()).isTrue();

        // A resume that threw is no attempt at the event
        assertThat(autoResumer.resumesAt(AT)).isZero();

        // The next try waits twice as long
        scheduler.advanceTimeBy(Duration.ofSeconds(2));
        verify(subscription, times(2)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.isResumePending()).isFalse();
        assertThat(autoResumer.resumesAt(AT)).isEqualTo(1);
    }

    @Test
    void resumes_that_throw_do_not_use_up_max_attempts() {
        when(subscription.resumeIfStoppedByErrorPolicy()).thenThrow(new IllegalStateException("Database unreachable"))
                                                         .thenThrow(new IllegalStateException("Database unreachable"))
                                                         .thenThrow(new IllegalStateException("Database unreachable"))
                                                         .thenReturn(true);
        var policy = policy(SubscriptionErrorPolicy.AutoResume.skippingAfter(1, Duration.ofSeconds(1), Duration.ofSeconds(8)));
        autoResumer.stoppedAt(AT, policy);

        // Three resumes throw, backing off 1, 2 and 4 s - none of them counts
        scheduler.advanceTimeBy(Duration.ofSeconds(1 + 2 + 4));
        verify(subscription, times(3)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.resumesAt(AT)).isZero();
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isFalse();

        // The fourth goes through: the one attempt is used, so the next give-up skips
        scheduler.advanceTimeBy(Duration.ofSeconds(8));
        verify(subscription, times(4)).resumeIfStoppedByErrorPolicy();
        assertThat(autoResumer.resumesAt(AT)).isEqualTo(1);
        assertThat(autoResumer.skipInsteadOfStopping(AT, policy)).isTrue();

        // And the wait for the next stop at the event is back to a resume's own backoff - 2 s for the second resume
        autoResumer.stoppedAt(AT, policy(SubscriptionErrorPolicy.AutoResume.unlimited(Duration.ofSeconds(1), Duration.ofSeconds(8))));
        scheduler.advanceTimeBy(Duration.ofMillis(1999));
        verify(subscription, times(4)).resumeIfStoppedByErrorPolicy();
        scheduler.advanceTimeBy(Duration.ofMillis(1));
        verify(subscription, times(5)).resumeIfStoppedByErrorPolicy();
    }
}
