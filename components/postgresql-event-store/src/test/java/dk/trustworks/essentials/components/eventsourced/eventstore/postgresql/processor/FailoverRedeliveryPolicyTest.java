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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailoverRedeliveryPolicy}: retries for {@value FailoverRedeliveryPolicy#SAFETY_FACTOR} times (lock timeout + queue
 * window), each defaulting when unknown, starting quick and doubling to a cap - and what it reports as its total is what
 * the {@code RedeliveryPolicy} will actually wait.
 */
class FailoverRedeliveryPolicyTest {

    @Test
    void with_unknown_timeouts_it_outlasts_four_times_the_defaults() {
        var failover = FailoverRedeliveryPolicy.outlasting(Optional.empty(), Optional.empty());

        assertThat(failover.lockTimeOut()).isEqualTo(Duration.ofSeconds(15));
        assertThat(failover.queueWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(failover.retriesFor()).isGreaterThanOrEqualTo(Duration.ofSeconds(180));
        // 0.2 + 0.2 + 0.4 + ... + 6.4 = 12.8 s in 7 redeliveries, then 10 s each: 24 in all
        assertThat(failover.policy().getMaximumNumberOfRedeliveries()).isEqualTo(24);
    }

    @Test
    void it_derives_from_the_timeouts_it_is_given() {
        var failover = FailoverRedeliveryPolicy.outlasting(Optional.of(Duration.ofSeconds(5)), Optional.of(Duration.ofSeconds(10)));

        assertThat(failover.retriesFor()).isGreaterThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(failover.retriesFor()).isLessThan(Duration.ofSeconds(70));
    }

    @Test
    void the_reported_total_is_what_the_redelivery_policy_waits() {
        var failover = FailoverRedeliveryPolicy.outlasting(Optional.of(Duration.ofSeconds(15)), Optional.of(Duration.ofSeconds(30)));

        var waited = Duration.ZERO;
        for (var redelivery = 0; redelivery < failover.policy().getMaximumNumberOfRedeliveries(); redelivery++) {
            waited = waited.plus(failover.policy().calculateNextRedeliveryDelay(redelivery));
        }
        assertThat(waited).isEqualTo(failover.retriesFor());
        assertThat(failover.policy().calculateNextRedeliveryDelay(0)).isEqualTo(Duration.ofMillis(200));
        assertThat(failover.policy().calculateNextRedeliveryDelay(20)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void it_describes_itself_for_the_start_up_log() {
        assertThat(FailoverRedeliveryPolicy.outlasting(Optional.empty(), Optional.empty()).describe())
                .contains("24 redeliveries")
                .contains("lock timeout PT15S")
                .contains("queue window PT30S");
    }
}
