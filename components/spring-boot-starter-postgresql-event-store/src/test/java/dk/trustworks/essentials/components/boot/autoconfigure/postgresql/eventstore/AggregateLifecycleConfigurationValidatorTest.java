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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
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
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(ScheduledClosingBooksAggregate.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("no FencedLockManager is configured");
                });
    }

    @Test
    void startup_succeeds_when_closing_books_is_on_access_and_snapshotting_is_disabled() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
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
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(AutoRolloverAggregateWithoutFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("no TypedClosingBooksNextGenerationFactory is registered");
                });
    }

    @Test
    void global_closing_books_kill_switch_skips_validation_for_annotated_aggregates() {
        // Closing-books is globally disabled (default). The aggregate carries
        // @AggregateClosingBooksPolicy(SCHEDULED_SCAN, enabled=true) which would otherwise
        // require a FencedLockManager. The global kill switch should beat the annotation
        // default and let startup succeed.
        contextRunner
                .withBean(ScheduledClosingBooksAggregate.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_fails_when_zone_id_is_invalid() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.closing-books.aggregates.Customers.zone-id=Atlantis/Lemuria")
                .withBean(OnAccessClosingBooksAggregate.class)
                .withBean(OnAccessClosingBooksAggregateNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure()).hasMessageContaining("invalid zoneId 'Atlantis/Lemuria'");
                });
    }

    @Test
    void warns_when_closing_books_annotation_is_silenced_by_global_kill_switch(CapturedOutput output) {
        // Default: closing-books.enabled=false. Aggregate has @AggregateClosingBooksPolicy(enabled=true).
        // The kill switch silences the annotation; the validator should log a WARN naming the
        // properties to set in order to re-enable.
        contextRunner
                .withBean(OnAccessClosingBooksAggregate.class)
                .withBean(OnAccessClosingBooksAggregateNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(output.getAll())
                            .contains("@AggregateClosingBooksPolicy(enabled=true)")
                            .contains("essentials.eventstore.closing-books.enabled=true")
                            .contains("essentials.eventstore.closing-books.aggregates.Customers.enabled=true");
                });
    }

    @Test
    void warns_when_snapshot_annotation_is_silenced_by_global_kill_switch(CapturedOutput output) {
        // Default: snapshots.enabled=false. Aggregate has @AggregateSnapshotPolicy(enabled=true).
        contextRunner
                .withBean(SnapshotAnnotatedAggregate.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(output.getAll())
                            .contains("@AggregateSnapshotPolicy(enabled=true)")
                            .contains("essentials.eventstore.snapshots.enabled=true")
                            .contains("essentials.eventstore.snapshots.aggregates.Subscriptions.enabled=true");
                });
    }

    @Test
    void startup_succeeds_when_event_threshold_is_defaulted_for_event_count_policy() {
        // EVENT_COUNT policy with no explicit threshold anywhere; resolver substitutes the default
        // and the validator emits a WARN (we don't assert log output here, only that startup is OK).
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.closing-books.default-policy=EVENT_COUNT")
                .withBean(OnAccessClosingBooksAggregate.class)
                .withBean(OnAccessClosingBooksAggregateNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_fails_when_a_time_boundary_policy_resolves_to_no_time_boundary() {
        // TIME_BOUNDARY policy but timeBoundary is left at its NONE default: BuiltInClosingBooksPolicyEvaluator
        // would report advancedPeriods=0 forever, so the books would never close.
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(TimeBoundaryAggregateWithoutBoundary.class)
                .withBean(TimeBoundaryAggregateWithoutBoundaryNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("the resolved time boundary is NONE")
                            .hasMessageContaining("essentials.eventstore.closing-books.aggregates.Vouchers.time-boundary");
                });
    }

    @Test
    void startup_fails_when_an_event_count_or_time_boundary_policy_resolves_to_no_time_boundary() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.closing-books.aggregates.Ledgers.default-policy=EVENT_COUNT_OR_TIME_BOUNDARY",
                                    "essentials.eventstore.closing-books.aggregates.Ledgers.time-boundary=NONE")
                .withBean(TimeBoundaryAggregateWithoutPeriodId.class)
                .withBean(TimeBoundaryAggregateWithoutPeriodIdNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("the resolved time boundary is NONE")
                            .hasMessageContaining("use policy 'EVENT_COUNT' if only the event-count condition was intended");
                });
    }

    @Test
    void startup_succeeds_when_a_time_boundary_is_supplied_through_properties() {
        // The annotation leaves timeBoundary at NONE; the property supplies it.
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.closing-books.aggregates.Vouchers.time-boundary=END_OF_WEEK")
                .withBean(TimeBoundaryAggregateWithoutBoundary.class)
                .withBean(TimeBoundaryAggregateWithoutBoundaryNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_fails_when_a_time_boundary_policy_aggregate_cannot_expose_its_period_id() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(TimeBoundaryAggregateWithoutPeriodId.class)
                .withBean(TimeBoundaryAggregateWithoutPeriodIdNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    assertThat(ctx.getStartupFailure())
                            .hasMessageContaining("does not implement")
                            .hasMessageContaining(HasClosingBooksPeriodId.class.getName())
                            .hasMessageContaining("essentials.eventstore.closing-books.aggregates.Ledgers.period-id-provided-externally=true");
                });
    }

    @Test
    void startup_succeeds_when_a_time_boundary_policy_aggregate_implements_has_closing_books_period_id() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(TimeBoundaryAggregateWithPeriodId.class)
                .withBean(TimeBoundaryAggregateWithPeriodIdNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_succeeds_when_the_period_id_is_declared_as_provided_externally() {
        // The aggregate supplies its period id through a custom currentPeriodIdProvider instead of
        // implementing HasClosingBooksPeriodId, so the check is opted out of per AggregateType.
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.closing-books.aggregates.Ledgers.period-id-provided-externally=true")
                .withBean(TimeBoundaryAggregateWithoutPeriodId.class)
                .withBean(TimeBoundaryAggregateWithoutPeriodIdNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @Test
    void startup_succeeds_when_the_period_id_is_globally_declared_as_provided_externally() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.closing-books.period-id-provided-externally=true")
                .withBean(TimeBoundaryAggregateWithoutPeriodId.class)
                .withBean(TimeBoundaryAggregateWithoutPeriodIdNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateLifecycleConfigurationValidator.class);
                });
    }

    @AggregateSnapshotPolicy(aggregateType = "Orders", enabled = true)
    @AggregateClosingBooksPolicy(aggregateType = "Orders", enabled = true)
    static class ConflictingAggregate {
    }

    @AggregateSnapshotPolicy(aggregateType = "Subscriptions", enabled = true)
    static class SnapshotAnnotatedAggregate {
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

    @AggregateClosingBooksPolicy(aggregateType = "Ledgers",
                                 enabled = true,
                                 triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.TIME_BOUNDARY,
                                 timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH)
    static class TimeBoundaryAggregateWithoutPeriodId {
    }

    @AggregateClosingBooksPolicy(aggregateType = "Vouchers",
                                 enabled = true,
                                 triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.TIME_BOUNDARY)
    static class TimeBoundaryAggregateWithoutBoundary implements HasClosingBooksPeriodId {
        @Override
        public String closingBooksPeriodId() {
            return "2026-W32";
        }
    }

    @AggregateClosingBooksPolicy(aggregateType = "Journals",
                                 enabled = true,
                                 triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.TIME_BOUNDARY,
                                 timeBoundary = ClosingBooksTimeBoundary.END_OF_MONTH)
    static class TimeBoundaryAggregateWithPeriodId implements HasClosingBooksPeriodId {
        @Override
        public String closingBooksPeriodId() {
            return "2026-08";
        }
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

    static class TimeBoundaryAggregateWithoutPeriodIdNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<String, String, TimeBoundaryAggregateWithoutPeriodId, String> {
        @Override
        public Class<TimeBoundaryAggregateWithoutPeriodId> aggregateImplementationType() {
            return TimeBoundaryAggregateWithoutPeriodId.class;
        }

        @Override
        public TimeBoundaryAggregateWithoutPeriodId createNextGeneration(TimeBoundaryAggregateWithoutPeriodId currentAggregate,
                                                                          ClosingBooksAggregateInstantiationContext<String, String> context,
                                                                          String hint) {
            return new TimeBoundaryAggregateWithoutPeriodId();
        }
    }

    static class TimeBoundaryAggregateWithoutBoundaryNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<String, String, TimeBoundaryAggregateWithoutBoundary, String> {
        @Override
        public Class<TimeBoundaryAggregateWithoutBoundary> aggregateImplementationType() {
            return TimeBoundaryAggregateWithoutBoundary.class;
        }

        @Override
        public TimeBoundaryAggregateWithoutBoundary createNextGeneration(TimeBoundaryAggregateWithoutBoundary currentAggregate,
                                                                          ClosingBooksAggregateInstantiationContext<String, String> context,
                                                                          String hint) {
            return new TimeBoundaryAggregateWithoutBoundary();
        }
    }

    static class TimeBoundaryAggregateWithPeriodIdNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<String, String, TimeBoundaryAggregateWithPeriodId, String> {
        @Override
        public Class<TimeBoundaryAggregateWithPeriodId> aggregateImplementationType() {
            return TimeBoundaryAggregateWithPeriodId.class;
        }

        @Override
        public TimeBoundaryAggregateWithPeriodId createNextGeneration(TimeBoundaryAggregateWithPeriodId currentAggregate,
                                                                       ClosingBooksAggregateInstantiationContext<String, String> context,
                                                                       String hint) {
            return new TimeBoundaryAggregateWithPeriodId();
        }
    }
}
