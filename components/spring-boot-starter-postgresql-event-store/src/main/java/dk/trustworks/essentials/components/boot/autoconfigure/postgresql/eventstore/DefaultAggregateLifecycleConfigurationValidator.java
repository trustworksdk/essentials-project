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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Default implementation of the {@code AggregateLifecycleConfigurationValidator} that validates the configuration
 * of aggregate lifecycles within the application. This class ensures that aggregates adhere to defined policies
 * and configurations, including snapshotting and closing books.
 */
public class DefaultAggregateLifecycleConfigurationValidator implements AggregateLifecycleConfigurationValidator, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DefaultAggregateLifecycleConfigurationValidator.class);

    private final AggregateSnapshotPolicyRegistry            snapshotPolicyRegistry;
    private final AggregateClosingBooksPolicyRegistry        closingBooksPolicyRegistry;
    private final AggregateSnapshotConfigurationResolver     snapshotConfigurationResolver;
    private final AggregateClosingBooksConfigurationResolver closingBooksConfigurationResolver;
    private final EssentialsEventStoreProperties             properties;
    private final Optional<FencedLockManager>                fencedLockManagerOptional;
    private final Set<Class<?>>                              nextGenerationFactoryAggregateTypes;

    /**
     * Constructs a {@code DefaultAggregateLifecycleConfigurationValidator} with the necessary dependencies.
     * This validator ensures that various configurations comply with the intended lifecycle policies
     * for aggregates in an event-sourced system.
     *
     * @param snapshotPolicyRegistry the registry for managing aggregate snapshot policy descriptors;
     *                                must not be null
     * @param closingBooksPolicyRegistry the registry for managing aggregate closing books policy descriptors;
     *                                    must not be null
     * @param snapshotConfigurationResolver the resolver for determining snapshot-specific configurations
     *                                       for aggregates; must not be null
     * @param closingBooksConfigurationResolver the resolver for determining closing books-specific configurations
     *                                           for aggregates; must not be null
     * @param properties the event store properties containing system-level settings; must not be null
     * @param fencedLockManagerOptional an optional fencing lock manager used for handling concurrency controls;
     *                                   must not be null
     * @param nextGenerationFactories a list of typed factories responsible for creating the next-generation
     *                                implementations of aggregates; must not be null
     * @throws IllegalArgumentException if any of the provided parameters is null
     */
    public DefaultAggregateLifecycleConfigurationValidator(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                           AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                                           AggregateSnapshotConfigurationResolver snapshotConfigurationResolver,
                                                           AggregateClosingBooksConfigurationResolver closingBooksConfigurationResolver,
                                                           EssentialsEventStoreProperties properties,
                                                           Optional<FencedLockManager> fencedLockManagerOptional,
                                                           List<TypedClosingBooksNextGenerationFactory<?, ?, ?, ?>> nextGenerationFactories) {
        this.snapshotPolicyRegistry = requireNonNull(snapshotPolicyRegistry, "No snapshotPolicyRegistry provided");
        this.closingBooksPolicyRegistry = requireNonNull(closingBooksPolicyRegistry, "No closingBooksPolicyRegistry provided");
        this.snapshotConfigurationResolver = requireNonNull(snapshotConfigurationResolver, "No snapshotConfigurationResolver provided");
        this.closingBooksConfigurationResolver = requireNonNull(closingBooksConfigurationResolver, "No closingBooksConfigurationResolver provided");
        this.properties = requireNonNull(properties, "No properties provided");
        this.fencedLockManagerOptional = requireNonNull(fencedLockManagerOptional, "No fencedLockManagerOptional provided");
        this.nextGenerationFactoryAggregateTypes = requireNonNull(nextGenerationFactories, "No nextGenerationFactories provided").stream()
                                                                                                                              .map(TypedClosingBooksNextGenerationFactory::aggregateImplementationType)
                                                                                                                              .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    @Override
    public void validate() {
        var aggregateImplementationTypes = new LinkedHashSet<Class<?>>();
        snapshotPolicyRegistry.getRegisteredPolicies().forEach(descriptor -> aggregateImplementationTypes.add(descriptor.aggregateImplementationType()));
        closingBooksPolicyRegistry.getRegisteredPolicies().forEach(descriptor -> aggregateImplementationTypes.add(descriptor.aggregateImplementationType()));

        for (var aggregateImplementationType : aggregateImplementationTypes) {
            var aggregateType = resolveAggregateType(aggregateImplementationType);
            var resolvedSnapshotConfiguration = snapshotConfigurationResolver.resolve(aggregateType, aggregateImplementationType);
            var resolvedClosingBooksConfiguration = closingBooksConfigurationResolver.resolve(aggregateType, aggregateImplementationType);

            if (resolvedSnapshotConfiguration.enabled() && resolvedClosingBooksConfiguration.enabled()) {
                log.warn("Aggregate '{}' enables both snapshotting and closing books for aggregateType '{}'. This is allowed, but validate that the snapshot cadence still makes sense for the closing-books policy.",
                         aggregateImplementationType.getName(),
                         aggregateType);
            }

            warnIfAnnotationIntentSilenced(aggregateImplementationType,
                                           aggregateType,
                                           resolvedSnapshotConfiguration.enabled(),
                                           resolvedClosingBooksConfiguration.enabled());

            if (resolvedClosingBooksConfiguration.enabled() &&
                resolvedClosingBooksConfiguration.triggerMode() == ClosingBooksTriggerMode.SCHEDULED_SCAN &&
                fencedLockManagerOptional.isEmpty()) {
                throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() + "' uses scheduled closing books for aggregateType '" + aggregateType + "' but no FencedLockManager is configured");
            }

            if (resolvedClosingBooksConfiguration.enabled() &&
                requiresNextGenerationFactory(resolvedClosingBooksConfiguration.defaultPolicy()) &&
                !nextGenerationFactoryAggregateTypes.contains(aggregateImplementationType)) {
                throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() + "' uses automatic close-and-open-next-generation policy '" +
                                                       resolvedClosingBooksConfiguration.defaultPolicy() + "' for aggregateType '" + aggregateType +
                                                       "' but no TypedClosingBooksNextGenerationFactory is registered");
            }

            if (resolvedClosingBooksConfiguration.enabled() &&
                usesTimeBoundaryPolicy(resolvedClosingBooksConfiguration.defaultPolicy()) &&
                resolvedClosingBooksConfiguration.timeBoundary() == ClosingBooksTimeBoundary.NONE) {
                throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() + "' uses time-boundary closing-books policy '" +
                                                        resolvedClosingBooksConfiguration.defaultPolicy() + "' for aggregateType '" + aggregateType +
                                                        "' but the resolved time boundary is NONE, so the boundary can never advance and the policy would never close the books. " +
                                                        "Set a boundary via 'essentials.eventstore.closing-books.time-boundary', " +
                                                        "'essentials.eventstore.closing-books.aggregates." + aggregateType + ".time-boundary', " +
                                                        "or @AggregateClosingBooksPolicy.timeBoundary" +
                                                        (resolvedClosingBooksConfiguration.defaultPolicy() == ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY
                                                         ? " - or use policy 'EVENT_COUNT' if only the event-count condition was intended."
                                                         : "."));
            }

            if (resolvedClosingBooksConfiguration.enabled() &&
                usesTimeBoundaryPolicy(resolvedClosingBooksConfiguration.defaultPolicy()) &&
                !HasClosingBooksPeriodId.class.isAssignableFrom(aggregateImplementationType) &&
                !periodIdProvidedExternally(aggregateType)) {
                throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() + "' uses time-boundary closing-books policy '" +
                                                        resolvedClosingBooksConfiguration.defaultPolicy() + "' for aggregateType '" + aggregateType +
                                                        "' but does not implement " + HasClosingBooksPeriodId.class.getName() +
                                                        ", so the framework cannot determine whether the configured boundary has advanced. " +
                                                        "Implement the interface, or - if the period id is supplied through a custom currentPeriodIdProvider - set " +
                                                        "'essentials.eventstore.closing-books.period-id-provided-externally=true' or " +
                                                        "'essentials.eventstore.closing-books.aggregates." + aggregateType + ".period-id-provided-externally=true'.");
            }

            if (resolvedClosingBooksConfiguration.enabled()) {
                validateZoneId(aggregateImplementationType, aggregateType, resolvedClosingBooksConfiguration);
                if (eventThresholdRequired(resolvedClosingBooksConfiguration.defaultPolicy())
                        && eventThresholdWasDefaulted(aggregateImplementationType, aggregateType)) {
                    log.warn("Aggregate '{}' (aggregateType '{}') uses '{}' policy but no event threshold is configured; using default {}. " +
                             "Set 'essentials.eventstore.closing-books.event-threshold', " +
                             "'essentials.eventstore.closing-books.aggregates.{}.event-threshold', " +
                             "or @AggregateClosingBooksPolicy.eventThreshold to override.",
                             aggregateImplementationType.getName(),
                             aggregateType,
                             resolvedClosingBooksConfiguration.defaultPolicy(),
                             DefaultAggregateClosingBooksConfigurationResolver.DEFAULT_EVENT_THRESHOLD,
                             aggregateType);
                }
                if (resolvedClosingBooksConfiguration.timeBoundary() == ClosingBooksTimeBoundary.EVERY_N_DAYS
                        && intervalDaysWasDefaulted(aggregateImplementationType, aggregateType)) {
                    log.warn("Aggregate '{}' (aggregateType '{}') uses time boundary 'EVERY_N_DAYS' but no interval is configured; using default {} days. " +
                             "Set 'essentials.eventstore.closing-books.interval-days', " +
                             "'essentials.eventstore.closing-books.aggregates.{}.interval-days', " +
                             "or @AggregateClosingBooksPolicy.intervalDays to override.",
                             aggregateImplementationType.getName(),
                             aggregateType,
                             DefaultAggregateClosingBooksConfigurationResolver.DEFAULT_INTERVAL_DAYS,
                             aggregateType);
                }
            }
        }
    }

    private void warnIfAnnotationIntentSilenced(Class<?> aggregateImplementationType,
                                                AggregateType aggregateType,
                                                boolean resolvedSnapshotEnabled,
                                                boolean resolvedClosingBooksEnabled) {
        var snapshotAnnotationEnabled = snapshotPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                                              .map(AggregateSnapshotPolicyDescriptor::policy)
                                                              .map(AggregateSnapshotPolicy::enabled)
                                                              .orElse(false);
        if (snapshotAnnotationEnabled && !resolvedSnapshotEnabled) {
            log.warn("Aggregate '{}' (aggregateType '{}') has @AggregateSnapshotPolicy(enabled=true) but the resolved configuration disables snapshots; no snapshots will be taken. " +
                             "Set 'essentials.eventstore.snapshots.enabled=true' to enable globally, " +
                             "or 'essentials.eventstore.snapshots.aggregates.{}.enabled=true' to enable just this aggregate.",
                     aggregateImplementationType.getName(),
                     aggregateType,
                     aggregateType);
        }

        var closingBooksAnnotationEnabled = closingBooksPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                                                       .map(AggregateClosingBooksPolicyDescriptor::policy)
                                                                       .map(AggregateClosingBooksPolicy::enabled)
                                                                       .orElse(false);
        if (closingBooksAnnotationEnabled && !resolvedClosingBooksEnabled) {
            log.warn("Aggregate '{}' (aggregateType '{}') has @AggregateClosingBooksPolicy(enabled=true) but the resolved configuration disables closing books; generations will not be opened or closed. " +
                             "Set 'essentials.eventstore.closing-books.enabled=true' to enable globally, " +
                             "or 'essentials.eventstore.closing-books.aggregates.{}.enabled=true' to enable just this aggregate.",
                     aggregateImplementationType.getName(),
                     aggregateType,
                     aggregateType);
        }
    }

    private void validateZoneId(Class<?> aggregateImplementationType,
                                AggregateType aggregateType,
                                ResolvedAggregateClosingBooksConfiguration resolved) {
        var zoneId = resolved.zoneId();
        if (zoneId == null || zoneId.isBlank()) {
            return;
        }
        try {
            ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            throw new IllegalStateException("Aggregate '" + aggregateImplementationType.getName() +
                                                    "' (aggregateType '" + aggregateType + "') has an invalid zoneId '" + zoneId +
                                                    "'. Set a valid IANA zone via 'essentials.eventstore.closing-books.zone-id', " +
                                                    "'essentials.eventstore.closing-books.aggregates." + aggregateType + ".zone-id', " +
                                                    "or @AggregateClosingBooksPolicy.zoneId.", e);
        }
    }

    private boolean eventThresholdRequired(ClosingBooksDefaultPolicyType policy) {
        return policy == ClosingBooksDefaultPolicyType.EVENT_COUNT
                || policy == ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY;
    }

    private boolean eventThresholdWasDefaulted(Class<?> aggregateImplementationType, AggregateType aggregateType) {
        var override = properties.getClosingBooks().getAggregates().get(aggregateType.toString());
        if (override != null && override.getEventThreshold() != null) {
            return false;
        }
        var annotationThreshold = closingBooksPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                                            .map(AggregateClosingBooksPolicyDescriptor::policy)
                                                            .map(AggregateClosingBooksPolicy::eventThreshold)
                                                            .orElse(0L);
        if (annotationThreshold > 0) {
            return false;
        }
        return properties.getClosingBooks().getEventThreshold() == null;
    }

    private boolean intervalDaysWasDefaulted(Class<?> aggregateImplementationType, AggregateType aggregateType) {
        var override = properties.getClosingBooks().getAggregates().get(aggregateType.toString());
        if (override != null && override.getIntervalDays() != null) {
            return false;
        }
        var annotationInterval = closingBooksPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                                           .map(AggregateClosingBooksPolicyDescriptor::policy)
                                                           .map(AggregateClosingBooksPolicy::intervalDays)
                                                           .orElse(0);
        if (annotationInterval > 0) {
            return false;
        }
        return properties.getClosingBooks().getIntervalDays() == null;
    }

    private boolean usesTimeBoundaryPolicy(ClosingBooksDefaultPolicyType defaultPolicy) {
        return switch (defaultPolicy) {
            case TIME_BOUNDARY, EVENT_COUNT_OR_TIME_BOUNDARY -> true;
            case EVENT_COUNT, MANUAL_ONLY, EXPLICIT_ONLY, UNSPECIFIED -> false;
        };
    }

    private boolean periodIdProvidedExternally(AggregateType aggregateType) {
        var override = properties.getClosingBooks().getAggregates().get(aggregateType.toString());
        if (override != null && override.getPeriodIdProvidedExternally() != null) {
            return override.getPeriodIdProvidedExternally();
        }
        return properties.getClosingBooks().isPeriodIdProvidedExternally();
    }

    private boolean requiresNextGenerationFactory(ClosingBooksDefaultPolicyType defaultPolicy) {
        return switch (defaultPolicy) {
            case EVENT_COUNT, TIME_BOUNDARY, EVENT_COUNT_OR_TIME_BOUNDARY -> true;
            case MANUAL_ONLY, EXPLICIT_ONLY, UNSPECIFIED -> false;
        };
    }

    private AggregateType resolveAggregateType(Class<?> aggregateImplementationType) {
        var snapshotAggregateType = snapshotPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                                          .flatMap(AggregateSnapshotPolicyDescriptor::aggregateType);
        if (snapshotAggregateType.isPresent()) {
            return AggregateType.of(snapshotAggregateType.get());
        }

        return closingBooksPolicyRegistry.findByAggregateImplementationType(aggregateImplementationType)
                                         .flatMap(AggregateClosingBooksPolicyDescriptor::aggregateType)
                                         .map(AggregateType::of)
                                         .orElseGet(() -> AggregateType.of(aggregateImplementationType.getSimpleName()));
    }
}
