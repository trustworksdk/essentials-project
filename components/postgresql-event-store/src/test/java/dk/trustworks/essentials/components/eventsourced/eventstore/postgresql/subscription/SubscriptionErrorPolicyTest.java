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
    void only_the_retrying_modes_retry() {
        assertThat(SubscriptionErrorPolicy.retryThenSkip(4).retriesBeforeGivingUp()).isEqualTo(4);
        assertThat(SubscriptionErrorPolicy.retryThenSkip(4).stopsOnError()).isFalse();
        assertThat(SubscriptionErrorPolicy.retryThenStop(4).retriesBeforeGivingUp()).isEqualTo(4);
        assertThat(SubscriptionErrorPolicy.stop().retriesBeforeGivingUp()).isZero();
        assertThat(SubscriptionErrorPolicy.stop().stopsOnError()).isTrue();
        // STOP has always ignored maxRetries - honouring it would silently start retrying where STOP was configured
        assertThat(new SubscriptionErrorPolicy(SubscriptionErrorPolicy.Mode.STOP, 5, Duration.ZERO, Duration.ZERO).retriesBeforeGivingUp()).isZero();
    }

    @Test
    void retry_then_stop_retries_and_then_stops() {
        var policy = SubscriptionErrorPolicy.retryThenStop(3, Duration.ofMillis(50), Duration.ofSeconds(2));
        assertThat(policy.mode()).isEqualTo(SubscriptionErrorPolicy.Mode.RETRY_N_THEN_STOP);
        assertThat(policy.maxRetries()).isEqualTo(3);
        assertThat(policy.retriesBeforeGivingUp()).isEqualTo(3);
        assertThat(policy.stopsOnError()).isTrue();
        assertThat(policy.initialBackoff()).isEqualTo(Duration.ofMillis(50));
        assertThat(policy.maxBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffBeforeRetry(2)).isEqualTo(Duration.ofMillis(100));

        var withDefaults = SubscriptionErrorPolicy.retryThenStop(2);
        assertThat(withDefaults.initialBackoff()).isEqualTo(SubscriptionErrorPolicy.DEFAULT_INITIAL_BACKOFF);
        assertThat(withDefaults.maxBackoff()).isEqualTo(SubscriptionErrorPolicy.DEFAULT_MAX_BACKOFF);
    }

    @Test
    void the_modes_say_whether_they_retry_and_whether_they_stop() {
        assertThat(SubscriptionErrorPolicy.Mode.SKIP.retries()).isFalse();
        assertThat(SubscriptionErrorPolicy.Mode.SKIP.stops()).isFalse();
        assertThat(SubscriptionErrorPolicy.Mode.RETRY_N_THEN_SKIP.retries()).isTrue();
        assertThat(SubscriptionErrorPolicy.Mode.RETRY_N_THEN_SKIP.stops()).isFalse();
        assertThat(SubscriptionErrorPolicy.Mode.STOP.retries()).isFalse();
        assertThat(SubscriptionErrorPolicy.Mode.STOP.stops()).isTrue();
        assertThat(SubscriptionErrorPolicy.Mode.RETRY_N_THEN_STOP.retries()).isTrue();
        assertThat(SubscriptionErrorPolicy.Mode.RETRY_N_THEN_STOP.stops()).isTrue();
    }

    @Test
    void an_invalid_retry_then_stop_policy_is_rejected() {
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenStop(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenStop(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenStop(1, Duration.ofSeconds(2), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenStop(1, Duration.ofMillis(-1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenStop(1, null, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscriptionErrorPolicy.retryThenStop(1, Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionErrorPolicy(SubscriptionErrorPolicy.Mode.RETRY_N_THEN_STOP, 0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
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
