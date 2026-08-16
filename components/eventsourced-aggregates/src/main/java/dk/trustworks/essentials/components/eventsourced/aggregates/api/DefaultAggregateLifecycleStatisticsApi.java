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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicyDescriptor;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicyRegistry;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyDescriptor;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyRegistry;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import io.micrometer.core.instrument.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

public class DefaultAggregateLifecycleStatisticsApi implements AggregateLifecycleStatisticsApi {
    private final EssentialsSecurityProvider securityProvider;
    private final AggregateSnapshotPolicyRegistry snapshotPolicyRegistry;
    private final AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry;
    private final Optional<MeterRegistry> meterRegistry;

    /**
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public DefaultAggregateLifecycleStatisticsApi(EssentialsSecurityProvider securityProvider,
                                                  AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                  AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry,
                                                  Optional<MeterRegistry> meterRegistry) {
        this.securityProvider = requireNonNull(securityProvider, "securityProvider must not be null");
        this.snapshotPolicyRegistry = requireNonNull(snapshotPolicyRegistry, "snapshotPolicyRegistry must not be null");
        this.closingBooksPolicyRegistry = requireNonNull(closingBooksPolicyRegistry, "closingBooksPolicyRegistry must not be null");
        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    @Override
    public List<ApiAggregateSnapshotStatistics> findAggregateSnapshotStatistics(Object principal) {
        validateReadAccess(principal);
        return snapshotPolicyRegistry.getRegisteredPolicies()
                                     .stream()
                                     .map(this::toSnapshotStatistics)
                                     .sorted(Comparator.comparing((ApiAggregateSnapshotStatistics stats) -> stats.aggregateType() != null ? stats.aggregateType().toString() : "")
                                                       .thenComparing(ApiAggregateSnapshotStatistics::aggregateImplementationType))
                                     .toList();
    }

    @Override
    public List<ApiAggregateClosingBooksStatistics> findAggregateClosingBooksStatistics(Object principal) {
        validateReadAccess(principal);
        return closingBooksPolicyRegistry.getRegisteredPolicies()
                                         .stream()
                                         .map(this::toClosingBooksStatistics)
                                         .sorted(Comparator.comparing((ApiAggregateClosingBooksStatistics stats) -> stats.aggregateType() != null ? stats.aggregateType().toString() : "")
                                                           .thenComparing(ApiAggregateClosingBooksStatistics::aggregateImplementationType))
                                         .toList();
    }

    private ApiAggregateSnapshotStatistics toSnapshotStatistics(AggregateSnapshotPolicyDescriptor descriptor) {
        var aggregateType = descriptor.aggregateType().map(AggregateType::of).orElse(null);
        var aggregateImplementationType = descriptor.aggregateImplementationType().getName();
        return new ApiAggregateSnapshotStatistics(aggregateType,
                                                  aggregateImplementationType,
                                                  timedMetrics(aggregateImplementationType,
                                                               aggregateType,
                                                               List.of("load_snapshot",
                                                                       "load_all_snapshots",
                                                                       "find_most_recent_last_included_event_order",
                                                                       "save_snapshot",
                                                                       "delete_all_snapshots",
                                                                       "delete_snapshots",
                                                                       "serialize_snapshot",
                                                                       "deserialize_snapshot"),
                                                               "essentials.aggregate_snapshot."),
                                                  counters(aggregateImplementationType,
                                                           aggregateType,
                                                           List.of("essentials.aggregate_snapshot.durable_queue.process_job.outcome"),
                                                           true),
                                                  gauges(aggregateImplementationType,
                                                         aggregateType,
                                                         List.of("essentials.aggregate_snapshot.durable_queue.queue_depth")));
    }

    private ApiAggregateClosingBooksStatistics toClosingBooksStatistics(AggregateClosingBooksPolicyDescriptor descriptor) {
        var aggregateType = descriptor.aggregateType().map(AggregateType::of).orElse(null);
        return new ApiAggregateClosingBooksStatistics(aggregateType,
                                                      descriptor.aggregateImplementationType().getName(),
                                                      // "rollover" is recorded by ClosingBooksCoordinator, so it is present for every
                                                      // trigger mode. The "scan."/"manager." entries only exist for SCHEDULED_SCAN.
                                                      timedMetrics(null,
                                                                   aggregateType,
                                                                   List.of("rollover",
                                                                           "scan.load_open_generations",
                                                                           "scan.process_generation",
                                                                           "manager.poll"),
                                                                   "essentials.aggregate_closing_books."),
                                                      counters(null,
                                                               aggregateType,
                                                               List.of("essentials.aggregate_closing_books.generations_closed",
                                                                       "essentials.aggregate_closing_books.generations_opened",
                                                                       "essentials.aggregate_closing_books.rollover.outcome",
                                                                       "essentials.aggregate_closing_books.policy.decision",
                                                                       "essentials.aggregate_closing_books.manager.poll.outcome",
                                                                       "essentials.aggregate_closing_books.scan.process_generation.outcome",
                                                                       "essentials.closing_books.time_boundary_gap_detected"),
                                                               true),
                                                      gauges(null,
                                                             aggregateType,
                                                             List.of("essentials.aggregate_closing_books.last_rollover_epoch_ms")));
    }

    private Map<String, ApiTimedMetricStatistics> timedMetrics(String aggregateImplementationType,
                                                               AggregateType aggregateType,
                                                               List<String> suffixes,
                                                               String prefix) {
        var registry = meterRegistry.orElse(null);
        if (registry == null) {
            return Map.of();
        }

        var metrics = new LinkedHashMap<String, ApiTimedMetricStatistics>();
        for (var suffix : suffixes) {
            var metricName = prefix + suffix;
            var timers = registry.find(metricName).timers().stream()
                                 .filter(timer -> matches(timer.getId(), aggregateImplementationType, aggregateType))
                                 .toList();
            if (timers.isEmpty()) {
                continue;
            }
            long count = 0L;
            double totalTimeMs = 0D;
            double maxTimeMs = 0D;
            for (var timer : timers) {
                count += timer.count();
                totalTimeMs += timer.totalTime(TimeUnit.MILLISECONDS);
                maxTimeMs = Math.max(maxTimeMs, timer.max(TimeUnit.MILLISECONDS));
            }
            metrics.put(suffix, new ApiTimedMetricStatistics(count, totalTimeMs, maxTimeMs));
        }
        return Map.copyOf(metrics);
    }

    private Map<String, Long> counters(String aggregateImplementationType,
                                       AggregateType aggregateType,
                                       List<String> metricNames,
                                       boolean includeMeterNameInKey) {
        var registry = meterRegistry.orElse(null);
        if (registry == null) {
            return Map.of();
        }

        var values = new LinkedHashMap<String, Long>();
        for (var metricName : metricNames) {
            for (var counter : registry.find(metricName).counters()) {
                if (!matches(counter.getId(), aggregateImplementationType, aggregateType)) {
                    continue;
                }
                var key = includeMeterNameInKey ? metricName + tagsSuffix(counter.getId()) : keyFromTags(counter.getId());
                values.merge(key, Math.round(counter.count()), Long::sum);
            }
        }
        return Map.copyOf(values);
    }

    private Map<String, Double> gauges(String aggregateImplementationType,
                                       AggregateType aggregateType,
                                       List<String> metricNames) {
        var registry = meterRegistry.orElse(null);
        if (registry == null) {
            return Map.of();
        }

        var values = new LinkedHashMap<String, Double>();
        for (var metricName : metricNames) {
            for (var gauge : registry.find(metricName).gauges()) {
                if (!matches(gauge.getId(), aggregateImplementationType, aggregateType)) {
                    continue;
                }
                values.put(metricName + tagsSuffix(gauge.getId()), gauge.value());
            }
        }
        return Map.copyOf(values);
    }

    private boolean matches(Meter.Id id, String aggregateImplementationType, AggregateType aggregateType) {
        var idAggregateImplType = id.getTag("aggregate_impl_type");
        var idAggregateType = id.getTag("aggregate_type");
        if (aggregateImplementationType != null && !aggregateImplementationType.equals(idAggregateImplType)) {
            return false;
        }
        return aggregateType == null || aggregateType.toString().equals(idAggregateType);
    }

    private String keyFromTags(Meter.Id id) {
        var tags = id.getTags().stream()
                     .filter(tag -> !tag.getKey().equals("aggregate_type"))
                     .filter(tag -> !tag.getKey().equals("aggregate_impl_type"))
                     .toList();
        if (tags.isEmpty()) {
            return "count";
        }
        return tags.stream()
                   .map(tag -> tag.getKey() + "=" + tag.getValue())
                   .sorted()
                   .reduce((left, right) -> left + "," + right)
                   .orElse("count");
    }

    private String tagsSuffix(Meter.Id id) {
        var key = keyFromTags(id);
        return key.equals("count") ? "" : "[" + key + "]";
    }

    private void validateReadAccess(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);
    }

    /**
     * Creates a builder for a {@link DefaultAggregateLifecycleStatisticsApi}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DefaultAggregateLifecycleStatisticsApi}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder {
        private EssentialsSecurityProvider securityProvider;
        private AggregateSnapshotPolicyRegistry snapshotPolicyRegistry;
        private AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry;
        private MeterRegistry meterRegistry;

        /**
         * @param securityProvider required
         * @return this builder
         */
        public Builder setSecurityProvider(EssentialsSecurityProvider securityProvider) {
            this.securityProvider = securityProvider;
            return this;
        }

        /**
         * @param snapshotPolicyRegistry required
         * @return this builder
         */
        public Builder setSnapshotPolicyRegistry(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry) {
            this.snapshotPolicyRegistry = snapshotPolicyRegistry;
            return this;
        }

        /**
         * @param closingBooksPolicyRegistry required
         * @return this builder
         */
        public Builder setClosingBooksPolicyRegistry(AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry) {
            this.closingBooksPolicyRegistry = closingBooksPolicyRegistry;
            return this;
        }

        /**
         * @param meterRegistry optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setMeterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry}.
         *
         * @param meterRegistry the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
            requireNonNull(meterRegistry, "No meterRegistry provided");
            return setMeterRegistry(meterRegistry.orElse(null));
        }

        /**
         * @return the new {@link DefaultAggregateLifecycleStatisticsApi}
         */
        @SuppressWarnings("removal")
        public DefaultAggregateLifecycleStatisticsApi build() {
            return new DefaultAggregateLifecycleStatisticsApi(securityProvider,
                                                              snapshotPolicyRegistry,
                                                              closingBooksPolicyRegistry,
                                                              Optional.ofNullable(meterRegistry));
        }
    }

}
