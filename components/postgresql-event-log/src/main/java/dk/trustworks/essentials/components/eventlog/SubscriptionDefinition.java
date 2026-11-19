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
package dk.trustworks.essentials.components.eventlog;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What a subscription is. The progress model is not a field: it follows from the handler kind
 * ({@link PeriodicHandler} or {@link PageHandler}).
 *
 * @param subscriber    the subscriber name: 1 to 100 letters, digits, {@code _ - . :}; shown in admin, logged, keyed
 * @param aggregateType the registered aggregate type
 * @param ownership     {@link Ownership#exclusive()} or {@link Ownership#perInstance(String)}
 * @param startFrom     where a subscription whose progress row does not exist yet starts
 * @param consistency   {@link ConsistencyMode#FRESH} or {@link ConsistencyMode#CONTIGUOUS}
 * @param eventTypes    the event types delivered; the others count as handled
 * @param tenant        only events of this tenant are delivered; the others count as handled (in memory)
 * @param errors        the subscription's retry, backoff and page size; empty for {@link ErrorSettings#defaults(EventLogSettings)}
 */
public record SubscriptionDefinition(String subscriber, String aggregateType, Ownership ownership, StartFrom startFrom, ConsistencyMode consistency,
                                     EventTypeFilter eventTypes, Optional<String> tenant, Optional<ErrorSettings> errors) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:\\-]{1,100}");

    public SubscriptionDefinition {
        requireNonNull(subscriber, "No subscriber provided");
        if (!NAME.matcher(subscriber).matches()) {
            throw new IllegalArgumentException("Invalid subscriber name '" + subscriber + "': 1 to 100 letters, digits, _ - . :");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("No aggregateType provided");
        }
        requireNonNull(ownership, "No ownership provided");
        requireNonNull(startFrom, "No startFrom provided");
        requireNonNull(consistency, "No consistency provided");
        requireNonNull(eventTypes, "No eventTypes provided");
        requireNonNull(tenant, "No tenant provided");
        requireNonNull(errors, "No errors provided");
        if (tenant.isPresent() && tenant.get().isEmpty()) {
            throw new IllegalArgumentException("An empty tenant; use Optional.empty() for every tenant");
        }
    }

    /**
     * @return a builder with the defaults: exclusive, from the beginning, {@code FRESH}, every event type, every tenant,
     * the event log's error settings
     */
    public static Builder builder(String subscriber, String aggregateType) {
        return new Builder(subscriber, aggregateType);
    }

    /**
     * @return the filter digest stored on the progress row: {@code null} without an event-type or tenant
     * filter, else the hex SHA-256 of the sorted event types and the tenant
     */
    public String filterDigest() {
        return filterDigest(eventTypes, tenant);
    }

    static String filterDigest(EventTypeFilter eventTypes, Optional<String> tenant) {
        if (eventTypes.matchesAll() && tenant.isEmpty()) {
            return null;
        }
        var text = new StringBuilder("types:");
        if (eventTypes.matchesAll()) {
            text.append('*');
        } else {
            new TreeSet<>(eventTypes.eventTypes()).forEach(type -> text.append(type.length()).append(':').append(type).append(';'));
        }
        text.append("\ntenant:").append(tenant.map(t -> t.length() + ":" + t).orElse("*"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Builder {
        private final String          subscriber;
        private final String          aggregateType;
        private Ownership             ownership   = Ownership.exclusive();
        private StartFrom             startFrom   = StartFrom.beginning();
        private ConsistencyMode       consistency = ConsistencyMode.FRESH;
        private EventTypeFilter       eventTypes  = EventTypeFilter.all();
        private Optional<String>      tenant      = Optional.empty();
        private Optional<ErrorSettings> errors    = Optional.empty();

        private Builder(String subscriber, String aggregateType) {
            this.subscriber = subscriber;
            this.aggregateType = aggregateType;
        }

        public Builder ownership(Ownership ownership) {
            this.ownership = ownership;
            return this;
        }

        public Builder exclusive() {
            return ownership(Ownership.exclusive());
        }

        public Builder perInstance(String instanceName) {
            return ownership(Ownership.perInstance(instanceName));
        }

        public Builder startFrom(StartFrom startFrom) {
            this.startFrom = startFrom;
            return this;
        }

        public Builder consistency(ConsistencyMode consistency) {
            this.consistency = consistency;
            return this;
        }

        public Builder eventTypes(EventTypeFilter eventTypes) {
            this.eventTypes = eventTypes;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = Optional.ofNullable(tenant);
            return this;
        }

        public Builder errors(ErrorSettings errors) {
            this.errors = Optional.ofNullable(errors);
            return this;
        }

        public SubscriptionDefinition build() {
            return new SubscriptionDefinition(subscriber, aggregateType, ownership, startFrom, consistency, eventTypes, tenant, errors);
        }
    }
}
