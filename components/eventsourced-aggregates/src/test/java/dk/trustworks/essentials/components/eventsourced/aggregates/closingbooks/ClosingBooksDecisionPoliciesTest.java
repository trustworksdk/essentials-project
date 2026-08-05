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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClosingBooksDecisionPoliciesTest {
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Accounts");

    @Test
    void from_legacy_policy_maps_true_to_close_and_open_next() {
        var policy = ClosingBooksDecisionPolicies.fromLegacyPolicy((ClosingBooksPolicy<String, String>) context -> true);

        var decision = policy.decide(context(ClosingBooksTriggerMode.EXPLICIT_COMMAND));

        assertThat(decision).isEqualTo(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT);
    }

    @Test
    void when_triggered_by_only_matches_the_configured_trigger_modes() {
        var policy = ClosingBooksDecisionPolicies.<String, String>whenTriggeredBy(ClosingBooksDecision.CLOSE_ONLY,
                                                                                  ClosingBooksTriggerMode.EXPLICIT_COMMAND);

        assertThat(policy.decide(context(ClosingBooksTriggerMode.EXPLICIT_COMMAND))).isEqualTo(ClosingBooksDecision.CLOSE_ONLY);
        assertThat(policy.decide(context(ClosingBooksTriggerMode.ON_ACCESS))).isEqualTo(ClosingBooksDecision.KEEP_OPEN);
    }

    @Test
    void all_of_requires_all_policies_to_match() {
        var policy = ClosingBooksDecisionPolicies.allOf(ClosingBooksDecisionPolicies.<String, String>keepOpen(),
                                                        ClosingBooksDecisionPolicies.<String, String>closeOnly(),
                                                        ClosingBooksDecisionPolicies.<String, String>closeAndOpenNext());

        var decision = policy.decide(context(ClosingBooksTriggerMode.ON_ACCESS));

        assertThat(decision).isEqualTo(ClosingBooksDecision.KEEP_OPEN);
    }

    @Test
    void any_of_returns_the_most_aggressive_non_keep_open_decision() {
        var policy = ClosingBooksDecisionPolicies.anyOf(ClosingBooksDecisionPolicies.<String, String>keepOpen(),
                                                        ClosingBooksDecisionPolicies.<String, String>closeOnly(),
                                                        ClosingBooksDecisionPolicies.<String, String>closeAndOpenNext());

        var decision = policy.decide(context(ClosingBooksTriggerMode.ON_ACCESS));

        assertThat(decision).isEqualTo(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT);
    }

    private ClosingBooksEvaluationContext<String, String> context(ClosingBooksTriggerMode triggerMode) {
        return new ClosingBooksEvaluationContext<>(AGGREGATE_TYPE,
                                                   new LogicalAggregateId<>("Account-123"),
                                                   new AggregateGeneration<>(AGGREGATE_TYPE,
                                                                             new LogicalAggregateId<>("Account-123"),
                                                                             1L,
                                                                             "Account-123#1",
                                                                             GenerationState.OPEN,
                                                                             OffsetDateTime.parse("2026-03-01T00:00:00Z"),
                                                                             Optional.empty()),
                                                   "aggregate",
                                                   triggerMode,
                                                   OffsetDateTime.parse("2026-03-29T00:00:00Z"));
    }
}
