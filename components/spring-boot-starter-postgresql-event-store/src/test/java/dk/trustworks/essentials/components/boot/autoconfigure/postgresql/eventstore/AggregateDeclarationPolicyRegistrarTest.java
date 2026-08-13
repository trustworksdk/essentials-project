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
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the mechanism that makes the policy annotations on an aggregate root take effect. An aggregate root is not a
 * Spring bean, so {@link AggregateSnapshotPolicyBeanPostProcessor} /
 * {@link AggregateClosingBooksPolicyBeanPostProcessor} never observe it - none of the aggregates below is registered as
 * a bean, which is the whole point.
 */
class AggregateDeclarationPolicyRegistrarTest {
    private static final AggregateType LEDGERS  = AggregateType.of("Ledgers");
    private static final AggregateType VOUCHERS = AggregateType.of("Vouchers");

    /** Written from a {@link SmartInitializingSingleton} callback inside the context, read by the test. */
    private static final AtomicInteger POLICIES_VISIBLE_TO_CALLBACK = new AtomicInteger(-1);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnapshotConfiguration.class, ClosingBooksConfiguration.class));

    @Test
    void test_policies_on_a_declared_aggregate_are_registered_even_though_it_is_not_a_spring_bean() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.snapshots.enabled=true")
                .withBean(EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder()
                                                              .declare(LEDGERS, DeclaredLedger.class)
                                                              .build())
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateDeclarationPolicyRegistrar.class);
                    assertThat(ctx.getBeanNamesForType(DeclaredLedger.class)).isEmpty();

                    assertThat(ctx.getBean(AggregateSnapshotPolicyRegistry.class)
                                  .findByAggregateImplementationType(DeclaredLedger.class))
                            .hasValueSatisfying(descriptor -> {
                                assertThat(descriptor.aggregateType()).contains("Ledgers");
                                assertThat(descriptor.policy().everyNEvents()).isEqualTo(50);
                            });

                    assertThat(ctx.getBean(AggregateClosingBooksPolicyRegistry.class)
                                  .findByAggregateImplementationType(DeclaredLedger.class))
                            .hasValueSatisfying(descriptor -> {
                                assertThat(descriptor.aggregateType()).contains("Ledgers");
                                assertThat(descriptor.policy().defaultPolicy()).isEqualTo(ClosingBooksDefaultPolicyType.MANUAL_ONLY);
                            });
                });
    }

    /**
     * The test that locks in registration-before-validation. {@code DeclaredBrokenLedger} asks for a time-boundary
     * policy with no boundary, which {@link DefaultAggregateLifecycleConfigurationValidator} rejects - but only if the
     * descriptor reached the registry before {@code afterSingletonsInstantiated()} ran. If registration ever regresses
     * to running after validation, the validator sees an empty registry and startup succeeds, so this test asserts the
     * <b>failure</b> rather than the success.
     */
    @Test
    void test_startup_fails_when_a_declared_aggregate_carries_an_invalid_policy() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(DeclaredBrokenLedgerNextGenerationFactory.class)
                .withBean(EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder()
                                                              .declare(LEDGERS, DeclaredBrokenLedger.class)
                                                              .build())
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .isNotNull()
                        .hasMessageContaining("the resolved time boundary is NONE"));
    }

    /**
     * The counterpart to the test above, and the reason declarations exist: the very same aggregate with the very same
     * invalid annotation starts up perfectly happily when it is not declared, because nothing ever reads it. This is
     * the silent failure that declarations remove.
     */
    @Test
    void test_an_undeclared_aggregate_that_is_not_a_spring_bean_stays_invisible() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(DeclaredBrokenLedgerNextGenerationFactory.class)
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx.getBean(AggregateClosingBooksPolicyRegistry.class).getRegisteredPolicies()).isEmpty();
                });
    }

    @Test
    void test_the_annotations_own_aggregate_type_wins_over_the_declared_one() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder()
                                                              .declare(LEDGERS, AnnotationTypedVoucher.class)
                                                              .build())
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx.getBean(AggregateClosingBooksPolicyRegistry.class)
                                  .findByAggregateImplementationType(AnnotationTypedVoucher.class))
                            .hasValueSatisfying(descriptor -> assertThat(descriptor.aggregateType()).contains(VOUCHERS.toString()));
                });
    }

    @Test
    void test_declarations_from_several_beans_are_all_registered() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true",
                                    "essentials.eventstore.snapshots.enabled=true")
                .withBean("ledgers",
                          EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder().declare(LEDGERS, DeclaredLedger.class).build())
                .withBean("vouchers",
                          EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder().declare(VOUCHERS, AnnotationTypedVoucher.class).build())
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx.getBean(AggregateClosingBooksPolicyRegistry.class).getRegisteredPolicies()).hasSize(2);
                });
    }

    @Test
    void test_a_declared_aggregate_without_policy_annotations_registers_nothing() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withBean(EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder()
                                                              .declare(LEDGERS, UnannotatedLedger.class)
                                                              .build())
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx.getBean(AggregateClosingBooksPolicyRegistry.class).getRegisteredPolicies()).isEmpty();
                    assertThat(ctx.getBean(AggregateSnapshotPolicyRegistry.class).getRegisteredPolicies()).isEmpty();
                });
    }

    /**
     * The invariant the whole design rests on: every declared policy is in the registries before the first
     * {@link SmartInitializingSingleton} callback fires, which is when
     * {@link DefaultAggregateLifecycleConfigurationValidator} validates. This holds because the registrar is an
     * {@link org.springframework.beans.factory.InitializingBean} - it registers while singletons are still being
     * created - and would break if registration moved into a {@code SmartInitializingSingleton}, where it would be
     * subject to callback order.
     */
    @Test
    void test_declared_policies_are_registered_before_any_smart_initializing_singleton_callback() {
        POLICIES_VISIBLE_TO_CALLBACK.set(-1);
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .withUserConfiguration(RegistryObserverConfiguration.class)
                .withBean(EssentialsAggregateDeclarations.class,
                          () -> EssentialsAggregateDeclarations.builder()
                                                              .declare(LEDGERS, DeclaredLedger.class)
                                                              .build())
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(POLICIES_VISIBLE_TO_CALLBACK).hasValue(1);
                });
    }

    @Test
    void test_a_context_with_no_declarations_still_starts() {
        contextRunner
                .withPropertyValues("essentials.eventstore.closing-books.enabled=true")
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNull();
                    assertThat(ctx).hasSingleBean(AggregateDeclarationPolicyRegistrar.class);
                });
    }

    /**
     * Neither the annotation's {@code aggregateType} nor a Spring bean definition - the declaration is the only source
     * of the aggregate type.
     */
    @AggregateSnapshotPolicy(everyNEvents = 50)
    @AggregateClosingBooksPolicy(defaultPolicy = ClosingBooksDefaultPolicyType.MANUAL_ONLY)
    static class DeclaredLedger {
    }

    @AggregateClosingBooksPolicy(triggerMode = ClosingBooksTriggerMode.ON_ACCESS,
                                 defaultPolicy = ClosingBooksDefaultPolicyType.TIME_BOUNDARY)
    static class DeclaredBrokenLedger implements HasClosingBooksPeriodId {
        @Override
        public String closingBooksPeriodId() {
            return "2026-08";
        }
    }

    @AggregateClosingBooksPolicy(aggregateType = "Vouchers",
                                 defaultPolicy = ClosingBooksDefaultPolicyType.MANUAL_ONLY)
    static class AnnotationTypedVoucher {
    }

    static class UnannotatedLedger {
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistryObserverConfiguration {
        @Bean
        SmartInitializingSingleton registryObserver(AggregateClosingBooksPolicyRegistry registry) {
            return () -> POLICIES_VISIBLE_TO_CALLBACK.set(registry.getRegisteredPolicies().size());
        }
    }

    static class DeclaredBrokenLedgerNextGenerationFactory implements TypedClosingBooksNextGenerationFactory<String, String, DeclaredBrokenLedger, String> {
        @Override
        public Class<DeclaredBrokenLedger> aggregateImplementationType() {
            return DeclaredBrokenLedger.class;
        }

        @Override
        public DeclaredBrokenLedger createNextGeneration(DeclaredBrokenLedger currentAggregate,
                                                         ClosingBooksAggregateInstantiationContext<String, String> context,
                                                         String hint) {
            return new DeclaredBrokenLedger();
        }
    }
}
