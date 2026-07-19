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
}
