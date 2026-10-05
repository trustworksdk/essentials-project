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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionResumePointTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("TestSubscriber");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    private SubscriptionResumePoint resumePointAt(long globalEventOrder) {
        return new SubscriptionResumePoint(SUBSCRIBER_ID,
                                           AGGREGATE_TYPE,
                                           GlobalEventOrder.of(globalEventOrder),
                                           OffsetDateTime.now());
    }

    @Test
    void test_advance_moves_the_resume_point_forward() {
        var resumePoint = resumePointAt(100);

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(101));

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(101));
        assertThat(resumePoint.isChanged()).isTrue();
    }

    @Test
    void test_advance_ignores_a_lower_resume_point() {
        // Reproduces the out-of-order completion seen after a transient gap: events 101..212 are
        // handled, then the gap-filled event 174 completes last. Its resume point (175) must not
        // rewind the subscription and cause 38 events to be redelivered.
        var resumePoint = resumePointAt(100);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(213));

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(175));

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(213));
    }

    @Test
    void test_advance_to_the_same_resume_point_leaves_it_unchanged() {
        var resumePoint = resumePointAt(100);
        resumePoint.setLastUpdated(OffsetDateTime.now()); // resets the changed flag

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(100));

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(100));
        assertThat(resumePoint.isChanged()).isFalse();
    }

    @Test
    void test_set_can_still_move_the_resume_point_backwards() {
        // Subscription resets rely on this - only the advance-path is monotonic
        var resumePoint = resumePointAt(213);

        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(1));

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(1));
        assertThat(resumePoint.isChanged()).isTrue();
    }

    @Test
    void test_an_advance_during_an_in_flight_save_stays_unpersisted() {
        // The lost-update the value-based dirty tracking exists to prevent: a save binds 205, an
        // in-flight batch then advances the resume point to 213, and only afterwards does the save
        // commit and report back. Marking the point clean here would strand 213 - the periodic
        // snapshotter only saves resume points that isChanged(), so 206..212 would be redelivered.
        var resumePoint = resumePointAt(205);
        var boundValue  = resumePoint.getResumeFromAndIncluding();

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(213));
        resumePoint.markAsPersisted(boundValue, OffsetDateTime.now());

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(213));
        assertThat(resumePoint.isChanged()).as("213 was never written, so it still needs saving").isTrue();
    }

    @Test
    void test_marking_the_written_value_as_persisted_makes_it_clean() {
        var resumePoint = resumePointAt(205);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(213));

        resumePoint.markAsPersisted(GlobalEventOrder.of(213), OffsetDateTime.now());

        assertThat(resumePoint.isChanged()).isFalse();
    }

    @Test
    void test_a_newly_loaded_resume_point_is_in_sync_with_the_store() {
        assertThat(resumePointAt(100).isChanged()).isFalse();
    }

    @Test
    void test_unpersisted_advance_counts_forward_progress_since_the_last_persisted_value() {
        var resumePoint = resumePointAt(100);
        assertThat(resumePoint.unpersistedAdvance()).isZero();

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(150));
        assertThat(resumePoint.unpersistedAdvance()).isEqualTo(50);

        resumePoint.markAsPersisted(GlobalEventOrder.of(140), OffsetDateTime.now());
        assertThat(resumePoint.unpersistedAdvance()).isEqualTo(10);
    }

    @Test
    void test_unpersisted_advance_is_zero_after_a_backwards_reposition() {
        // A reset rewinds the resume point and saves it itself - the early save must not count it as progress
        var resumePoint = resumePointAt(100);

        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(10));

        assertThat(resumePoint.isChanged()).isTrue();
        assertThat(resumePoint.unpersistedAdvance()).isZero();
    }

    @Test
    void test_a_reposition_starts_a_new_epoch_and_advancing_does_not() {
        var resumePoint = resumePointAt(100);
        assertThat(resumePoint.getRepositionEpoch()).isZero();

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(150));
        assertThat(resumePoint.getRepositionEpoch()).isZero();

        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(10));
        assertThat(resumePoint.getRepositionEpoch()).isEqualTo(1);
        assertThat(resumePoint.snapshot()).isEqualTo(new SubscriptionResumePoint.Snapshot(GlobalEventOrder.of(10), 1));
    }

    @Test
    void test_a_reposition_to_the_stored_value_still_needs_saving() {
        // The store must learn the new epoch, or a save captured before the reset could still overwrite it
        var resumePoint = resumePointAt(100);

        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(100));

        assertThat(resumePoint.isChanged()).isTrue();
    }

    @Test
    void test_a_save_captured_before_a_reset_cannot_mark_the_reset_persisted() {
        // The race S6 in docs/subscription-improvements.md: a save binds 500, a reset to 10 is saved and marked,
        // then the older save's markAsPersisted arrives
        var resumePoint = resumePointAt(100);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(500));
        var staleSave = resumePoint.snapshot();

        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(10));
        resumePoint.markAsPersisted(resumePoint.snapshot(), OffsetDateTime.now());
        resumePoint.markAsPersisted(staleSave, OffsetDateTime.now());

        assertThat(resumePoint.isChanged()).isFalse();
        assertThat(resumePoint.snapshot()).isEqualTo(new SubscriptionResumePoint.Snapshot(GlobalEventOrder.of(10), 1));
    }

    @Test
    void test_a_save_captured_before_a_reset_may_finish_marking_after_the_reset_started() {
        // The stale save commits first and the reset is persisted afterwards - the reset's mark must win
        var resumePoint = resumePointAt(100);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(500));
        var staleSave = resumePoint.snapshot();
        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(10));

        resumePoint.markAsPersisted(staleSave, OffsetDateTime.now());
        assertThat(resumePoint.isChanged()).as("the reset is not persisted yet").isTrue();

        resumePoint.markAsPersisted(resumePoint.snapshot(), OffsetDateTime.now());
        assertThat(resumePoint.isChanged()).isFalse();
    }

    @Test
    void test_a_superseded_snapshot_is_not_retried_until_the_resume_point_advances() {
        var resumePoint = resumePointAt(100);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(150));

        resumePoint.markAsSuperseded(resumePoint.snapshot());
        assertThat(resumePoint.isChanged()).isFalse();

        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(151));
        assertThat(resumePoint.isChanged()).isTrue();
    }

    @Test
    void test_a_superseded_snapshot_older_than_this_instances_own_reposition_is_ignored() {
        var resumePoint = resumePointAt(100);
        resumePoint.advanceResumeFromAndIncluding(GlobalEventOrder.of(500));
        var staleSave = resumePoint.snapshot();
        resumePoint.setResumeFromAndIncluding(GlobalEventOrder.of(10));
        resumePoint.markAsPersisted(resumePoint.snapshot(), OffsetDateTime.now());

        resumePoint.markAsSuperseded(staleSave);

        assertThat(resumePoint.isChanged()).isFalse();
        assertThat(resumePoint.unpersistedAdvance()).isZero();
    }
}
