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

package dk.trustworks.essentials.components.queue.shardowned.api;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * How {@link DefaultShardOwnedQueuesApi} reports what {@link MessageQueue#releaseKeptOrderedUnit} answered or threw,
 * without a database: the queue is a stand-in that answers, or throws, what it is given. The rest of the API is covered
 * against a real database by {@code ShardOwnedQueuesApiIT}.
 */
class DefaultShardOwnedQueuesApiTest {

    private static final QueueName QUEUE = QueueName.of("api-kept-unit");

    @Test
    void a_release_on_request_that_gave_up_waiting_for_another_release_of_the_units_row_is_answered_with_its_outcome_not_thrown() {
        var timedOut = new KeptUnitRelease(3, "instance-1", KeptUnitRelease.Outcome.CLAIM_WAIT_TIMED_OUT, List.of("ACC-1"),
                                           Duration.ofMillis(1500), 0, List.of());
        var api = apiOverAQueue(new KeptUnitReleases(List.of("instance-1"), List.of(timedOut)), null);

        var answer = api.releaseKeptOrderedUnit(new Object(), QUEUE, "instance-1", 3);

        assertThat(answer.answeredBy()).containsExactly("instance-1");
        assertThat(answer.releases()).singleElement().satisfies(release -> {
            assertThat(release.outcome()).isEqualTo("CLAIM_WAIT_TIMED_OUT");
            assertThat(release.queueName()).isEqualTo(QUEUE);
            assertThat(release.unit()).isEqualTo(3);
            assertThat(release.keysInHandler()).containsExactly("ACC-1");
            assertThat(release.charged()).as("nothing charged").isZero();
            assertThat(release.deadLetteredKeys()).isEmpty();
        });
    }

    @Test
    void a_release_on_request_whose_statement_gave_up_waiting_for_a_lock_on_the_database_is_thrown() {
        // PostgreSQL's lock_timeout: the same SQL state the engine's own claim wait once carried, still a failure
        var lockTimeout = new SQLException("canceling statement due to lock timeout", "55P03");
        var api         = apiOverAQueue(null, lockTimeout);

        assertThatThrownBy(() -> api.releaseKeptOrderedUnit(new Object(), QUEUE, "instance-1", 3))
                .isInstanceOf(MessageQueueException.class)
                .hasMessage("Failed to release kept ordered unit 3 of instance 'instance-1' of queue 'api-kept-unit'")
                .cause().isSameAs(lockTimeout);
    }

    @Test
    void a_release_on_request_that_failed_otherwise_is_thrown() {
        var lost = new SQLException("connection lost", "08006");
        var api  = apiOverAQueue(null, lost);

        assertThatThrownBy(() -> api.releaseKeptOrderedUnit(new Object(), QUEUE, "instance-1", 3))
                .isInstanceOf(MessageQueueException.class)
                .hasMessage("Failed to release kept ordered unit 3 of instance 'instance-1' of queue 'api-kept-unit'")
                .cause().isSameAs(lost);
    }

    /**
     * The API over a registry holding {@link #QUEUE} alone, whose {@link MessageQueue#releaseKeptOrderedUnit} throws
     * {@code failure} when it is given, and answers {@code answer} otherwise; every principal is allowed.
     */
    private static ShardOwnedQueuesApi apiOverAQueue(KeptUnitReleases answer, SQLException failure) {
        var queue = (MessageQueue) Proxy.newProxyInstance(MessageQueue.class.getClassLoader(), new Class<?>[]{MessageQueue.class},
                                                          (proxy, method, args) -> {
                                                              if ("releaseKeptOrderedUnit".equals(method.getName())) {
                                                                  if (failure != null) {
                                                                      throw failure;
                                                                  }
                                                                  return answer;
                                                              }
                                                              throw new UnsupportedOperationException(method.getName());
                                                          });
        var queues = new MessageQueues() {
            @Override
            public List<QueueName> queueNames() {
                return List.of(QUEUE);
            }

            @Override
            public Optional<MessageQueue> findQueue(QueueName queueName) {
                return QUEUE.equals(queueName) ? Optional.of(queue) : Optional.empty();
            }
        };
        var allowEverything = new EssentialsSecurityProvider() {
            @Override
            public boolean isAllowed(Object principal, String requiredRole) {
                return true;
            }

            @Override
            public Optional<String> getPrincipalName(Object principal) {
                return Optional.of("test");
            }
        };
        return new DefaultShardOwnedQueuesApi(allowEverything, queues);
    }
}
