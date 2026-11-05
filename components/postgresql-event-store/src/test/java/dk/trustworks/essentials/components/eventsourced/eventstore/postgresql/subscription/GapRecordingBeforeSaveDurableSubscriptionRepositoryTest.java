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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.components.foundation.lifecycle.ShutdownContext;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link GapRecordingBeforeSaveDurableSubscriptionRepository#saveResumePoints}: the delegate must never read a resume point
 * whose save window ({@link DeferredGapRecording#beforeResumePointSave}) is not open, also when the resume point was
 * unchanged when the wrapper looked and advanced afterwards; and a resume point whose gaps could not be recorded makes
 * the save fail, after the others were saved - so the stop, lock release and shutdown paths see the failure
 */
class GapRecordingBeforeSaveDurableSubscriptionRepositoryTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private static SubscriptionResumePoint persistedResumePoint(String subscriber) {
        return new SubscriptionResumePoint(SubscriberId.of(subscriber), ORDERS, GlobalEventOrder.of(10), OffsetDateTime.now(Clock.systemUTC()));
    }

    /**
     * Records which subscribers have a save window open; runs {@code onSave} inside each beforeResumePointSave call
     */
    private static final class FakeDeferredGapRecording implements DeferredGapRecording {
        final Set<SubscriberId>                  openWindows = new HashSet<>();
        final Map<SubscriberId, RuntimeException> failures    = new HashMap<>();
        Consumer<SubscriberId>                   onSave      = subscriberId -> {
        };

        @Override
        public void deferGapRecordingUntilResumePointSave(SubscriberId subscriberId, AggregateType aggregateType) {
        }

        @Override
        public ResumePointSave beforeResumePointSave(SubscriberId subscriberId, AggregateType aggregateType) {
            var failure = failures.get(subscriberId);
            if (failure != null) {
                throw failure;
            }
            openWindows.add(subscriberId);
            onSave.accept(subscriberId);
            return () -> openWindows.remove(subscriberId);
        }
    }

    @Test
    void a_resume_point_unchanged_when_checked_that_advances_before_the_delegate_reads_it_is_not_saved_without_its_window() {
        var gapRecording = new FakeDeferredGapRecording();
        var changed      = persistedResumePoint("changed");
        var unchanged    = persistedResumePoint("unchanged");
        changed.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        // Checked first, unchanged; then, while the next resume point's gaps are recorded, its subscription advances it
        gapRecording.onSave = subscriberId -> unchanged.advanceResumeFromAndIncluding(GlobalEventOrder.of(30));

        var savedWithoutWindow = new ArrayList<SubscriberId>();
        var delegate           = mock(DurableSubscriptionRepository.class);
        doAnswer(invocation -> {
            Collection<SubscriptionResumePoint> resumePoints = invocation.getArgument(0);
            resumePoints.stream()
                        .filter(SubscriptionResumePoint::isChanged)
                        .filter(resumePoint -> !gapRecording.openWindows.contains(resumePoint.getSubscriberId()))
                        .forEach(resumePoint -> savedWithoutWindow.add(resumePoint.getSubscriberId()));
            return null;
        }).when(delegate).saveResumePoints(any());

        new GapRecordingBeforeSaveDurableSubscriptionRepository(delegate, gapRecording).saveResumePoints(List.of(unchanged, changed));

        assertThat(savedWithoutWindow).isEmpty();
        assertThat(unchanged.isChanged()).as("left to the next save").isTrue();
        assertThat(gapRecording.openWindows).isEmpty();
    }

    @Test
    void a_resume_point_whose_gaps_could_not_be_recorded_fails_the_save_after_the_others_were_saved() {
        var gapRecording = new FakeDeferredGapRecording();
        var failing      = persistedResumePoint("failing");
        var saved        = persistedResumePoint("saved");
        failing.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        saved.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        gapRecording.failures.put(failing.getSubscriberId(), new IllegalStateException("recording failed", new IOException("Connection refused")));

        var handedToDelegate = new ArrayList<SubscriberId>();
        var delegate         = mock(DurableSubscriptionRepository.class);
        doAnswer(invocation -> {
            Collection<SubscriptionResumePoint> resumePoints = invocation.getArgument(0);
            resumePoints.forEach(resumePoint -> handedToDelegate.add(resumePoint.getSubscriberId()));
            return null;
        }).when(delegate).saveResumePoints(any());

        var repository = new GapRecordingBeforeSaveDurableSubscriptionRepository(delegate, gapRecording);
        var thrown = catchThrowable(() -> repository.saveResumePoints(List.of(failing, saved)));

        assertThat(thrown).isInstanceOf(GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException.class)
                          .hasMessageContaining("failing");
        assertThat(IOExceptionUtil.isIOException(thrown)).as("an unreachable database is classified as one").isTrue();
        assertThat(handedToDelegate).containsExactly(saved.getSubscriberId());
        assertThat(failing.isChanged()).isTrue();
        assertThat(gapRecording.openWindows).isEmpty();
    }

    @Test
    void a_failing_delegate_save_carries_the_gap_recording_failures_as_suppressed() {
        var gapRecording = new FakeDeferredGapRecording();
        var failing      = persistedResumePoint("failing");
        var saved        = persistedResumePoint("saved");
        failing.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        saved.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        gapRecording.failures.put(failing.getSubscriberId(), new IllegalStateException("recording failed"));
        var delegate = mock(DurableSubscriptionRepository.class);
        doThrow(new IllegalStateException("write failed")).when(delegate).saveResumePoints(any());

        var repository = new GapRecordingBeforeSaveDurableSubscriptionRepository(delegate, gapRecording);
        var thrown = catchThrowable(() -> repository.saveResumePoints(List.of(failing, saved)));

        assertThat(thrown).hasMessage("write failed");
        assertThat(thrown.getSuppressed()).singleElement().isInstanceOf(GapRecordingBeforeSaveDurableSubscriptionRepository.GapsNotRecordedException.class);
        assertThat(gapRecording.openWindows).isEmpty();
    }

    @Test
    void a_stop_whose_gaps_could_not_be_recorded_stops_settling_at_once() {
        var subscription = subscription();
        var resumePoint  = persistedResumePoint("subscriber");
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        var repository = repositoryFailingToRecordGaps(resumePoint);

        var started = System.nanoTime();
        assertThatCode(() -> subscription.persistResumePointUntilSettled(repository, resumePoint)).doesNotThrowAnyException();

        verify(repository, times(1)).saveResumePoints(any());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(AbstractEventStoreSubscription.RESUME_POINT_SETTLE_TIMEOUT);
    }

    @Test
    void during_shutdown_an_io_failure_to_record_the_gaps_marks_the_database_unreachable_and_stops_settling() {
        var subscription = subscription();
        var shutdown     = ShutdownContext.startingNow(Duration.ofSeconds(10));
        subscription.shutdownStarting(shutdown);
        var resumePoint = persistedResumePoint("subscriber");
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(20));
        var repository = repositoryFailingToRecordGaps(resumePoint);

        subscription.persistResumePointUntilSettled(repository, resumePoint);

        verify(repository, times(1)).saveResumePoints(any());
        assertThat(shutdown.isDatabaseUnreachable()).isTrue();
    }

    private static DurableSubscriptionRepository repositoryFailingToRecordGaps(SubscriptionResumePoint resumePoint) {
        var gapRecording = new FakeDeferredGapRecording();
        gapRecording.failures.put(resumePoint.getSubscriberId(), new IllegalStateException("recording failed", new IOException("Connection refused")));
        return spy(new GapRecordingBeforeSaveDurableSubscriptionRepository(mock(DurableSubscriptionRepository.class), gapRecording));
    }

    @SuppressWarnings("unchecked")
    private static AbstractEventStoreSubscription subscription() {
        var context = new EventStoreSubscriptionContext(mock(EventStore.class),
                                                        ORDERS,
                                                        SubscriberId.of("subscriber"),
                                                        null,
                                                        mock(EventStoreSubscriptionObserver.class),
                                                        mock(Consumer.class),
                                                        eventStreamLogName -> mock(EventStorePollingOptimizer.class));
        return mock(AbstractEventStoreSubscription.class,
                    withSettings().useConstructor(context).defaultAnswer(CALLS_REAL_METHODS));
    }
}
