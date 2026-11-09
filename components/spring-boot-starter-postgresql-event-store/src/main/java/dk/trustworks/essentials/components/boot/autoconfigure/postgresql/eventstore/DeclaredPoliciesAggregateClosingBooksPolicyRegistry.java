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
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The starter's default {@link AggregateClosingBooksPolicyRegistry}: an {@link InMemoryAggregateClosingBooksPolicyRegistry}
 * that registers the {@link AggregateClosingBooksPolicy} of every {@link EssentialsAggregateDeclarations declared}
 * aggregate before it answers its first read. Whoever reads it - the starter's resolver, an application-supplied
 * resolver, an application bean being built - therefore sees the declared policies, with one exception: a read made
 * while an {@link EssentialsAggregateDeclarations} bean (or something it depends on) is itself being created sees the
 * registry without the declared policies. A concurrent first read waits until the declared policies are registered.
 * <p>
 * The declarations are read on the first read, not when the registry is created: the policy {@code BeanPostProcessor}s
 * take this registry, so it is created while the {@code BeanPostProcessor}s are registered, and reading the declarations
 * then would create the application's declaration beans too early for the other {@code BeanPostProcessor}s to process
 * them. {@link #register} does not trigger the read.
 * <p>
 * <b>The declaration wins.</b> For a declared aggregate class that carries {@link AggregateClosingBooksPolicy}, the
 * registry always answers with the descriptor built from the declaration, whatever else calls {@link #register} for
 * that class and in whichever order - an application's explicit {@code register(...)}, or the policy
 * {@code BeanPostProcessor} when the class is also a Spring bean (whose descriptor lacks the declared
 * {@code aggregateType}). A descriptor registered before the first read is replaced by the declared one on that read;
 * one registered after it, and not equal to the declared one, is ignored and logged. A class that is not declared, or
 * is declared without {@link AggregateClosingBooksPolicy}, is registered as {@link #register} is called.
 */
final class DeclaredPoliciesAggregateClosingBooksPolicyRegistry extends InMemoryAggregateClosingBooksPolicyRegistry {
    private static final Logger log = LoggerFactory.getLogger(DeclaredPoliciesAggregateClosingBooksPolicyRegistry.class);

    private final Supplier<List<EssentialsAggregateDeclarations>>        declarations;
    /**
     * The descriptor built from the declaration, per declared aggregate implementation class; filled on the first read
     */
    private final Map<Class<?>, AggregateClosingBooksPolicyDescriptor>   declaredDescriptors = new ConcurrentHashMap<>();
    private volatile boolean                                             declaredPoliciesRegistered;
    /**
     * Read and written only while holding this registry's monitor
     */
    private          boolean                                     registering;

    /**
     * @param declarations supplies every {@link EssentialsAggregateDeclarations} bean in the context, called once on
     *                     the first read; must not be null
     */
    DeclaredPoliciesAggregateClosingBooksPolicyRegistry(Supplier<List<EssentialsAggregateDeclarations>> declarations) {
        this.declarations = requireNonNull(declarations, "No declarations provided");
    }

    /**
     * Registers {@code descriptor}, unless its aggregate implementation class is declared with an
     * {@link AggregateClosingBooksPolicy}: then the declared descriptor stays (see the class javadoc). Does not read
     * the declarations.
     */
    @Override
    public void register(AggregateClosingBooksPolicyDescriptor descriptor) {
        requireNonNull(descriptor, "No descriptor provided");
        var declared = declaredDescriptors.get(descriptor.aggregateImplementationType());
        if (declared != null) {
            if (!declared.equals(descriptor)) {
                log.info("Ignored registering {} - '{}' is declared, and its declared closing-books policy {} wins",
                         descriptor, descriptor.aggregateImplementationType().getName(), declared);
            }
            return;
        }
        super.register(descriptor);
        // The declared descriptors may have been registered between the check above and this registration
        declared = declaredDescriptors.get(descriptor.aggregateImplementationType());
        if (declared != null) {
            super.register(declared);
        }
    }

    @Override
    public Optional<AggregateClosingBooksPolicyDescriptor> findByAggregateImplementationType(Class<?> aggregateImplementationType) {
        registerDeclaredPoliciesOnce();
        return super.findByAggregateImplementationType(aggregateImplementationType);
    }

    @Override
    public Collection<AggregateClosingBooksPolicyDescriptor> getRegisteredPolicies() {
        registerDeclaredPoliciesOnce();
        return super.getRegisteredPolicies();
    }

    private void registerDeclaredPoliciesOnce() {
        if (declaredPoliciesRegistered) {
            return;
        }
        synchronized (this) {
            // registering is only true here when this thread re-enters (the monitor is reentrant): a read made while
            // the declaration beans are being created. It reads without the declared policies instead of recursing.
            // Other threads block on the monitor until the registration has finished.
            if (declaredPoliciesRegistered || registering) {
                return;
            }
            registering = true;
            try {
                var declared = new InMemoryAggregateClosingBooksPolicyRegistry();
                AggregateDeclarationPolicyRegistrar.registerDeclaredClosingBooksPolicies(declarations.get(), declared);
                for (var descriptor : declared.getRegisteredPolicies()) {
                    // Recorded before it is registered: a concurrent register(...) checks it again after its own
                    declaredDescriptors.put(descriptor.aggregateImplementationType(), descriptor);
                    super.findByAggregateImplementationType(descriptor.aggregateImplementationType())
                         .filter(registered -> !registered.equals(descriptor))
                         .ifPresent(registered -> log.info("Replaced {} with the declared closing-books policy {} - '{}' is declared, and its declaration wins",
                                                           registered, descriptor, descriptor.aggregateImplementationType().getName()));
                    super.register(descriptor);
                }
                declaredPoliciesRegistered = true;
            } finally {
                registering = false;
            }
        }
    }
}
