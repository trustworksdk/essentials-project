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
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application resolves its aggregate's configuration while its own beans are being built - a
 * {@code StatefulAggregateRepository} bean resolves its snapshot repository in its builder. Application beans are
 * created before auto-configured ones, so a resolution that reads a registry not yet holding the declared policies
 * silently gets the global defaults: the trading demo's {@code @AggregateSnapshotPolicy(everyNEvents = 100)} snapshotted
 * every 10 events. The default registries register the declared policies before their first read. These tests resolve
 * from application beans, through the starter's resolvers and through application-supplied ones, and expect the
 * annotations.
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
     * The registries are populated with the declared policies whoever reads them: an application that replaces both
     * resolvers with its own still gets the annotation values, not the global defaults.
     */
    @Test
    void declared_policies_apply_to_an_application_supplied_resolver_resolving_while_the_application_starts() {
        contextRunner.withUserConfiguration(ApplicationSuppliedResolvers.class)
                     .run(ctx -> {
                         assertThat(ctx).hasNotFailed();
                         assertThat(ctx.getBean(AggregateSnapshotConfigurationResolver.class)).isInstanceOf(ApplicationSnapshotResolver.class);
                         assertThat(ctx.getBean(AggregateClosingBooksConfigurationResolver.class)).isInstanceOf(ApplicationClosingBooksResolver.class);

                         var snapshot = ctx.getBean(ResolvedAggregateSnapshotConfiguration.class);
                         assertThat(snapshot.everyNEvents()).isEqualTo(7);
                         assertThat(snapshot.mode()).isEqualTo(SnapshotExecutionMode.ASYNC_IN_MEMORY);

                         var closingBooks = ctx.getBean(ResolvedAggregateClosingBooksConfiguration.class);
                         assertThat(closingBooks.zoneId()).isEqualTo("America/New_York");
                         assertThat(closingBooks.enabled()).isFalse();
                     });
    }

    /**
     * Replaces both auto-configured resolvers, so nothing in the starter's resolver beans orders the reads
     */
    @Configuration
    static class ApplicationSuppliedResolvers {
        @Bean
        AggregateSnapshotConfigurationResolver applicationSnapshotResolver(EssentialsEventStoreProperties properties,
                                                                           AggregateSnapshotPolicyRegistry registry) {
            return new ApplicationSnapshotResolver(properties, registry);
        }

        @Bean
        AggregateClosingBooksConfigurationResolver applicationClosingBooksResolver(EssentialsEventStoreProperties properties,
                                                                                   AggregateClosingBooksPolicyRegistry registry) {
            return new ApplicationClosingBooksResolver(properties, registry);
        }
    }

    static class ApplicationSnapshotResolver extends DefaultAggregateSnapshotConfigurationResolver {
        ApplicationSnapshotResolver(EssentialsEventStoreProperties properties, AggregateSnapshotPolicyRegistry registry) {
            super(properties, registry);
        }
    }

    static class ApplicationClosingBooksResolver extends DefaultAggregateClosingBooksConfigurationResolver {
        ApplicationClosingBooksResolver(EssentialsEventStoreProperties properties, AggregateClosingBooksPolicyRegistry registry) {
            super(properties, registry);
        }
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
