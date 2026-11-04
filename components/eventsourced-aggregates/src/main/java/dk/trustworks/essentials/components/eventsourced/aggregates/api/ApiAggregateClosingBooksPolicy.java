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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

public record ApiAggregateClosingBooksPolicy(
        AggregateType aggregateType,
        String aggregateImplementationType,
        boolean enabled,
        ClosingBooksTriggerMode triggerMode,
        ClosingBooksDefaultPolicyType defaultPolicy,
        long eventThreshold,
        ClosingBooksTimeBoundary timeBoundary,
        String zoneId,
        int intervalDays,
        ClosingBooksPeriodSource periodSource
) {
    /**
     * The constructor before {@code periodSource} was added; the period source defaults to
     * {@link ClosingBooksPeriodSource#CLOCK}.
     *
     * @deprecated use {@link #builder()}, which also takes the {@code periodSource}
     */
    @Deprecated(forRemoval = true, since = "0.61.0")
    public ApiAggregateClosingBooksPolicy(AggregateType aggregateType,
                                          String aggregateImplementationType,
                                          boolean enabled,
                                          ClosingBooksTriggerMode triggerMode,
                                          ClosingBooksDefaultPolicyType defaultPolicy,
                                          long eventThreshold,
                                          ClosingBooksTimeBoundary timeBoundary,
                                          String zoneId,
                                          int intervalDays) {
        this(aggregateType, aggregateImplementationType, enabled, triggerMode, defaultPolicy, eventThreshold,
             timeBoundary, zoneId, intervalDays, ClosingBooksPeriodSource.CLOCK);
    }

    public static ApiAggregateClosingBooksPolicy from(AggregateClosingBooksPolicyDescriptor descriptor) {
        var policy = descriptor.policy();
        return new ApiAggregateClosingBooksPolicy(descriptor.aggregateType().map(AggregateType::of).orElse(null),
                                                  descriptor.aggregateImplementationType().getName(),
                                                  policy.enabled(),
                                                  policy.triggerMode(),
                                                  policy.defaultPolicy(),
                                                  policy.eventThreshold(),
                                                  policy.timeBoundary(),
                                                  policy.zoneId(),
                                                  policy.intervalDays(),
                                                  policy.periodSource());
    }

    /**
     * @return a builder; {@code periodSource} defaults to {@link ClosingBooksPeriodSource#CLOCK}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link ApiAggregateClosingBooksPolicy}, obtained from {@link #builder()}.
     */
    public static final class Builder {
        private AggregateType aggregateType;
        private String aggregateImplementationType;
        private boolean enabled = false;
        private ClosingBooksTriggerMode triggerMode;
        private ClosingBooksDefaultPolicyType defaultPolicy;
        private long eventThreshold = 0;
        private ClosingBooksTimeBoundary timeBoundary;
        private String zoneId;
        private int intervalDays = 0;
        private ClosingBooksPeriodSource periodSource = ClosingBooksPeriodSource.CLOCK;

        public Builder setAggregateType(AggregateType aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder setAggregateImplementationType(String aggregateImplementationType) {
            this.aggregateImplementationType = aggregateImplementationType;
            return this;
        }

        public Builder setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder setTriggerMode(ClosingBooksTriggerMode triggerMode) {
            this.triggerMode = triggerMode;
            return this;
        }

        public Builder setDefaultPolicy(ClosingBooksDefaultPolicyType defaultPolicy) {
            this.defaultPolicy = defaultPolicy;
            return this;
        }

        public Builder setEventThreshold(long eventThreshold) {
            this.eventThreshold = eventThreshold;
            return this;
        }

        public Builder setTimeBoundary(ClosingBooksTimeBoundary timeBoundary) {
            this.timeBoundary = timeBoundary;
            return this;
        }

        public Builder setZoneId(String zoneId) {
            this.zoneId = zoneId;
            return this;
        }

        public Builder setIntervalDays(int intervalDays) {
            this.intervalDays = intervalDays;
            return this;
        }

        public Builder setPeriodSource(ClosingBooksPeriodSource periodSource) {
            this.periodSource = periodSource;
            return this;
        }

        public ApiAggregateClosingBooksPolicy build() {
            return new ApiAggregateClosingBooksPolicy(aggregateType, aggregateImplementationType, enabled, triggerMode, defaultPolicy, eventThreshold, timeBoundary, zoneId, intervalDays, periodSource);
        }
    }
}
