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

package dk.trustworks.essentials.components.foundation.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * QL-3: the {@link RedeliveryPolicy} builders report every setting left out, naming the setter - and, in 0.60.x, still
 * build exactly what they built before (from 0.61 they will throw instead).
 */
class RedeliveryPolicyBuilderChecksTest {
    private static final Duration DELAY     = Duration.ofMillis(100);
    private static final Duration THRESHOLD = Duration.ofSeconds(5);

    @Test
    void a_complete_exponential_backoff_reports_nothing() {
        var builder = RedeliveryPolicy.exponentialBackoff()
                                      .setInitialRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelayMultiplier(2.0)
                                      .setMaximumFollowupRedeliveryDelayThreshold(THRESHOLD)
                                      .setMaximumNumberOfRedeliveries(5);

        assertThat(builder.checks().problems()).isEmpty();
    }

    @Test
    void an_exponential_backoff_without_multiplier_and_maximum_redeliveries_names_both_setters_and_still_builds_as_before() {
        var builder = RedeliveryPolicy.exponentialBackoff()
                                      .setInitialRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelay(DELAY)
                                      .setMaximumFollowupRedeliveryDelayThreshold(THRESHOLD);

        assertThat(builder.checks().problems())
                .hasSize(2)
                .anySatisfy(problem -> assertThat(problem).startsWith("setFollowupRedeliveryDelayMultiplier(...)"))
                .anySatisfy(problem -> assertThat(problem).startsWith("setMaximumNumberOfRedeliveries(...)"));

        var policy = builder.build();
        assertThat(policy.getMaximumNumberOfRedeliveries()).as("unchanged in 0.60.x: unset means 0").isZero();
        assertThat(policy.calculateNextRedeliveryDelay(3)).as("unchanged in 0.60.x: no growth").isEqualTo(DELAY);
    }

    @Test
    void a_multiplier_below_one_is_reported() {
        var builder = RedeliveryPolicy.exponentialBackoff()
                                      .setInitialRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelayMultiplier(0.5)
                                      .setMaximumFollowupRedeliveryDelayThreshold(THRESHOLD)
                                      .setMaximumNumberOfRedeliveries(5);

        assertThat(builder.checks().problems()).singleElement(as(STRING)).contains("0.5");
    }

    @Test
    void zero_redeliveries_set_explicitly_is_not_reported() {
        var builder = RedeliveryPolicy.fixedBackoff()
                                      .setRedeliveryDelay(DELAY)
                                      .setMaximumNumberOfRedeliveries(0);

        assertThat(builder.checks().problems()).isEmpty();
    }

    @Test
    void a_missing_delay_is_named_by_its_setter_and_build_still_fails_as_before() {
        var builder = RedeliveryPolicy.exponentialBackoff()
                                      .setFollowupRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelayMultiplier(2.0)
                                      .setMaximumFollowupRedeliveryDelayThreshold(THRESHOLD)
                                      .setMaximumNumberOfRedeliveries(5);

        assertThat(builder.checks().problems()).singleElement(as(STRING)).startsWith("setInitialRedeliveryDelay(...)");
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
                                          .hasMessageContaining("initialRedeliveryDelay");
    }

    @Test
    void the_generic_builder_runs_the_same_checks() {
        var builder = RedeliveryPolicy.builder()
                                      .setInitialRedeliveryDelay(DELAY)
                                      .setFollowupRedeliveryDelay(DELAY)
                                      .setMaximumFollowupRedeliveryDelayThreshold(THRESHOLD);

        assertThat(builder.checks().problems())
                .anySatisfy(problem -> assertThat(problem).startsWith("setFollowupRedeliveryDelayMultiplier(...)"))
                .anySatisfy(problem -> assertThat(problem).startsWith("setMaximumNumberOfRedeliveries(...)"));
    }

    @Test
    void a_fixed_backoff_without_maximum_redeliveries_is_reported_and_builds_as_before() {
        var builder = RedeliveryPolicy.fixedBackoff().setRedeliveryDelay(DELAY);

        assertThat(builder.checks().problems()).singleElement(as(STRING)).startsWith("setMaximumNumberOfRedeliveries(...)");
        assertThat(builder.build()).isEqualTo(RedeliveryPolicy.fixedBackoff(DELAY, 0));
    }

    @Test
    void a_linear_backoff_without_threshold_and_maximum_redeliveries_names_both_setters() {
        var builder = RedeliveryPolicy.linearBackoff().setRedeliveryDelay(DELAY);

        assertThat(builder.checks().problems())
                .hasSize(2)
                .anySatisfy(problem -> assertThat(problem).startsWith("setMaximumFollowupRedeliveryDelayThreshold(...)"))
                .anySatisfy(problem -> assertThat(problem).startsWith("setMaximumNumberOfRedeliveries(...)"));
    }

    @Test
    void the_builders_build_the_same_policies_as_before() {
        assertThat(RedeliveryPolicy.fixedBackoff().setRedeliveryDelay(DELAY).setMaximumNumberOfRedeliveries(3).build())
                .isEqualTo(RedeliveryPolicy.fixedBackoff(DELAY, 3));
        assertThat(RedeliveryPolicy.exponentialBackoff()
                                   .setInitialRedeliveryDelay(DELAY)
                                   .setFollowupRedeliveryDelay(DELAY)
                                   .setFollowupRedeliveryDelayMultiplier(2.0)
                                   .setMaximumFollowupRedeliveryDelayThreshold(THRESHOLD)
                                   .setMaximumNumberOfRedeliveries(3)
                                   .build())
                .isEqualTo(RedeliveryPolicy.exponentialBackoff(DELAY, DELAY, 2.0, THRESHOLD, 3));
    }
}
