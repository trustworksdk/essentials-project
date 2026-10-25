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
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interface default of {@link DurableSubscriptionRepository#getOrCreateResumePoint} - what a custom repository
 * that does not override it gets
 */
class DurableSubscriptionRepositoryDefaultGetOrCreateTest {
    private static final SubscriberId  SUBSCRIBER_ID = SubscriberId.of("sub-1");
    private static final AggregateType ORDERS        = AggregateType.of("Orders");

    @Test
    void returns_the_resume_point_another_caller_stored_between_the_get_and_the_create() {
        var repository = new InMemoryRepository();
        var storedByOtherCaller = new SubscriptionResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(5), OffsetDateTime.now());
        // The other caller stores its resume point after this caller's get found none
        repository.beforeCreate = () -> repository.store(storedByOtherCaller);

        var resumePoint = repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(7));

        assertThat(resumePoint).isSameAs(storedByOtherCaller);
        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(5));
        assertThat(repository.createAttempts).isEqualTo(1);
    }

    @Test
    void creates_the_resume_point_when_none_exists() {
        var repository = new InMemoryRepository();

        var resumePoint = repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(7));

        assertThat(resumePoint.getResumeFromAndIncluding()).isEqualTo(GlobalEventOrder.of(7));
        assertThat(repository.getResumePoint(SUBSCRIBER_ID, ORDERS)).containsSame(resumePoint);
    }

    @Test
    void returns_the_existing_resume_point_without_creating() {
        var repository = new InMemoryRepository();
        var existing   = new SubscriptionResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(3), OffsetDateTime.now());
        repository.store(existing);

        assertThat(repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(7))).isSameAs(existing);
        assertThat(repository.createAttempts).isZero();
    }

    @Test
    void rethrows_the_create_failure_when_no_resume_point_can_be_read_afterwards() {
        var repository = new InMemoryRepository();
        repository.createFailsWithoutStoring = true;

        assertThatThrownBy(() -> repository.getOrCreateResumePoint(SUBSCRIBER_ID, ORDERS, GlobalEventOrder.of(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
    }

    /**
     * A store that keeps the documented {@link DurableSubscriptionRepository#createResumePoint} contract - it throws
     * {@link IllegalStateException} when the resume point already exists - and overrides nothing else
     */
    private static final class InMemoryRepository implements DurableSubscriptionRepository {
        private final Map<Pair<SubscriberId, AggregateType>, SubscriptionResumePoint> resumePoints = new ConcurrentHashMap<>();
        Runnable beforeCreate              = () -> {};
        boolean  createFailsWithoutStoring = false;
        int      createAttempts            = 0;

        void store(SubscriptionResumePoint resumePoint) {
            resumePoints.put(Pair.of(resumePoint.getSubscriberId(), resumePoint.getAggregateType()), resumePoint);
        }

        @Override
        public SubscriptionResumePoint createResumePoint(SubscriberId subscriberId, AggregateType forAggregateType, GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder) {
            createAttempts++;
            beforeCreate.run();
            if (createFailsWithoutStoring) {
                throw new IllegalStateException("Create refused");
            }
            var created  = new SubscriptionResumePoint(subscriberId, forAggregateType, onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder, OffsetDateTime.now());
            var existing = resumePoints.putIfAbsent(Pair.of(subscriberId, forAggregateType), created);
            if (existing != null) {
                throw new IllegalStateException("Cannot createResumePoint since a ResumePoint already exists");
            }
            return created;
        }

        @Override
        public Optional<SubscriptionResumePoint> getResumePoint(SubscriberId subscriberId, AggregateType forAggregateType) {
            return Optional.ofNullable(resumePoints.get(Pair.of(subscriberId, forAggregateType)));
        }

        @Override
        public void deleteResumePoint(SubscriberId subscriberId, AggregateType aggregateType) {
            resumePoints.remove(Pair.of(subscriberId, aggregateType));
        }

        @Override
        public void saveResumePoints(Collection<SubscriptionResumePoint> resumePoints) {
            resumePoints.forEach(this::store);
        }

        @Override
        public List<SubscriptionResumePoint> findAllResumePoints() {
            return List.copyOf(resumePoints.values());
        }

        @Override
        public List<SubscriptionResumePoint> findAllResumePoints(long startIndex, long pageSize) {
            return findAllResumePoints().stream().skip(startIndex).limit(pageSize).toList();
        }
    }
}
