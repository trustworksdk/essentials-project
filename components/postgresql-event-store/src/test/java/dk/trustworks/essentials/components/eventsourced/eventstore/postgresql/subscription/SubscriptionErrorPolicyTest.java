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

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class SubscriptionErrorPolicyTest {

    @Test
    void the_default_everywhere_is_skip() {
        assertThat(SubscriptionErrorPolicy.skip().mode()).isEqualTo(SubscriptionErrorPolicy.Mode.SKIP);
        assertThat(SubscriptionErrorPolicy.skip().retriesBeforeGivingUp()).isZero();
        assertThat(new EventStoreSubscriptionManagerSettings(10, Duration.ofMillis(100), Duration.ofSeconds(1)).subscriptionErrorPolicy())
                .isEqualTo(SubscriptionErrorPolicy.skip());
    }

    @Test
    void only_retry_n_then_skip_retries() {
        assertThat(SubscriptionErrorPolicy.retryThenSkip(4).retriesBeforeGivingUp()).isEqualTo(4);
        assertThat(SubscriptionErrorPolicy.stop().retriesBeforeGivingUp()).isZero();
        assertThat(SubscriptionErrorPolicy.stop().stopsOnError()).isTrue();
        assertThat(new SubscriptionErrorPolicy(SubscriptionErrorPolicy.Mode.STOP, 5, Duration.ZERO, Duration.ZERO).retriesBeforeGivingUp()).isZero();
    }

    @Test
    void the_backoff_doubles_up_to_the_max() {
        var policy = SubscriptionErrorPolicy.retryThenSkip(10, Duration.ofMillis(100), Duration.ofMillis(500));
        assertThat(policy.backoffBeforeRetry(1)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoffBeforeRetry(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoffBeforeRetry(3)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.backoffBeforeRetry(4)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.backoffBeforeRetry(Integer.MAX_VALUE)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void an_invalid_policy_is_rejected() {
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenSkip(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenSkip(1, Duration.ofSeconds(2), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenSkip(1, Duration.ofMillis(-1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionErrorPolicy(null, 0, Duration.ZERO, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
