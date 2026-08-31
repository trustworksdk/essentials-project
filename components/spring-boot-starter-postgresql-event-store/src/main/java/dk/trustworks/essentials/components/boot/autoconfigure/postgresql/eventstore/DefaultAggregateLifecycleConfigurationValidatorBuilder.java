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
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyRegistry;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link DefaultAggregateLifecycleConfigurationValidator}, obtained from
 * {@link DefaultAggregateLifecycleConfigurationValidator#builder()}.
 * <p>
 * The {@link FencedLockManager} is held as a plain nullable field — absent is a legitimate configuration, and is
 * precisely what the validator's {@code SCHEDULED_SCAN} check fails on — and also has an {@code Optional} overload,
 * which is what the {@code @Bean} method assembling this validator naturally holds. {@code nextGenerationFactories}
 * defaults to empty rather than being required, because having none is the normal case for an application that does not
 * use closing books.
 */
public final class DefaultAggregateLifecycleConfigurationValidatorBuilder {
    private AggregateSnapshotPolicyRegistry                        snapshotPolicyRegistry;
    private AggregateClosingBooksPolicyRegistry                    closingBooksPolicyRegistry;
    private AggregateSnapshotConfigurationResolver                 snapshotConfigurationResolver;
    private AggregateClosingBooksConfigurationResolver             closingBooksConfigurationResolver;
    private EssentialsEventStoreProperties                         properties;
    private FencedLockManager                                      fencedLockManager;
    private List<TypedClosingBooksNextGenerationFactory<?, ?, ?, ?>> nextGenerationFactories = List.of();

    /**
     * @param snapshotPolicyRegistry the registry of aggregate snapshot policy descriptors. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setSnapshotPolicyRegistry(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry) {
        this.snapshotPolicyRegistry = snapshotPolicyRegistry;
        return this;
    }

    /**
     * @param closingBooksPolicyRegistry the registry of aggregate closing-books policy descriptors. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setClosingBooksPolicyRegistry(AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry) {
        this.closingBooksPolicyRegistry = closingBooksPolicyRegistry;
        return this;
    }

    /**
     * @param snapshotConfigurationResolver the resolver determining snapshot configuration per aggregate. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setSnapshotConfigurationResolver(AggregateSnapshotConfigurationResolver snapshotConfigurationResolver) {
        this.snapshotConfigurationResolver = snapshotConfigurationResolver;
        return this;
    }

    /**
     * @param closingBooksConfigurationResolver the resolver determining closing-books configuration per aggregate. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setClosingBooksConfigurationResolver(AggregateClosingBooksConfigurationResolver closingBooksConfigurationResolver) {
        this.closingBooksConfigurationResolver = closingBooksConfigurationResolver;
        return this;
    }

    /**
     * @param properties the event-store properties containing system-level settings. Required
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setProperties(EssentialsEventStoreProperties properties) {
        this.properties = properties;
        return this;
    }

    /**
     * @param fencedLockManager the lock manager, or {@code null} when none is configured. A {@code SCHEDULED_SCAN}
     *                          closing-books policy without one is exactly what the validator rejects
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setFencedLockManager(FencedLockManager fencedLockManager) {
        this.fencedLockManager = fencedLockManager;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setFencedLockManager(FencedLockManager)}.
     *
     * @param fencedLockManager the lock manager, or empty when none is configured
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setFencedLockManager(Optional<FencedLockManager> fencedLockManager) {
        requireNonNull(fencedLockManager, "fencedLockManager cannot be null");
        return setFencedLockManager(fencedLockManager.orElse(null));
    }

    /**
     * @param nextGenerationFactories the typed carry-forward factories for closing books. Defaults to empty
     * @return this builder instance for fluent chaining
     */
    public DefaultAggregateLifecycleConfigurationValidatorBuilder setNextGenerationFactories(List<TypedClosingBooksNextGenerationFactory<?, ?, ?, ?>> nextGenerationFactories) {
        this.nextGenerationFactories = nextGenerationFactories;
        return this;
    }

    /**
     * Builds the validator.
     *
     * @return the validator
     */
    @SuppressWarnings("removal")
    public DefaultAggregateLifecycleConfigurationValidator build() {
        return new DefaultAggregateLifecycleConfigurationValidator(requireNonNull(snapshotPolicyRegistry, "snapshotPolicyRegistry cannot be null"),
                                                                   requireNonNull(closingBooksPolicyRegistry, "closingBooksPolicyRegistry cannot be null"),
                                                                   requireNonNull(snapshotConfigurationResolver, "snapshotConfigurationResolver cannot be null"),
                                                                   requireNonNull(closingBooksConfigurationResolver, "closingBooksConfigurationResolver cannot be null"),
                                                                   requireNonNull(properties, "properties cannot be null"),
                                                                   Optional.ofNullable(fencedLockManager),
                                                                   requireNonNull(nextGenerationFactories, "nextGenerationFactories cannot be null"));
    }
}
