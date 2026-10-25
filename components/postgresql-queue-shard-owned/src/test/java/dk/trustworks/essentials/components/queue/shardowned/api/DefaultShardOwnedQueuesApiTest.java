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
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * How {@link DefaultShardOwnedQueuesApi} reports a {@link MessageQueue#releaseKeptOrderedUnit} that threw, without a
 * database: the queue is a stand-in that throws the {@link SQLException} it is given. The rest of the API is covered
 * against a real database by {@code ShardOwnedQueuesApiIT}.
 */
class DefaultShardOwnedQueuesApiTest {

    private static final QueueName QUEUE = QueueName.of("api-kept-unit");

    @Test
    void a_release_on_request_that_gave_up_waiting_for_another_release_of_the_units_row_is_reported_as_safe_to_ask_again() {
        var timedOut = new SQLException("a release of its lease row held its claim", KeptUnitRelease.RELEASE_CLAIM_WAIT_TIMED_OUT_SQL_STATE);
        var api      = apiOverAQueueThrowing(timedOut);

        assertThatThrownBy(() -> api.releaseKeptOrderedUnit(new Object(), QUEUE, "instance-1", 3))
                .isInstanceOf(MessageQueueException.class)
                .hasMessageContaining("Failed to release kept ordered unit 3 of instance 'instance-1' of queue 'api-kept-unit'")
                .hasMessageContaining("SQL state 55P03")
                .hasMessageContaining("released nothing")
                .hasMessageContaining("Asking again is safe")
                .cause().isSameAs(timedOut);
    }

    @Test
    void a_release_on_request_that_failed_otherwise_is_not_reported_as_safe_to_ask_again() {
        var lost = new SQLException("connection lost", "08006");
        var api  = apiOverAQueueThrowing(lost);

        assertThatThrownBy(() -> api.releaseKeptOrderedUnit(new Object(), QUEUE, "instance-1", 3))
                .isInstanceOf(MessageQueueException.class)
                .hasMessage("Failed to release kept ordered unit 3 of instance 'instance-1' of queue 'api-kept-unit'")
                .cause().isSameAs(lost);
    }

    /**
     * The API over a registry holding {@link #QUEUE} alone, whose {@link MessageQueue#releaseKeptOrderedUnit} throws
     * {@code failure}; every principal is allowed.
     */
    // MessageQueue's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static ShardOwnedQueuesApi apiOverAQueueThrowing(SQLException failure) {
        var queue = (MessageQueue) Proxy.newProxyInstance(MessageQueue.class.getClassLoader(), new Class<?>[]{MessageQueue.class},
                                                          (proxy, method, args) -> {
                                                              if ("releaseKeptOrderedUnit".equals(method.getName())) {
                                                                  throw failure;
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
