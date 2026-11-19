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

package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A read model kept in the event log's own database (design phase 8 §3.3): a {@code CONTIGUOUS}, exclusive,
 * in-transaction subscription whose handler writes the view in the page transaction that also moves the resume point,
 * so the view always matches its resume point (I12). Failures, retries, page shrinking and parking are the engine's;
 * the projection catches nothing (I14).
 *
 * <pre>{@code
 * var projection = ViewProjection.builder("order-summary", "Orders")
 *         .onPage((events, ctx) -> upsertAll(ctx.handle(), events))
 *         .transactions(new UnitOfWorkPageTransactions(unitOfWorkFactory))
 *         .build(eventLog, serializer);
 * projection.start();
 * }</pre>
 */
public final class ViewProjection {
    private static final Logger log = LoggerFactory.getLogger(ViewProjection.class);

    private final EventLog                     eventLog;
    private final SubscriptionDefinition       definition;
    private final PageTransactionSource        source;
    private final EventDeserializer            deserializer;
    private final PageApplier                  pageApplier;
    private final Dispatch<EventApplier<?>>    dispatch;
    private final ProcessorSeams               seams;
    private final LifecycleGate                gate = new LifecycleGate();
    private volatile SubscriptionHandle        handle;

    private ViewProjection(EventLog eventLog, SubscriptionDefinition definition, PageTransactionSource source, EventDeserializer deserializer,
                           PageApplier pageApplier, Dispatch<EventApplier<?>> dispatch, ProcessorSeams seams) {
        this.eventLog = eventLog;
        this.definition = definition;
        this.source = source;
        this.deserializer = deserializer;
        this.pageApplier = pageApplier;
        this.dispatch = dispatch;
        this.seams = seams;
    }

    public static Builder builder(String subscriber, String aggregateType) {
        return new Builder(subscriber, aggregateType);
    }

    /**
     * Start delivering (an exclusive projection may start as a standby). Idempotent while running; waits for a stop or
     * rebuild in progress (see {@link LifecycleGate})
     */
    public ViewProjection start() throws SQLException {
        try (var ignored = gate.enter()) {
            subscribeIfNotRunning();
        }
        return this;
    }

    /** Stop delivering; an exclusive projection releases its lease. Idempotent */
    public void stop() {
        try (var ignored = gate.enter()) {
            stopSubscription();
        }
    }

    private void subscribeIfNotRunning() throws SQLException {
        if (!isRunning()) {
            handle = seams.subscriber().subscribe(eventLog, definition, (PageHandler) this::handlePage, source);
        }
    }

    private void stopSubscription() {
        var current = handle;
        if (current != null) {
            current.stop();
        }
    }

    public boolean isRunning() {
        var current = handle;
        return current != null && current.isRunning();
    }

    public SubscriptionDefinition definition() {
        return definition;
    }

    /** @return the running subscription, for its status and parked-event futures */
    public Optional<SubscriptionHandle> handle() {
        return Optional.ofNullable(handle);
    }

    public ProgressKey key() {
        return ProgressKey.of(definition);
    }

    /**
     * Rebuild (§3.3, §4.2): stop, then in one transaction of the projection's page source run {@code truncate} and reset
     * the subscription to its start ({@link StartFrom#globalOrder}, else {@code 1}), commit, and start again if it ran. At
     * every point the view matches its resume point. Needs an application page source; with
     * {@link PageTransactionSource#engine()} use {@link #rebuild(DataSource, Truncate)}.
     *
     * @return {@code false} if the subscription has no progress row yet (nothing to rebuild; {@code truncate} rolled back)
     */
    public boolean rebuild(Truncate truncate) throws SQLException {
        if (source == PageTransactionSource.engine()) {
            throw new IllegalStateException("rebuild needs the projection's own page source; with PageTransactionSource.engine() call rebuild(DataSource, truncate)");
        }
        return rebuild(source, truncate);
    }

    /** {@link #rebuild(Truncate)} in a transaction on a connection of {@code dataSource}, the event log's database */
    public boolean rebuild(DataSource dataSource, Truncate truncate) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        return rebuild(() -> {
            var connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new PageTransactionSource.OpenPage() {
                public Connection connection() {
                    return connection;
                }

                public <T> T resource(Class<T> type) {
                    throw new IllegalArgumentException("No " + type.getName());
                }

                public void commit() throws SQLException {
                    try (connection) {
                        connection.commit();
                    }
                }

                public void rollback() {
                    try (connection) {
                        connection.rollback();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }, truncate);
    }

    /** One {@link LifecycleGate} transition: a concurrent {@link #start()} cannot resume the projection mid-rebuild */
    private boolean rebuild(PageTransactionSource in, Truncate truncate) throws SQLException {
        requireNonNull(truncate, "No truncate provided");
        try (var ignored = gate.enter()) {
            return rebuildStopped(in, truncate);
        }
    }

    private boolean rebuildStopped(PageTransactionSource in, Truncate truncate) throws SQLException {
        var wasRunning = isRunning();
        stopSubscription();
        var open = in.begin();
        boolean reset;
        try {
            truncate.truncate(open.connection());
            reset = resetProgress(open.connection());
        } catch (Exception e) {
            open.rollback();
            if (e instanceof SQLException sql) {
                throw sql;
            }
            throw new IllegalStateException("Rebuilding " + key() + " failed", e);
        }
        if (reset) {
            open.commit();
        } else {
            open.rollback();
        }
        if (wasRunning) {
            subscribeIfNotRunning();
        }
        return reset;
    }

    /**
     * Reset the subscription to its start ({@link StartFrom#globalOrder}, else {@code 1}) on the caller's transaction,
     * which is neither committed nor rolled back here. For a caller that has stopped the projection and clears the view
     * in the same transaction ({@link EventLogViewProcessor#rebuild()})
     *
     * @return {@code false} if the subscription has no progress row yet
     */
    boolean resetProgress(Connection connection) throws SQLException {
        var from = definition.startFrom() instanceof StartFrom.GlobalOrder order ? order.value() : 1L;
        return eventLog.admin().reset(key(), from, connection);
    }

    private void handlePage(List<StoredEvent> page, PageTransaction transaction) throws Exception {
        var context = new ProjectionContext(transaction);
        if (pageApplier != null) {
            var events = new ArrayList<DeliveredEvent>(page.size());
            for (var event : page) {
                events.add(deserialize(event));
            }
            try {
                pageApplier.apply(events, context);
            } catch (Exception e) {
                if (!seams.has(IntegrationMutant.PROCESSOR_SWALLOWS_FAILURE)) {
                    throw e;
                }
                log.warn("[{}] MUTANT PROCESSOR_SWALLOWS_FAILURE: page failure swallowed", definition.subscriber(), e);
            }
            return;
        }
        for (var stored : page) {
            var event   = deserialize(stored);
            var applier = dispatch.handlerFor(event.payload().getClass());
            if (applier.isEmpty()) {
                continue;
            }
            try {
                applier.get().apply(event, context);
            } catch (Exception e) {
                if (!seams.has(IntegrationMutant.PROCESSOR_SWALLOWS_FAILURE)) {
                    throw e;
                }
                log.warn("[{}] MUTANT PROCESSOR_SWALLOWS_FAILURE: failure of {} swallowed", definition.subscriber(), stored.globalOrder(), e);
            }
        }
    }

    private DeliveredEvent deserialize(StoredEvent event) throws SQLException {
        return Processors.deserialize(deserializer, event, seams);
    }

    @Override
    public String toString() {
        return "ViewProjection[" + definition.subscriber() + " on " + definition.aggregateType() + "]";
    }

    /** Applies a page of events, in {@code global_order}, inside the page transaction */
    @FunctionalInterface
    public interface PageApplier {
        void apply(List<DeliveredEvent> events, ProjectionContext context) throws Exception;
    }

    /** Applies one event inside the page transaction */
    @FunctionalInterface
    public interface EventApplier<T> {
        void apply(DeliveredEvent event, ProjectionContext context) throws Exception;
    }

    /** Empties the view on the rebuild transaction's connection */
    @FunctionalInterface
    public interface Truncate {
        void truncate(Connection connection) throws Exception;
    }

    public static final class Builder {
        private final SubscriptionDefinition.Builder             definition;
        private final String                                     subscriber;
        private final Map<Class<?>, EventApplier<?>>             appliers = new LinkedHashMap<>();
        private PageApplier                                      pageApplier;
        private PageTransactionSource                            source   = PageTransactionSource.engine();
        private boolean                                          strict;
        private ProcessorSeams                                   seams    = ProcessorSeams.NONE;

        private Builder(String subscriber, String aggregateType) {
            this.subscriber = subscriber;
            this.definition = SubscriptionDefinition.builder(subscriber, aggregateType).consistency(ConsistencyMode.CONTIGUOUS).exclusive();
        }

        /** Set-based writes: the whole page at once. Excludes {@link #on} */
        public Builder onPage(PageApplier applier) {
            this.pageApplier = requireNonNull(applier, "No applier provided");
            return this;
        }

        /** One event at a time, dispatched on the payload's class and its supertypes. Excludes {@link #onPage} */
        public <T> Builder on(Class<T> payloadType, EventApplier<T> applier) {
            requireNonNull(payloadType, "No payloadType provided");
            requireNonNull(applier, "No applier provided");
            if (appliers.putIfAbsent(payloadType, applier) != null) {
                throw new IllegalArgumentException("A second applier for " + payloadType.getName());
            }
            return this;
        }

        /** Page transactions from {@code source}; {@link PageTransactionSource#engine()} by default */
        public Builder transactions(PageTransactionSource source) {
            this.source = requireNonNull(source, "No source provided");
            return this;
        }

        /** {@code CONTIGUOUS} by default (D1) */
        public Builder consistency(ConsistencyMode consistency) {
            definition.consistency(consistency);
            return this;
        }

        /** Exclusive by default */
        public Builder ownership(Ownership ownership) {
            definition.ownership(ownership);
            return this;
        }

        public Builder startFrom(StartFrom startFrom) {
            definition.startFrom(startFrom);
            return this;
        }

        public Builder eventTypes(EventTypeFilter eventTypes) {
            definition.eventTypes(eventTypes);
            return this;
        }

        public Builder tenant(String tenant) {
            definition.tenant(tenant);
            return this;
        }

        public Builder errors(ErrorSettings errors) {
            definition.errors(errors);
            return this;
        }

        /** A payload type with no applier fails the page as a handler failure, instead of counting as handled */
        public Builder strict() {
            this.strict = true;
            return this;
        }

        Builder seams(ProcessorSeams seams) {
            this.seams = requireNonNull(seams, "No seams provided");
            return this;
        }

        public ViewProjection build(EventLog eventLog, JSONEventSerializer serializer) {
            return build(eventLog, EventDeserializer.of(serializer));
        }

        public ViewProjection build(EventLog eventLog, EventDeserializer deserializer) {
            requireNonNull(eventLog, "No eventLog provided");
            requireNonNull(deserializer, "No deserializer provided");
            if ((pageApplier == null) == appliers.isEmpty()) {
                throw new IllegalStateException("A ViewProjection needs either onPage(...) or at least one on(...), not both");
            }
            return new ViewProjection(eventLog, definition.build(), source, deserializer, pageApplier, new Dispatch<>(appliers, strict, subscriber), seams);
        }
    }
}
