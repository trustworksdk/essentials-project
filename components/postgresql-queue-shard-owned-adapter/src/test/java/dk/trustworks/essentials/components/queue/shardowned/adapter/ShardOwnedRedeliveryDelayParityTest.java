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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.time.Duration;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link RedeliveryPolicy} must produce the same waits on the shard-owned engine as on the engines whose consumer
 * calls {@link RedeliveryPolicy#calculateNextRedeliveryDelay(int)} itself.
 * <p>
 * The adapter used to map a policy onto the engine's own formula, {@code initialRedeliveryDelay × multiplier^(attempts-1)}:
 * {@code followupRedeliveryDelay} was dropped, and a linear policy ran as a constant delay.
 */
class ShardOwnedRedeliveryDelayParityTest {

    static Stream<Arguments> policies() {
        return Stream.of(Arguments.of("exponential", RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(500), Duration.ofMillis(500), 2.0d, Duration.ofMinutes(1), 12)),
                         Arguments.of("exponential with a distinct follow-up", RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(100), Duration.ofSeconds(1), 1.5d, Duration.ofSeconds(20), 12)),
                         Arguments.of("exponential with multiplier 1.0", RedeliveryPolicy.exponentialBackoff(Duration.ofMillis(200), Duration.ofMillis(700), 1.0d, Duration.ofSeconds(3), 6)),
                         Arguments.of("linear", RedeliveryPolicy.linearBackoff(Duration.ofMillis(150), Duration.ofSeconds(1), 20)),
                         Arguments.of("fixed", RedeliveryPolicy.fixedBackoff(Duration.ofMillis(250), 5)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("policies")
    void the_engine_waits_what_the_policy_calculates_before_every_retry(String description, RedeliveryPolicy policy) {
        var engineOptions = ShardOwnedDurableQueueConsumer.toConsumerOptions(consumeFromQueueWith(policy));
        var enginePolicy  = dk.trustworks.essentials.components.queue.shardowned.RedeliveryPolicy.from(engineOptions);

        // The engine passes the deliveries made so far (1 after the first failure); the other engines' consumers
        // pass the message's redelivery attempts (0 after the first failure). The engine dead-letters once
        // maxAttempts deliveries have been made, so the last wait it asks for is after maximumNumberOfRedeliveries.
        var engineWaits = IntStream.rangeClosed(1, policy.maximumNumberOfRedeliveries)
                                   .mapToObj(enginePolicy::delayAfter)
                                   .toList();
        var policyWaits = IntStream.range(0, policy.maximumNumberOfRedeliveries)
                                   .mapToObj(policy::calculateNextRedeliveryDelay)
                                   .toList();

        assertThat(engineWaits).isEqualTo(policyWaits);
        assertThat(enginePolicy.isExhausted(policy.maximumNumberOfRedeliveries)).isFalse();
        assertThat(enginePolicy.isExhausted(policy.maximumNumberOfRedeliveries + 1)).isTrue();
    }

    private static ConsumeFromQueue consumeFromQueueWith(RedeliveryPolicy policy) {
        return ConsumeFromQueue.builder()
                               .setQueueName(QueueName.of("parity"))
                               .setRedeliveryPolicy(policy)
                               .setParallelConsumers(1)
                               .setQueueMessageHandler(message -> { })
                               .build();
    }
}
