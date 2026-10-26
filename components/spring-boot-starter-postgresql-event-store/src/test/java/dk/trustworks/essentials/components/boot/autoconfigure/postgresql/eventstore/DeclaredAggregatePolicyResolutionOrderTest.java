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

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A declared aggregate's policy annotations reach the registries through {@link AggregateDeclarationPolicyRegistrar},
 * and an application resolves its aggregate's configuration while its own beans are being built - a
 * {@code StatefulAggregateRepository} bean resolves its snapshot repository in its builder. Application beans are
 * created before auto-configured ones, so a resolution that does not wait for the registrar finds empty registries and
 * silently gets the global defaults: the trading demo's {@code @AggregateSnapshotPolicy(everyNEvents = 100)} snapshotted
 * every 10 events. These tests resolve from application beans and expect the annotations.
 */
class DeclaredAggregatePolicyResolutionOrderTest {
    private static final AggregateType DECLARED = AggregateType.of("Declared");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(SnapshotConfiguration.class, ClosingBooksConfiguration.class))
                    .withUserConfiguration(ResolvesWhileBeingBuilt.class)
                    .withPropertyValues("essentials.eventstore.snapshots.enabled=true");

    @Test
    void a_snapshot_policy_on_a_declared_aggregate_applies_to_a_resolution_made_while_the_application_starts() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var resolved = ctx.getBean(ResolvedAggregateSnapshotConfiguration.class);
            assertThat(resolved.everyNEvents()).isEqualTo(7);
            assertThat(resolved.mode()).isEqualTo(SnapshotExecutionMode.ASYNC_IN_MEMORY);
        });
    }

    @Test
    void a_closing_books_policy_on_a_declared_aggregate_applies_to_a_resolution_made_while_the_application_starts() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var resolved = ctx.getBean(ResolvedAggregateClosingBooksConfiguration.class);
            assertThat(resolved.zoneId()).isEqualTo("America/New_York");
            assertThat(resolved.enabled()).isFalse();
        });
    }

    /**
     * Declares the aggregate and, like an application's repository beans, resolves its configuration while being built
     */
    @Configuration
    static class ResolvesWhileBeingBuilt {
        @Bean
        ResolvedAggregateSnapshotConfiguration snapshotConfigurationResolvedAtStartup(AggregateSnapshotConfigurationResolver resolver) {
            return resolver.resolve(DECLARED, DeclaredAggregate.class);
        }

        @Bean
        ResolvedAggregateClosingBooksConfiguration closingBooksConfigurationResolvedAtStartup(AggregateClosingBooksConfigurationResolver resolver) {
            return resolver.resolve(DECLARED, DeclaredAggregate.class);
        }

        @Bean
        EssentialsAggregateDeclarations declaredAggregates() {
            return EssentialsAggregateDeclarations.builder()
                                                  .declare(DECLARED, DeclaredAggregate.class)
                                                  .build();
        }
    }

    @AggregateSnapshotPolicy(mode = SnapshotExecutionMode.ASYNC_IN_MEMORY, everyNEvents = 7)
    @AggregateClosingBooksPolicy(enabled = false, zoneId = "America/New_York")
    static class DeclaredAggregate {
    }
}
