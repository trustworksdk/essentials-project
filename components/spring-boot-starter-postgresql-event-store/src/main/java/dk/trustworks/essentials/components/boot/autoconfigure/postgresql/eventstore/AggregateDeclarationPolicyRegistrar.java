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

import dk.trustworks.essentials.components.eventsourced.aggregates.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import org.slf4j.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Registers the aggregate-lifecycle policy annotations found on {@link EssentialsAggregateDeclarations declared}
 * aggregate implementation classes into the {@link AggregateSnapshotPolicyRegistry} and
 * {@link AggregateClosingBooksPolicyRegistry}.
 * <p>
 * This exists because {@link AggregateSnapshotPolicyBeanPostProcessor} and
 * {@link AggregateClosingBooksPolicyBeanPostProcessor} can only observe <b>Spring beans</b>, and an aggregate root is
 * not one - a singleton {@code TradingAccount} would be meaningless. Without this registrar,
 * {@code @AggregateSnapshotPolicy} and {@code @AggregateClosingBooksPolicy} on an aggregate class reach no registry and
 * the admin API's lifecycle endpoints report nothing, with no error to explain why.
 * <p>
 * <b>Ordering.</b> {@link DefaultAggregateLifecycleConfigurationValidator} validates registry contents from
 * {@code afterSingletonsInstantiated()}. Being an {@link InitializingBean} is what guarantees this registrar wins that
 * race: {@link #afterPropertiesSet()} runs while the singletons are still being created, and every
 * {@code SmartInitializingSingleton} callback fires only after that phase completes. Implementing
 * {@code SmartInitializingSingleton} here instead would put registration and validation in the same phase, ordered
 * merely by bean-registration order, and a registrar running second would let validation pass over empty registries -
 * reintroducing exactly the silent failure this class exists to remove.
 * <p>
 * The validator is not the only reader, and the others do not wait for a phase: an application resolves its
 * aggregates' configuration while its own beans are being built (a {@code StatefulAggregateRepository} bean resolves its
 * snapshot repository in its builder), and application beans are created before auto-configured ones - possibly before
 * this registrar. The starter's default registries therefore do not depend on this registrar: each registers the
 * declared policies itself before it answers its first read ({@link #registerDeclaredSnapshotPolicies},
 * {@link #registerDeclaredClosingBooksPolicies}), so every reader of them sees the declared policies - except a read
 * made while an {@link EssentialsAggregateDeclarations} bean (or something it depends on) is itself being created.
 * For those registries this registrar registers the same descriptors again. It is what brings the declared policies
 * into an application-supplied registry, and a read of such a registry before this registrar has run finds them
 * missing - an application-supplied registry must register them itself, with the same two methods, to be safe for
 * early readers.
 * <p>
 * The registrar is additionally passed to the validator's {@code @Bean} method as an unused parameter. That is belt and
 * braces rather than the mechanism - it keeps the dependency visible in the wiring, and preserves correctness if the
 * validator ever moves its checks out of {@code afterSingletonsInstantiated()} and into its constructor.
 *
 * @see EssentialsAggregateDeclarations
 */
public class AggregateDeclarationPolicyRegistrar implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(AggregateDeclarationPolicyRegistrar.class);

    private final List<EssentialsAggregateDeclarations> declarations;
    private final AggregateSnapshotPolicyRegistry       snapshotPolicyRegistry;
    private final AggregateClosingBooksPolicyRegistry   closingBooksPolicyRegistry;

    /**
     * @param declarations               every {@link EssentialsAggregateDeclarations} bean in the context; must not be
     *                                   null, may be empty
     * @param snapshotPolicyRegistry     the registry receiving {@link AggregateSnapshotPolicyDescriptor}s; must not be null
     * @param closingBooksPolicyRegistry the registry receiving {@link AggregateClosingBooksPolicyDescriptor}s; must not
     *                                   be null
     */
    public AggregateDeclarationPolicyRegistrar(List<EssentialsAggregateDeclarations> declarations,
                                               AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                               AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry) {
        this.declarations = List.copyOf(requireNonNull(declarations, "No declarations provided"));
        this.snapshotPolicyRegistry = requireNonNull(snapshotPolicyRegistry, "No snapshotPolicyRegistry provided");
        this.closingBooksPolicyRegistry = requireNonNull(closingBooksPolicyRegistry, "No closingBooksPolicyRegistry provided");
    }

    @Override
    public void afterPropertiesSet() {
        registerDeclaredSnapshotPolicies(declarations, snapshotPolicyRegistry);
        registerDeclaredClosingBooksPolicies(declarations, closingBooksPolicyRegistry);
        declarations.stream()
                    .flatMap(declaration -> declaration.declarations().stream())
                    .filter(declaration -> AnnotationUtils.findAnnotation(declaration.aggregateImplementationType(), AggregateSnapshotPolicy.class) == null &&
                            AnnotationUtils.findAnnotation(declaration.aggregateImplementationType(), AggregateClosingBooksPolicy.class) == null)
                    .forEach(declaration -> log.debug("Declared aggregate '{}' (aggregateType '{}') carries neither @AggregateSnapshotPolicy nor @AggregateClosingBooksPolicy",
                                                      declaration.aggregateImplementationType().getName(),
                                                      declaration.aggregateType()));
    }

    /**
     * Registers the {@link AggregateSnapshotPolicy} of every declared aggregate implementation class that carries one
     * into {@code registry}. Registering the same declarations again replaces each descriptor with an equal one.
     *
     * @param declarations every {@link EssentialsAggregateDeclarations} to read; must not be null, may be empty
     * @param registry     the registry receiving the {@link AggregateSnapshotPolicyDescriptor}s; must not be null
     */
    public static void registerDeclaredSnapshotPolicies(List<EssentialsAggregateDeclarations> declarations,
                                                        AggregateSnapshotPolicyRegistry registry) {
        requireNonNull(declarations, "No declarations provided");
        requireNonNull(registry, "No registry provided");
        declarations.stream()
                    .flatMap(declaration -> declaration.declarations().stream())
                    .forEach(declaration -> {
                        var aggregateImplementationType = declaration.aggregateImplementationType();
                        var snapshotPolicy = AnnotationUtils.findAnnotation(aggregateImplementationType, AggregateSnapshotPolicy.class);
                        if (snapshotPolicy != null) {
                            registry.register(new AggregateSnapshotPolicyDescriptor(aggregateImplementationType,
                                                                                   resolveAggregateType(snapshotPolicy.aggregateType(), declaration),
                                                                                   snapshotPolicy));
                            log.debug("Registered aggregate snapshot policy for '{}' from declared aggregateType '{}'",
                                      aggregateImplementationType.getName(),
                                      declaration.aggregateType());
                        }
                    });
    }

    /**
     * Registers the {@link AggregateClosingBooksPolicy} of every declared aggregate implementation class that carries
     * one into {@code registry}. Registering the same declarations again replaces each descriptor with an equal one.
     *
     * @param declarations every {@link EssentialsAggregateDeclarations} to read; must not be null, may be empty
     * @param registry     the registry receiving the {@link AggregateClosingBooksPolicyDescriptor}s; must not be null
     */
    public static void registerDeclaredClosingBooksPolicies(List<EssentialsAggregateDeclarations> declarations,
                                                            AggregateClosingBooksPolicyRegistry registry) {
        requireNonNull(declarations, "No declarations provided");
        requireNonNull(registry, "No registry provided");
        declarations.stream()
                    .flatMap(declaration -> declaration.declarations().stream())
                    .forEach(declaration -> {
                        var aggregateImplementationType = declaration.aggregateImplementationType();
                        var closingBooksPolicy = AnnotationUtils.findAnnotation(aggregateImplementationType, AggregateClosingBooksPolicy.class);
                        if (closingBooksPolicy != null) {
                            registry.register(new AggregateClosingBooksPolicyDescriptor(aggregateImplementationType,
                                                                                       resolveAggregateType(closingBooksPolicy.aggregateType(), declaration),
                                                                                       closingBooksPolicy));
                            log.debug("Registered aggregate closing-books policy for '{}' from declared aggregateType '{}'",
                                      aggregateImplementationType.getName(),
                                      declaration.aggregateType());
                        }
                    });
    }

    /**
     * The annotation's own {@code aggregateType()} wins when set - it is the only way a
     * {@code BeanPostProcessor}-registered descriptor ever got one - otherwise the declaration supplies it.
     */
    private static Optional<String> resolveAggregateType(String annotationAggregateType,
                                                         AggregateDeclaration declaration) {
        return annotationAggregateType.isBlank()
               ? Optional.of(declaration.aggregateType().toString())
               : Optional.of(annotationAggregateType);
    }
}
