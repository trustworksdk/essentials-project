/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.Lifecycle;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Caches per-aggregate-type {@link AggregateSnapshotRepository} instances produced by an
 * {@link AggregateSnapshotRepositoryFactory}.
 * <p>
 * The provider is a {@link Lifecycle} bean. Repos that are themselves {@link Lifecycle}-aware
 * (e.g. {@link AsyncAggregateSnapshotRepository}) are started lazily when first resolved and
 * stopped together when the provider stops. Spring's {@code DefaultLifecycleManager} drives
 * provider lifecycle on context start/stop; tests must call {@code start()}/{@code stop()}
 * explicitly.
 */
public class CachingAggregateSnapshotRepositoryProvider implements AggregateSnapshotRepositoryProvider, Lifecycle {
    private final AggregateSnapshotRepositoryFactory                 factory;
    private final Map<String, Optional<AggregateSnapshotRepository>> cache   = new ConcurrentHashMap<>();
    private final AtomicBoolean                                      started = new AtomicBoolean();

    public CachingAggregateSnapshotRepositoryProvider(AggregateSnapshotRepositoryFactory factory) {
        this.factory = requireNonNull(factory, "No factory provided");
    }

    @Override
    public Optional<AggregateSnapshotRepository> resolve(AggregateType aggregateType,
                                                         Class<?> aggregateImplementationType) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        return cache.computeIfAbsent(cacheKey(aggregateType, aggregateImplementationType),
                                     ignored -> {
                                         var resolved = factory.create(aggregateType, aggregateImplementationType);
                                         // If the provider is already started, propagate to the new repo so callers
                                         // never observe an unstarted repo. If not started yet, the start() pass will
                                         // pick it up.
                                         if (started.get()) {
                                             resolved.filter(repo -> repo instanceof Lifecycle)
                                                     .ifPresent(repo -> ((Lifecycle) repo).start());
                                         }
                                         return resolved;
                                     });
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        cache.values().stream()
             .flatMap(Optional::stream)
             .filter(repo -> repo instanceof Lifecycle)
             .map(Lifecycle.class::cast)
             .filter(lifecycle -> !lifecycle.isStarted())
             .forEach(Lifecycle::start);
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        cache.values().stream()
             .flatMap(Optional::stream)
             .filter(repo -> repo instanceof Lifecycle)
             .map(Lifecycle.class::cast)
             .filter(Lifecycle::isStarted)
             .forEach(Lifecycle::stop);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    private String cacheKey(AggregateType aggregateType, Class<?> aggregateImplementationType) {
        return aggregateType + "::" + aggregateImplementationType.getName();
    }
}
