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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateLifecycleConfigurationValidatorTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnapshotConfiguration.class, ClosingBooksConfiguration.class));

    @Test
    void startup_succeeds_when_the_same_aggregate_enables_both_snapshotting_and_closing_books() {
        contextRunner
                .withPropertyValues("essentials.eventstore.snapshots.enabled=true")
                .withBean(ConflictingAggregate.class)
                .withBean(ConflictingAggregateNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_fails_when_scheduled_closing_books_is_enabled_without_a_lock_manager() {
        contextRunner
                .withBean(ScheduledClosingBooksAggregate.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("no FencedLockManager is configured");
                });
    }

    @Test
    void startup_succeeds_when_closing_books_is_on_access_and_snapshotting_is_disabled() {
        contextRunner
                .withBean(OnAccessClosingBooksAggregate.class)
                .withBean(OnAccessClosingBooksAggregateNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_fails_when_automatic_close_and_open_policy_has_no_next_generation_factory() {
        contextRunner
                .withBean(AutoRolloverAggregateWithoutFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("no TypedClosingBooksNextGenerationFactory is registered");
                });
    }

    @AggregateSnapshotPolicy(aggregateType = "Orders", enabled = true)
    @AggregateClosingBooksPolicy(aggregateType = "Orders", enabled = true)
    static class ConflictingAggregate {
    }

    @AggregateClosingBooksPolicy(aggregateType = "Accounts", enabled = true, triggerMode = ClosingBooksTriggerMode.SCHEDULED_SCAN)
    static class ScheduledClosingBooksAggregate {
    }

    @AggregateClosingBooksPolicy(aggregateType = "Customers", enabled = true, triggerMode = ClosingBooksTriggerMode.ON_ACCESS)
    static class OnAccessClosingBooksAggregate {
    }

    @AggregateClosingBooksPolicy(aggregateType = "Invoices",
                                 enabled = true,
                                 triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.EVENT_COUNT,
                                 eventThreshold = 10)
    static class AutoRolloverAggregateWithoutFactory {
    }

    static class ConflictingAggregateNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<String, String, ConflictingAggregate, String> {
        @Override
        public Class<ConflictingAggregate> aggregateImplementationType() {
            return ConflictingAggregate.class;
        }

        @Override
        public ConflictingAggregate createNextGeneration(ConflictingAggregate currentAggregate,
                                                         ClosingBooksAggregateInstantiationContext<String, String> context,
                                                         String hint) {
            return new ConflictingAggregate();
        }
    }

    static class OnAccessClosingBooksAggregateNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<String, String, OnAccessClosingBooksAggregate, String> {
        @Override
        public Class<OnAccessClosingBooksAggregate> aggregateImplementationType() {
            return OnAccessClosingBooksAggregate.class;
        }

        @Override
        public OnAccessClosingBooksAggregate createNextGeneration(OnAccessClosingBooksAggregate currentAggregate,
                                                                  ClosingBooksAggregateInstantiationContext<String, String> context,
                                                                  String hint) {
            return new OnAccessClosingBooksAggregate();
        }
    }
}
