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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal.GapEnds;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.jdbi.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.collections.Lists;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Postgresql specific version of the {@link EventStreamGapHandler}, which will maintain per {@link SubscriberId} transient gaps
 * and permanent gaps (across all subscribers) in the {@link #TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME} and {@link #PERMANENT_GAPS_TABLE_NAME}
 * <p>
 * <b>Use the {@link EventStoreUnitOfWorkFactory} the {@link PostgresqlEventStore} uses.</b> A subscriber acknowledges a gap fill
 * inside its own {@link UnitOfWork}, and the gap handler resolves the fill's transient gap in <i>that</i> unit of work, so that
 * handling the event and resolving its gap commit or roll back together. That only holds when the unit of work factory given
 * here is the event store's (or shares its transaction, as two Spring transaction-aware factories on one transaction manager do).
 * With another factory the gap handler finds no current unit of work of its own and resolves the gap in a separate transaction
 * that commits <i>before</i> the subscriber's does: a crash in between loses the fill (its gap is gone, the event was not handled).
 * The handler cannot see the event store at construction, so it detects this the first time it is called outside a unit of work
 * of its factory, and then logs a one-time WARN.
 *
 * @param <CONFIG> The concrete {@link AggregateEventStreamConfiguration}
 */
public final class PostgresqlEventStreamGapHandler<CONFIG extends AggregateEventStreamConfiguration> implements EventStreamGapHandler<CONFIG>, EssentialsSchemaContributor {
    private static final Logger                                               log                                  = LoggerFactory.getLogger(PostgresqlEventStreamGapHandler.class);
    public static final  String                                               TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME = "transient_subscriber_gaps";
    private static final String                                               TRANSIENT_SUBSCRIBER_GAPS_INDEX_NAME = "transient_subscriber_gaps_index";
    public static final  String                                               PERMANENT_GAPS_TABLE_NAME            = "permanent_gaps";
    public static final  List<GlobalEventOrder>                               NO_GAPS                              = List.of();
    private final        EventStoreUnitOfWorkFactory<?>                       unitOfWorkFactory;
    private final        ResolveTransientGapsToIncludeInQueryStrategy         resolveTransientGapsToIncludeInQueryStrategy;
    private final        ResolveTransientGapsToPermanentGapsPromotionStrategy resolveTransientGapsToPermanentGapsPromotionStrategy;
    private              long                                                 refreshTransientGapsFromStorageEverySeconds;
    private final        AtomicBoolean                                        warnedAboutUnitOfWorkOutsideCurrent  = new AtomicBoolean();

    /**
     * The default {@link ResolveTransientGapsToIncludeInQueryStrategy}: every open transient gap while there are at most
     * 50, and beyond that the 20 highest, the 10 lowest and a window of 20 of the ones in between that rotates on every
     * poll. A subscription keeps its own rotation - see {@link #SUBSCRIPTION_ROTATIONS} and {@link TransientGapsQuerySelection}.
     */
    private static final ResolveTransientGapsToIncludeInQueryStrategy DEFAULT_RESOLVE_TRANSIENT_GAPS_TO_INCLUDE_IN_QUERY_STRATEGY = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();

    /**
     * The rotations of the subscription whose {@link PostgresqlSubscriptionGapHandler} is asking its include strategy for
     * gaps - bound for the duration of that call, on the thread making it. A default selection ({@link ResolveTransientGapsToIncludeInQueryStrategy#defaultSelection()})
     * rotates with these when bound, so every subscription keeps a rotation of its own whether the strategy is passed as
     * is or wrapped or called by a custom one: the strategy interface is not told which subscription asks, and a type
     * check for the default instance stopped applying as soon as it was decorated. Keyed by the default selection
     * instance as well as the aggregate type, so a custom strategy that composes two default selections keeps a rotation
     * per selection. Outside such a call - the strategy called directly, or on another thread - it rotates with its own.
     */
    private static final ScopedValue<ConcurrentMap<RotationKey, TransientGapsQuerySelection>> SUBSCRIPTION_ROTATIONS = ScopedValue.newInstance();

    /**
     * The most global orders bound into one {@code IN (...)} list: PostgreSQL caps a statement at 65 535 bind parameters,
     * and {@link SubscriptionGapHandler#giveUpTransientGaps(AggregateType, List)} is given one order per missing event.
     * Longer lists are deleted in several statements in the same transaction. The CDC event store gives up ranges
     * instead ({@link SubscriptionGapHandler#giveUpTransientGapRanges}), one statement whatever their width.
     */
    static final int MAX_GLOBAL_ORDERS_PER_STATEMENT = 1_000;

    /**
     * {@code ranges} - closed - keyed by where each starts, for {@link #covers}
     */
    private static NavigableMap<Long, Long> rangesByFromInclusive(List<LongRange> ranges) {
        var byFromInclusive = new TreeMap<Long, Long>();
        ranges.forEach(range -> byFromInclusive.merge(range.fromInclusive, range.getToInclusive(), Math::max));
        return byFromInclusive;
    }

    /**
     * Whether one of the ranges of {@link #rangesByFromInclusive} covers {@code order}. Correct for ranges that overlap only
     * when no range lies inside another one that starts lower - the ranges a subscription gives up are disjoint
     */
    private static boolean covers(NavigableMap<Long, Long> rangesByFromInclusive, long order) {
        var startingAtOrBelow = rangesByFromInclusive.floorEntry(order);
        return startingAtOrBelow != null && startingAtOrBelow.getValue() >= order;
    }

    /**
     * Default configuration, which promotes transient gaps to permanent gaps after 120 seconds, and asks each poll for
     * a bounded selection of a subscriber's transient gaps again: all of them while there are at most 50, beyond that
     * the 20 highest (where a late commit lands), the 10 lowest (the next to be promoted) and a rotating window of 20 of
     * the ones in between - so every open gap is asked for again well before it is promoted, however many are open.
     *
     * @param unitOfWorkFactory the unit of work factory that coordinates the event store {@link UnitOfWork}
     */
    public PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory<?> unitOfWorkFactory) {
        this(unitOfWorkFactory, SchemaOwnership.COMPONENT);
    }

    /**
     * The default configuration of {@link #PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory)}, plus who
     * creates the gap tables.
     *
     * @param unitOfWorkFactory the unit of work factory that coordinates the event store {@link UnitOfWork}
     * @param schemaOwnership   {@link SchemaOwnership#COMPONENT} creates the gap tables now; {@link SchemaOwnership#HARNESS}
     *                          leaves them to an {@link EssentialsSchemaHarness}
     */
    public PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory<?> unitOfWorkFactory, SchemaOwnership schemaOwnership) {
        this(unitOfWorkFactory,
             Duration.ofSeconds(60),
             DEFAULT_RESOLVE_TRANSIENT_GAPS_TO_INCLUDE_IN_QUERY_STRATEGY,
             ResolveTransientGapsToPermanentGapsPromotionStrategy.thresholdBased(120),
             schemaOwnership);
    }

    /**
     * @param unitOfWorkFactory                                    the unit of work factory that coordinates the event store {@link UnitOfWork} - must be the event store's own factory (see the class documentation)
     * @param refreshTransientGapsFromStorageInterval              how often should transient gaps be refreshed from database. This is a simple low overhead effort to keep local caches of transient gaps
     *                                                             eventually in sync across multiple nodes. Any new transient gaps detected, resolved/deleted are always reflected in the underlying database which is the ultimate
     *                                                             source of truth. If an event stream subscriber, managed through the {@link EventStoreSubscriptionManager} is using an exclusive (i.e. single node) subscription then
     *                                                             the local transient gap will always be in sync with the database. For other subscriptions forms, the local cache will be eventually consistent with the database. This will
     *                                                             mean that certain nodes may try to include a transient gap AFTER another node have promoted the gap to be a permanent gap (and the same applies if a permanent gap is removed
     *                                                             using e.g. {@link EventStreamGapHandler#resetPermanentGapsFor(AggregateType)})
     * @param resolveTransientGapsToIncludeInQueryStrategy         strategy that determines how many and which gaps, among all the transient gaps detected for the given subscriber,
     *                                                             should be included from {@link SubscriptionGapHandler#findTransientGapsToIncludeInQuery(AggregateType, LongRange)}
     *                                                             (which is called from {@link PostgresqlEventStore#pollEvents(AggregateType, long, Optional, Optional, Optional, Optional, Optional)}).
     *                                                             To keep the default selection of 50 gaps (see {@link ResolveTransientGapsToIncludeInQueryStrategy#defaultSelection()}) pass that, or compose it in your own strategy.
     *                                                             The gaps the {@code resolveTransientGapsToPermanentGapsPromotionStrategy} is about to promote are always added to the query, whatever this strategy returns
     * @param resolveTransientGapsToPermanentGapsPromotionStrategy strategy for when the {@link PostgresqlEventStreamGapHandler} will promote a transient gap to a permanent gap
     */
    public PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory<?> unitOfWorkFactory,
                                           Duration refreshTransientGapsFromStorageInterval,
                                           ResolveTransientGapsToIncludeInQueryStrategy resolveTransientGapsToIncludeInQueryStrategy,
                                           ResolveTransientGapsToPermanentGapsPromotionStrategy resolveTransientGapsToPermanentGapsPromotionStrategy) {
        this(unitOfWorkFactory, refreshTransientGapsFromStorageInterval, resolveTransientGapsToIncludeInQueryStrategy, resolveTransientGapsToPermanentGapsPromotionStrategy,
             SchemaOwnership.COMPONENT);
    }

    /**
     * Same as {@link #PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory, Duration, ResolveTransientGapsToIncludeInQueryStrategy, ResolveTransientGapsToPermanentGapsPromotionStrategy)},
     * plus who creates the gap tables: {@link SchemaOwnership#COMPONENT} creates them now, {@link SchemaOwnership#HARNESS}
     * leaves them to an {@link EssentialsSchemaHarness}.
     */
    public PostgresqlEventStreamGapHandler(EventStoreUnitOfWorkFactory<?> unitOfWorkFactory,
                                           Duration refreshTransientGapsFromStorageInterval,
                                           ResolveTransientGapsToIncludeInQueryStrategy resolveTransientGapsToIncludeInQueryStrategy,
                                           ResolveTransientGapsToPermanentGapsPromotionStrategy resolveTransientGapsToPermanentGapsPromotionStrategy,
                                           SchemaOwnership schemaOwnership) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.refreshTransientGapsFromStorageEverySeconds = requireNonNull(refreshTransientGapsFromStorageInterval, "No refreshTransientGapsFromStorageInterval provided").toSeconds();
        this.resolveTransientGapsToIncludeInQueryStrategy = requireNonNull(resolveTransientGapsToIncludeInQueryStrategy, "No resolveTransientGapsToIncludeInQuery provided");
        this.resolveTransientGapsToPermanentGapsPromotionStrategy = requireNonNull(resolveTransientGapsToPermanentGapsPromotionStrategy, "No resolveTransientGapsToPermanentGapsPromotionStrategy provided");
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            var jdbi = unitOfWork.handle().getJdbi();
            jdbi.registerArgument(new AggregateTypeArgumentFactory());
            jdbi.registerColumnMapper(new AggregateTypeColumnMapper());
            jdbi.registerArgument(new SubscriberIdArgumentFactory());
            jdbi.registerColumnMapper(new SubscriberIdColumnMapper());
        });
        if (requireNonNull(schemaOwnership, "No schemaOwnership provided") == SchemaOwnership.COMPONENT) {
            PostgresqlCreateSchemaApplier.applyOwnSchema(unitOfWorkFactory, this);
            log.info("Ensured the gap tables '{}' and '{}' exist", TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME, PERMANENT_GAPS_TABLE_NAME);
        }
    }

    @Override
    public String moduleId() {
        return "postgresql-event-store-gaps";
    }

    @Override
    public int order() {
        return SchemaOrder.ORDER_EVENT_STORE;
    }

    /**
     * The transient-gaps table and its index, and the permanent-gaps table.
     */
    @Override
    public List<SchemaChange> contribute(SchemaContext context) {
        return List.of(SchemaChange.repeatable("transient-gaps-table",
                                               TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME,
                                               "CREATE TABLE IF NOT EXISTS " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " (\n" +
                                                       "   subscriber_id text NOT NULL,\n" +
                                                       "   aggregate_type text NOT NULL,\n" +
                                                       "   gap_global_event_order bigint NOT NULL\n," +
                                                       "   first_discovered TIMESTAMP WITH TIME ZONE NOT NULL\n," +
                                                       "   PRIMARY KEY (subscriber_id, aggregate_type, gap_global_event_order)\n" +
                                                       ")"),
                       SchemaChange.repeatable("transient-gaps-index",
                                               TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME,
                                               "CREATE INDEX IF NOT EXISTS " + TRANSIENT_SUBSCRIBER_GAPS_INDEX_NAME + " ON \n" +
                                                       TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + "(subscriber_id, aggregate_type)"),
                       SchemaChange.repeatable("permanent-gaps-table",
                                               PERMANENT_GAPS_TABLE_NAME,
                                               "CREATE TABLE IF NOT EXISTS " + PERMANENT_GAPS_TABLE_NAME + " (\n" +
                                                       "   aggregate_type text NOT NULL,\n" +
                                                       "   gap_global_event_order bigint NOT NULL\n," +
                                                       "   added_timestamp TIMESTAMP WITH TIME ZONE NOT NULL," +
                                                       "   PRIMARY KEY (aggregate_type, gap_global_event_order)\n" +
                                                       ")"));
    }

    @Override
    public SubscriptionGapHandler gapHandlerFor(SubscriberId subscriberId) {
        return new PostgresqlSubscriptionGapHandler(subscriberId);
    }

    @Override
    public List<GlobalEventOrder> resetPermanentGapsFor(AggregateType aggregateType) {
        var globalEventOrdersRemoved = unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                                                                                unitOfWork.handle().createQuery("DELETE FROM " + PERMANENT_GAPS_TABLE_NAME + " WHERE aggregate_type = :aggregate_type RETURNING gap_global_event_order")
                                                                                          .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                                                                          .mapTo(GlobalEventOrder.class)
                                                                                          .list());
        log.info("[{}] Removed {} Permanent Gap(s) with GlobalEventOrder: {}",
                 aggregateType,
                 globalEventOrdersRemoved.size(),
                 globalEventOrdersRemoved);
        return globalEventOrdersRemoved;
    }


    @Override
    public List<GlobalEventOrder> resetPermanentGapsFor(AggregateType aggregateType, LongRange resetForThisSpecificGlobalEventOrdersRange) {
        requireNonNull(resetForThisSpecificGlobalEventOrdersRange, "No resetForThisSpecificGlobalEventOrdersRange provided");
        List<GlobalEventOrder> globalEventOrdersRemoved = unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                                                                                           {
                                                                                               var sql = "DELETE FROM " + PERMANENT_GAPS_TABLE_NAME + " WHERE aggregate_type = :aggregate_type \n";
                                                                                               if (resetForThisSpecificGlobalEventOrdersRange.isOpenRange()) {
                                                                                                   sql += " AND gap_global_event_order >= :gap_global_event_order_from_and_including";
                                                                                               } else {
                                                                                                   sql += " AND gap_global_event_order >= :gap_global_event_order_from_and_including AND gap_global_event_order <= :gap_global_event_order_to_and_including";
                                                                                               }
                                                                                               sql += "  RETURNING gap_global_event_order";
                                                                                               var update = unitOfWork.handle().createQuery(sql)
                                                                                                                      .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"));
                                                                                               if (resetForThisSpecificGlobalEventOrdersRange.isOpenRange()) {
                                                                                                   update.bind("gap_global_event_order_from_and_including", resetForThisSpecificGlobalEventOrdersRange.fromInclusive);
                                                                                               } else {
                                                                                                   update.bind("gap_global_event_order_from_and_including", resetForThisSpecificGlobalEventOrdersRange.fromInclusive);
                                                                                                   update.bind("gap_global_event_order_to_and_including", resetForThisSpecificGlobalEventOrdersRange.toInclusive);
                                                                                               }
                                                                                               return update
                                                                                                       .mapTo(GlobalEventOrder.class)
                                                                                                       .list();
                                                                                           });
        log.info("[{}] Removed {} Permanent Gap(s), according to reset range {}, with GlobalEventOrder: {}",
                 aggregateType,
                 globalEventOrdersRemoved.size(),
                 resetForThisSpecificGlobalEventOrdersRange,
                 globalEventOrdersRemoved);

        return globalEventOrdersRemoved;
    }

    @Override
    public List<GlobalEventOrder> resetPermanentGapsFor(AggregateType aggregateType, List<GlobalEventOrder> resetForTheseSpecificGlobalEventOrders) {
        requireNonNull(resetForTheseSpecificGlobalEventOrders, "No resetForTheseSpecificGlobalEventOrders provided");
        if (resetForTheseSpecificGlobalEventOrders.isEmpty()) {
            return resetForTheseSpecificGlobalEventOrders;
        }

        var globalEventOrdersRemoved = unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                                                                        {
                                                                            var sql = "DELETE FROM " + PERMANENT_GAPS_TABLE_NAME + " WHERE aggregate_type = :aggregate_type \n" +
                                                                                    " AND gap_global_event_order IN (<globalEventOrders>)\n" +
                                                                                    "  RETURNING gap_global_event_order";
                                                                            return unitOfWork.handle().createQuery(sql)
                                                                                             .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                                                                             .bindList("globalEventOrders", resetForTheseSpecificGlobalEventOrders)
                                                                                             .mapTo(GlobalEventOrder.class)
                                                                                             .list();
                                                                        });
        log.info("[{}] Removed {} Permanent Gap(s), according to reset list {}, with GlobalEventOrder: {}",
                 aggregateType,
                 globalEventOrdersRemoved.size(),
                 resetForTheseSpecificGlobalEventOrders,
                 globalEventOrdersRemoved);

        return globalEventOrdersRemoved;
    }

    @Override
    public Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType) {
        return getPermanentGapsAsLongFor(aggregateType)
                .map(GlobalEventOrder::of);
    }

    @Override
    public void registerPermanentGaps(AggregateType aggregateType, List<GlobalEventOrder> gaps, String reason) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(gaps, "No gaps provided");
        if (gaps.isEmpty()) return;

        unitOfWorkFactory.usingUnitOfWork(uow -> {
            var now = now();
            var batch = uow.handle().prepareBatch("""
                                                      INSERT INTO permanent_gaps(aggregate_type, gap_global_event_order, added_timestamp)
                                                      VALUES (:aggregate_type, :gap_global_event_order, :added_timestamp)
                                                      ON CONFLICT DO NOTHING
                                                  """);

            for (var g : gaps) {
                batch.bind("aggregate_type", aggregateType)
                     .bind("gap_global_event_order", g)
                     .bind("added_timestamp", now)
                     .add();
            }
            batch.execute();
        });

        log.warn("[{}] Registered {} permanent gap(s) due to poison/skip. reason='{}' gaps={}",
                 aggregateType, gaps.size(), reason, gaps);
    }

    private Stream<Long> getPermanentGapsAsLongFor(AggregateType aggregateType) {
        return unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                                                        unitOfWork.handle().createQuery("SELECT gap_global_event_order FROM " + PERMANENT_GAPS_TABLE_NAME + " WHERE aggregate_type = :aggregate_type")
                                                                  .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                                                  .mapTo(Long.class)
                                                                  .stream());
    }

    private class PostgresqlSubscriptionGapHandler implements SubscriptionGapHandler {
        private final SubscriberId                                                               subscriberId;
        private       OffsetDateTime                                                             transientGapsLastRefreshedFromStorage;
        private       ConcurrentMap<AggregateType, List<Pair<GlobalEventOrder, OffsetDateTime>>> allTransientGaps = new ConcurrentHashMap<>();
        /**
         * This subscription's rotations, for every default selection its include strategy calls - see {@link #SUBSCRIPTION_ROTATIONS}
         */
        private final ConcurrentMap<RotationKey, TransientGapsQuerySelection>                    transientGapsQuerySelections = new ConcurrentHashMap<>();

        public PostgresqlSubscriptionGapHandler(SubscriberId subscriberId) {
            this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        }

        @Override
        public SubscriberId subscriberId() {
            return subscriberId;
        }

        @Override
        public Optional<Duration> transientGapGiveUpThreshold() {
            return resolveTransientGapsToPermanentGapsPromotionStrategy.permanentGapThreshold();
        }

        @Override
        public List<GlobalEventOrder> findTransientGapsToIncludeInQuery(AggregateType aggregateType, LongRange globalOrderQueryRange) {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(globalOrderQueryRange, "No globalOrderQueryRange provided");

            // Ensure all transient gaps for this aggregate type is loaded
            getTransientGapsFor(aggregateType);
            var transientGapsFor = allTransientGaps.get(aggregateType);
            if (transientGapsFor.isEmpty()) {
                return NO_GAPS;
            }
            // A default selection rotates through the gaps, and the rotation belongs to this subscription - however the
            // strategy reaches it
            var selected = withRotationsOf(transientGapsQuerySelections,
                                           () -> resolveTransientGapsToIncludeInQueryStrategy.resolveTransientGaps(aggregateType,
                                                                                                                   globalOrderQueryRange,
                                                                                                                   Collections.unmodifiableList(transientGapsFor)));
            return withGapsAboutToBePromoted(aggregateType, transientGapsFor, selected);
        }

        /**
         * A gap is only ever promoted to permanent when this subscription's query asked for it and its event was not there
         * (see {@link #reconcileGapsAndReport}) - the one proof that does not need to know where the events are stored -
         * or when a subscription that tracks gaps itself gave up waiting for it ({@link #giveUpTransientGaps}).
         * Whatever the include strategy returned (the default one asks for a bounded selection, a custom one for anything),
         * the gaps the promotion strategy would promote now are therefore added, lowest first and at most
         * {@link TransientGapsQuerySelection#MAX_GAPS_PER_QUERY} of them: with a burst of expired gaps they are
         * promoted that many per poll. Nothing is added, and nothing costs more than evaluating the promotion strategy
         * in memory, while no gap is old enough.
         */
        private List<GlobalEventOrder> withGapsAboutToBePromoted(AggregateType aggregateType,
                                                                 List<Pair<GlobalEventOrder, OffsetDateTime>> transientGapsFor,
                                                                 List<GlobalEventOrder> selected) {
            var promotable = resolveTransientGapsToPermanentGapsPromotionStrategy.resolveTransientGapsReadyToBePromotedToPermanentGaps(aggregateType,
                                                                                                                                       Collections.unmodifiableList(transientGapsFor));
            if (promotable == null || promotable.isEmpty()) {
                return selected;
            }
            var union = new TreeSet<Long>();
            promotable.stream().map(GlobalEventOrder::longValue).sorted().limit(TransientGapsQuerySelection.MAX_GAPS_PER_QUERY).forEach(union::add);
            if (selected != null) {
                selected.forEach(gap -> union.add(gap.longValue()));
            }
            return union.stream().map(GlobalEventOrder::of).collect(Collectors.toList());
        }

        @Override
        public void reconcileGaps(AggregateType aggregateType, LongRange globalOrderQueryRange, List<PersistedEvent> persistedEvents, List<GlobalEventOrder> transientGapsIncludedInQuery) {
            reconcileGapsAndReport(aggregateType, globalOrderQueryRange, persistedEvents, transientGapsIncludedInQuery);
        }

        /**
         * Resolves the transient gaps whose events the query returned, records the new gaps it reveals, and promotes the
         * transient gaps it asked for, did not get and the promotion strategy considers ready.
         * <p>
         * A new gap is a hole in the global order from where {@code globalOrderQueryRange} starts up to the highest of
         * {@code persistedEvents}. It is recorded as transient gaps only up to {@link #MAX_AWAITED_ORDERS_PER_GAP_END}
         * orders deep from each end ({@link GapEnds}); the middle of a wider hole is recorded nowhere - no transient gap,
         * so never a permanent gap either. The subscription awaits it in memory only: the polling event store re-queries
         * it by range on every poll until the gap handler's give-up threshold has passed, and the CDC event store's
         * delivery tracker is handed it with every commit - also after a re-subscribe on the same instance. A restart or
         * crash, a {@code resetFrom}, an unsubscribe or a fenced-lock release inside that window loses it. Finding the
         * holes walks the events, never the orders of the range, so a reconciliation costs as much as the events and the
         * bounded ends, whatever the width of a hole: a {@code setval} that moved the sequence a million forward under a
         * running polling subscription used to write a million transient-gap rows, which every later poll loaded, sorted
         * and filtered, and which were then promoted to a million permanent-gap rows.
         */
        @Override
        public GapReconciliation reconcileGapsAndReport(AggregateType aggregateType, LongRange globalOrderQueryRange, List<PersistedEvent> persistedEvents, List<GlobalEventOrder> transientGapsIncludedInQuery) {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(globalOrderQueryRange, "No globalOrderQueryRange provided");
            requireNonNull(persistedEvents, "No persistedEvents provided");
            requireNonNull(transientGapsIncludedInQuery, "No transientGaps provided");
            return inUnitOfWorkOfThisGapHandler(() -> doReconcileGapsAndReport(aggregateType, globalOrderQueryRange, persistedEvents, transientGapsIncludedInQuery));
        }

        private GapReconciliation doReconcileGapsAndReport(AggregateType aggregateType, LongRange globalOrderQueryRange, List<PersistedEvent> persistedEvents, List<GlobalEventOrder> transientGapsIncludedInQuery) {

            log.debug("[{}] Reconciling '{}' Gaps for query with globalOrderQueryRange: {},  persistedEvents size: {} and transientGaps size: {}",
                      subscriberId,
                      aggregateType,
                      globalOrderQueryRange,
                      persistedEvents.size(),
                      transientGapsIncludedInQuery.size());

            // Resolve existing Transient Gaps
            var resolvedTransientGaps = persistedEvents.stream().map(PersistedEvent::globalEventOrder)
                                                       .filter(transientGapsIncludedInQuery::contains)
                                                       .collect(Collectors.toList());
            var findTransientGapsThatWereResolved = !transientGapsIncludedInQuery.isEmpty() && !persistedEvents.isEmpty();
            // Counted from rows actually changed, not from the lists: under a non-exclusive subscription another
            // instance may reconcile the same gap concurrently, and only one of them resolved it.
            var resolvedCount = 0;
            if (findTransientGapsThatWereResolved) {
                resolvedCount = deleteTransientGaps(aggregateType,
                                                    resolvedTransientGaps);
            }
            var newCount = 0;

            // New Transient Gaps - walked hole by hole between the events, never order by order across the range: a hole
            // can be a million orders wide, and only the orders at its two ends are recorded (see GapEnds)
            if (!persistedEvents.isEmpty()) {
                var newGaps = GapEnds.below(globalOrderQueryRange.fromInclusive,
                                            persistedEvents.stream().mapToLong(persistedEvent -> persistedEvent.globalEventOrder().longValue()));
                if (!newGaps.awaitedInMemoryOnly().isEmpty()) {
                    log.debug("[{}] Not recording the middle of {} '{}' gap(s) wider than {} orders as transient gaps: {} - only the {} orders at each end. " +
                                      "The subscription awaits the middle in memory only",
                              subscriberId,
                              newGaps.awaitedInMemoryOnly().size(),
                              aggregateType,
                              2L * MAX_AWAITED_ORDERS_PER_GAP_END,
                              newGaps.awaitedInMemoryOnly(),
                              MAX_AWAITED_ORDERS_PER_GAP_END);
                }
                if (!newGaps.recorded().isEmpty()) {
                    // Verify if the transient gap is already marked permanent by another subscriber (permanent gaps are defined across subscribers per aggregate type).
                    // Only the permanent gaps within the awaited ranges are read - all of the aggregate type's were read before
                    var permanentGapsAmongTheNewTransientGaps = permanentGapsWithin(aggregateType, newGaps.recorded());
                    if (!permanentGapsAmongTheNewTransientGaps.isEmpty()) {
                        log.debug("[{}] Removed {} permanent gaps among the newly discovered transient gaps for {}: {}",
                                  subscriberId,
                                  permanentGapsAmongTheNewTransientGaps.size(),
                                  aggregateType,
                                  permanentGapsAmongTheNewTransientGaps);
                    }
                    var newTransientGapsToAdd = newGaps.recorded()
                                                       .stream()
                                                       .flatMapToLong(LongRange::stream)
                                                       .filter(globalEventOrder -> !permanentGapsAmongTheNewTransientGaps.contains(globalEventOrder))
                                                       .mapToObj(GlobalEventOrder::of)
                                                       .collect(Collectors.toList());
                    log.debug("[{}] Detected {} New Transient '{}' gaps within {} based on {} persisted events",
                              subscriberId,
                              newTransientGapsToAdd.size(),
                              aggregateType,
                              newGaps.recorded(),
                              persistedEvents.size());
                    newCount = addNewTransientGaps(aggregateType, newTransientGapsToAdd);
                }
            }

            // Promote Transient Gaps to Permanent Gaps
            log.trace("[{}] Looking for Transient '{}' gaps that can be promoted to Permanent Gaps. All Transient Gaps: {}",
                      subscriberId,
                      aggregateType,
                      allTransientGaps);
            var promotableTransientGaps = new ArrayList<>(resolveTransientGapsToPermanentGapsPromotionStrategy.resolveTransientGapsReadyToBePromotedToPermanentGaps(aggregateType,
                                                                                                                                                                     allTransientGaps.getOrDefault(aggregateType, List.of())));
            // Never a gap whose event is right here: the event store leaves the gap a gap fill fills open until the
            // subscriber is done with the event (it passes it here without the gap - and keeps passing the fills awaiting
            // acknowledgement), and resolves it then - promoted now, the gap would be gone before the event was handled,
            // and recorded as permanent although its event exists
            if (!promotableTransientGaps.isEmpty() && !persistedEvents.isEmpty()) {
                var reconciledGlobalOrders = persistedEvents.stream().map(PersistedEvent::globalEventOrder).collect(Collectors.toSet());
                promotableTransientGaps.removeIf(reconciledGlobalOrders::contains);
            }
            // Nor a gap this query did not ask for: its event may exist - committed after the subscriber that is to deliver it
            // looked, or delivered by another node or the CDC bus and awaiting acknowledgement there - and only a query that
            // asked for the gap and did not get it shows it is still missing. findTransientGapsToIncludeInQuery adds the gaps
            // about to be promoted to its query, so such a gap is promoted by the next poll's reconciliation instead. Callers
            // must therefore pass only the gaps their query really included: the CDC event store, recording the gap an event
            // from the bus opened, passes none - nothing queried them - and records the gaps its delivery tracker gave up on
            // through giveUpTransientGaps, its own proof that their events are missing
            if (!promotableTransientGaps.isEmpty()) {
                var askedFor = new HashSet<>(transientGapsIncludedInQuery);
                if (promotableTransientGaps.removeIf(gap -> !askedFor.contains(gap))) {
                    log.debug("[{}] Not promoting '{}' Transient Gaps this query did not ask for - the next poll asks for them first. Promoting: {}",
                              subscriberId,
                              aggregateType,
                              promotableTransientGaps);
                }
            }
            var promotedCount = promoteTransientGapsToPermanentGaps(aggregateType,
                                                                    promotableTransientGaps);
            return new GapReconciliation(newCount, resolvedCount, promotedCount);
        }

        /**
         * Only deletes the fills' transient gaps, in the current unit of work: no new gaps, no promotion. Promoting here
         * would run in the subscriber's unit of work when it acknowledges a fill, and could promote the gap of another
         * fill this subscriber was handed and has not acknowledged yet.
         */
        @Override
        public GapReconciliation resolveFilledGaps(AggregateType aggregateType, List<PersistedEvent> gapFills) {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(gapFills, "No gapFills provided");
            if (gapFills.isEmpty()) {
                return GapReconciliation.NONE;
            }
            return inUnitOfWorkOfThisGapHandler(() -> {
                var resolvedCount = deleteTransientGaps(aggregateType,
                                                        gapFills.stream().map(PersistedEvent::globalEventOrder).toList());
                return new GapReconciliation(0, resolvedCount, 0);
            });
        }

        /**
         * Promotes each of {@code transientGaps} that is still a transient gap of this subscriber to a permanent gap of the
         * aggregate type, which every subscriber of the aggregate type then skips (see {@link SubscriptionGapHandler#giveUpTransientGaps}).
         * When the promotion strategy states its threshold ({@link ResolveTransientGapsToPermanentGapsPromotionStrategy#permanentGapThreshold()})
         * all of them are promoted at once, without looking at their recorded age: the caller is obliged to have waited that
         * threshold for each of them, so the strategy's rule is met - give or take its measuring age in whole seconds from
         * when the gap was first recorded. A gap passed here that the caller did not wait for is promoted all the same.
         * With a strategy whose rule is not a plain age, the caller waited a default the strategy knows nothing about, so
         * only those the strategy itself considers ready are promoted. A gap something else resolved or promoted meanwhile
         * is left alone: only the rows deleted here are recorded as permanent gaps.
         */
        @Override
        public GapReconciliation giveUpTransientGaps(AggregateType aggregateType, List<GlobalEventOrder> transientGaps) {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(transientGaps, "No transientGaps provided");
            if (transientGaps.isEmpty()) {
                return GapReconciliation.NONE;
            }
            return inUnitOfWorkOfThisGapHandler(() -> {
                var givenUp = new HashSet<>(transientGaps);
                List<GlobalEventOrder> toPromote;
                if (resolveTransientGapsToPermanentGapsPromotionStrategy.permanentGapThreshold().isPresent()) {
                    toPromote = List.copyOf(givenUp);
                } else {
                    var ready = resolveTransientGapsToPermanentGapsPromotionStrategy.resolveTransientGapsReadyToBePromotedToPermanentGaps(aggregateType,
                                                                                                                                         Collections.unmodifiableList(internalGetTransientGapsFor(aggregateType)));
                    toPromote = ready == null ? List.of() : ready.stream().filter(givenUp::contains).distinct().toList();
                }
                var promotedCount = promoteGivenUpTransientGaps(aggregateType, toPromote);
                return new GapReconciliation(0, 0, promotedCount);
            });
        }

        /**
         * {@link #giveUpTransientGaps(AggregateType, List)} for ranges: with a promotion strategy that states its threshold,
         * every transient gap of this subscriber within them is promoted in one statement, whatever their width - the
         * orders never leave the database, and two array parameters stand for every range, so PostgreSQL's 65 535
         * bind-parameter cap is never in reach. With a strategy that states none, only those of the subscriber's transient
         * gaps the strategy considers ready, as for orders.
         */
        @Override
        public GapReconciliation giveUpTransientGapRanges(AggregateType aggregateType, List<LongRange> transientGapRanges) {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(transientGapRanges, "No transientGapRanges provided");
            if (transientGapRanges.isEmpty()) {
                return GapReconciliation.NONE;
            }
            transientGapRanges.forEach(range -> requireTrue(range.isClosedRange(), msg("Range {} is not closed", range)));
            return inUnitOfWorkOfThisGapHandler(() -> {
                var givenUp = rangesByFromInclusive(transientGapRanges);
                if (resolveTransientGapsToPermanentGapsPromotionStrategy.permanentGapThreshold().isPresent()) {
                    return new GapReconciliation(0, 0, promoteGivenUpTransientGapRanges(aggregateType, transientGapRanges, givenUp));
                }
                var ready = resolveTransientGapsToPermanentGapsPromotionStrategy.resolveTransientGapsReadyToBePromotedToPermanentGaps(aggregateType,
                                                                                                                                     Collections.unmodifiableList(internalGetTransientGapsFor(aggregateType)));
                var toPromote = ready == null ? List.<GlobalEventOrder>of() : ready.stream().filter(gap -> covers(givenUp, gap.longValue())).distinct().toList();
                return new GapReconciliation(0, 0, promoteGivenUpTransientGaps(aggregateType, toPromote));
            });
        }

        /**
         * Deletes this subscriber's transient gaps within {@code ranges} and records exactly those as permanent gaps, in
         * one statement in the current unit of work
         *
         * @return how many were promoted
         */
        private int promoteGivenUpTransientGapRanges(AggregateType aggregateType, List<LongRange> ranges, NavigableMap<Long, Long> rangesByFromInclusive) {
            var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();
            var gaps       = internalGetTransientGapsFor(aggregateType);
            allTransientGaps.put(aggregateType,
                                 gaps.stream()
                                     .filter(gap -> !covers(rangesByFromInclusive, gap._1.longValue()))
                                     .collect(Collectors.toList()));
            var promoted = unitOfWork.handle().createQuery("WITH given_up AS (\n" +
                                                                   "    DELETE FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + " gaps\n" +
                                                                   "    USING unnest(CAST(:from_inclusive AS bigint[]), CAST(:to_inclusive AS bigint[])) AS given_up_range(from_inclusive, to_inclusive)\n" +
                                                                   "    WHERE gaps.aggregate_type = :aggregate_type AND gaps.subscriber_id = :subscriber_id\n" +
                                                                   "      AND gaps.gap_global_event_order BETWEEN given_up_range.from_inclusive AND given_up_range.to_inclusive\n" +
                                                                   "    RETURNING gaps.gap_global_event_order\n" +
                                                                   "), recorded AS (\n" +
                                                                   "    INSERT INTO " + PERMANENT_GAPS_TABLE_NAME + " (aggregate_type, gap_global_event_order, added_timestamp)\n" +
                                                                   "    SELECT :aggregate_type, gap_global_event_order, :added_timestamp FROM given_up\n" +
                                                                   "    ON CONFLICT DO NOTHING\n" +
                                                                   ")\n" +
                                                                   "SELECT count(*) FROM given_up")
                                     .bind("aggregate_type", aggregateType)
                                     .bind("subscriber_id", subscriberId)
                                     .bind("added_timestamp", now())
                                     .bindArray("from_inclusive", Long.class, ranges.stream().map(range -> range.fromInclusive).toList())
                                     .bindArray("to_inclusive", Long.class, ranges.stream().map(LongRange::getToInclusive).toList())
                                     .mapTo(Long.class)
                                     .one();
            log.debug("[{}] Promoted {} given up Transient '{}' Gaps within {} to be Permanent Gaps",
                      subscriberId,
                      promoted,
                      aggregateType,
                      ranges);
            return Math.toIntExact(promoted);
        }

        /**
         * Only adds transient gaps - for the orders of {@code gaps} that are not permanent gaps of the aggregate type
         */
        @Override
        public GapReconciliation addTransientGaps(AggregateType aggregateType, LongRange gaps) {
            requireNonNull(aggregateType, "No aggregateType provided");
            requireNonNull(gaps, "No gaps provided");
            requireTrue(gaps.isClosedRange(), msg("Range {} is not closed", gaps));
            return inUnitOfWorkOfThisGapHandler(() -> {
                var permanentGaps = permanentGapsWithin(aggregateType, List.of(gaps));
                var newTransientGaps = gaps.stream()
                                           .filter(order -> !permanentGaps.contains(order))
                                           .mapToObj(GlobalEventOrder::of)
                                           .toList();
                return new GapReconciliation(addNewTransientGaps(aggregateType, newTransientGaps), 0, 0);
            });
        }

        /**
         * The permanent gaps of the aggregate type within {@code ranges} - closed - read in the current unit of work, in
         * one statement with two array parameters however many ranges there are
         */
        private Set<Long> permanentGapsWithin(AggregateType aggregateType, List<LongRange> ranges) {
            return unitOfWorkFactory.getRequiredUnitOfWork()
                                    .handle()
                                    .createQuery("SELECT permanent.gap_global_event_order FROM " + PERMANENT_GAPS_TABLE_NAME + " permanent\n" +
                                                         "    JOIN unnest(CAST(:from_inclusive AS bigint[]), CAST(:to_inclusive AS bigint[])) AS within_range(from_inclusive, to_inclusive)\n" +
                                                         "      ON permanent.gap_global_event_order BETWEEN within_range.from_inclusive AND within_range.to_inclusive\n" +
                                                         "    WHERE permanent.aggregate_type = :aggregate_type")
                                    .bind("aggregate_type", aggregateType)
                                    .bindArray("from_inclusive", Long.class, ranges.stream().map(range -> range.fromInclusive).toList())
                                    .bindArray("to_inclusive", Long.class, ranges.stream().map(LongRange::getToInclusive).toList())
                                    .mapTo(Long.class)
                                    .collect(Collectors.toSet());
        }

        /**
         * Deletes those of {@code givenUp} that are transient gaps of this subscriber and records exactly those as
         * permanent gaps - deleting at most {@link #MAX_GLOBAL_ORDERS_PER_STATEMENT} per statement, all in the current
         * unit of work
         *
         * @return how many were promoted
         */
        private int promoteGivenUpTransientGaps(AggregateType aggregateType, List<GlobalEventOrder> givenUp) {
            if (givenUp.isEmpty()) return 0;
            var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();
            var gaps       = internalGetTransientGapsFor(aggregateType);
            var givenUpSet = new HashSet<>(givenUp);
            allTransientGaps.put(aggregateType,
                                 gaps.stream()
                                     .filter(gap -> !givenUpSet.contains(gap._1))
                                     .collect(Collectors.toList()));
            var deleted = new ArrayList<GlobalEventOrder>();
            for (var chunk : Lists.partition(List.copyOf(givenUpSet), MAX_GLOBAL_ORDERS_PER_STATEMENT)) {
                deleted.addAll(unitOfWork.handle().createQuery("DELETE FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + "\n" +
                                                                       "    WHERE aggregate_type = :aggregate_type and subscriber_id = :subscriber_id and gap_global_event_order IN (<givenUpTransientGaps>)\n" +
                                                                       "    RETURNING gap_global_event_order")
                                         .bind("aggregate_type", aggregateType)
                                         .bind("subscriber_id", subscriberId)
                                         .bindList("givenUpTransientGaps", chunk)
                                         .mapTo(GlobalEventOrder.class)
                                         .list());
            }
            if (deleted.isEmpty()) {
                log.debug("[{}] None of the given up '{}' Transient Gaps {} is a transient gap any more - nothing to promote",
                          subscriberId,
                          aggregateType,
                          givenUp);
                return 0;
            }
            var now = now();
            var preparedBatch = unitOfWork.handle().prepareBatch("INSERT INTO " + PERMANENT_GAPS_TABLE_NAME + "\n" +
                                                                         "(aggregate_type, gap_global_event_order, added_timestamp) " +
                                                                         "VALUES (:aggregate_type, :gap_global_event_order, :added_timestamp) " +
                                                                         "ON CONFLICT DO NOTHING");
            for (var permanentGap : deleted) {
                preparedBatch
                        .bind("aggregate_type", aggregateType)
                        .bind("gap_global_event_order", permanentGap)
                        .bind("added_timestamp", now)
                        .add();
            }
            preparedBatch.execute();
            log.debug("[{}] Promoted {} given up Transient '{}' Gaps to be Permanent Gaps: {}",
                      subscriberId,
                      deleted.size(),
                      aggregateType,
                      deleted);
            return deleted.size();
        }

        /**
         * Runs in the current unit of work of this gap handler's {@link UnitOfWorkFactory}. Without one the factory is not
         * the one the caller (the event store, or a subscriber acknowledging a gap fill) is in, so the work gets a unit of
         * work, and with it a transaction, of its own - that commits independently of the caller's, which is the crash
         * window the class documentation describes. Said once.
         */
        private GapReconciliation inUnitOfWorkOfThisGapHandler(Supplier<GapReconciliation> action) {
            if (unitOfWorkFactory.getCurrentUnitOfWork().isPresent()) {
                return action.get();
            }
            if (warnedAboutUnitOfWorkOutsideCurrent.compareAndSet(false, true)) {
                log.warn("[{}] The gap handler was called without a current unit of work of its own UnitOfWorkFactory, so its gap changes run in a separate transaction that commits independently of the caller's. " +
                                 "This happens when the PostgresqlEventStreamGapHandler was given another UnitOfWorkFactory than the PostgresqlEventStore uses. " +
                                 "A crash between the two commits can then lose a gap fill that a subscriber acknowledged. Give the gap handler the event store's UnitOfWorkFactory",
                         subscriberId);
            }
            return unitOfWorkFactory.withUnitOfWork(unitOfWork -> action.get());
        }

        /**
         * @return how many transient gaps this subscriber stopped waiting for - whether or not another subscriber had
         * already recorded them as permanent, which only decides whether a permanent-gap row is inserted
         */
        private int promoteTransientGapsToPermanentGaps(AggregateType aggregateType, List<GlobalEventOrder> promotableTransientGaps) {
            if (promotableTransientGaps.isEmpty()) return 0;

            var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();
            deleteTransientGaps(aggregateType, promotableTransientGaps);

            var now = now();
            var preparedBatch = unitOfWork.handle().prepareBatch("INSERT INTO " + PERMANENT_GAPS_TABLE_NAME + "\n" +
                                                                         "(aggregate_type, gap_global_event_order, added_timestamp) " +
                                                                         "VALUES (:aggregate_type, :gap_global_event_order, :added_timestamp) " +
                                                                         "ON CONFLICT DO NOTHING");

            for (var permanentGap : promotableTransientGaps) {
                preparedBatch
                        .bind("aggregate_type", aggregateType)
                        .bind("gap_global_event_order", permanentGap)
                        .bind("added_timestamp", now)
                        .add();
            }
            var rowsUpdated = Arrays.stream(preparedBatch.execute())
                                    .reduce(Integer::sum).orElse(0);
            if (rowsUpdated == promotableTransientGaps.size()) {
                log.debug("[{}] Promoted {} Transient '{}' Gaps to be Permanent Gaps: {}",
                          subscriberId,
                          promotableTransientGaps.size(),
                          aggregateType,
                          promotableTransientGaps);
            } else {
                log.debug("[{}] Promoted {} out of {} Transient '{}' Gaps to be Permanent Gaps: {}",
                          subscriberId,
                          rowsUpdated,
                          promotableTransientGaps.size(),
                          aggregateType,
                          promotableTransientGaps);
            }
            return (int) promotableTransientGaps.stream().distinct().count();
        }

        /**
         * @return how many transient gaps were actually registered - fewer than asked when a concurrent reconciliation
         * registered some of them first
         */
        private int addNewTransientGaps(AggregateType aggregateType, List<GlobalEventOrder> newTransientGapsToAdd) {
            if (newTransientGapsToAdd.isEmpty()) return 0;
            var distinctTransientGapsToAdd = newTransientGapsToAdd.stream()
                                                                  .distinct()
                                                                  .collect(Collectors.toList());
            if (distinctTransientGapsToAdd.isEmpty()) return 0;

            var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();
            var now        = now();

            var gaps  = internalGetTransientGapsFor(aggregateType);
            var known = gaps.stream().map(gap -> gap._1).collect(Collectors.toSet());
            gaps.addAll(distinctTransientGapsToAdd.stream()
                                                  .filter(globalEventOrder -> !known.contains(globalEventOrder))
                                                  .map(globalEventOrder -> Pair.of(globalEventOrder,
                                                                                   now))
                                                  .collect(Collectors.toList()));

            // One statement and one array parameter however many gaps: a batch made a round trip per row
            var rowsUpdated = unitOfWork.handle().createUpdate("INSERT INTO " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + "\n" +
                                                                       "(subscriber_id, aggregate_type, gap_global_event_order, first_discovered) " +
                                                                       "SELECT :subscriber_id, :aggregate_type, gap_global_event_order, :first_discovered " +
                                                                       "FROM unnest(CAST(:gap_global_event_orders AS bigint[])) AS gap_global_event_order " +
                                                                       "ON CONFLICT DO NOTHING")
                                        .bind("subscriber_id", subscriberId)
                                        .bind("aggregate_type", aggregateType)
                                        .bind("first_discovered", now)
                                        .bindArray("gap_global_event_orders", Long.class, distinctTransientGapsToAdd.stream().map(GlobalEventOrder::longValue).toList())
                                        .execute();
            if (rowsUpdated == distinctTransientGapsToAdd.size()) {
                log.debug("[{}] Added {} New Transient '{}' Gaps {}\nAll Transient '{}' Gaps: {}",
                          subscriberId,
                          distinctTransientGapsToAdd.size(),
                          aggregateType,
                          distinctTransientGapsToAdd,
                          aggregateType,
                          allTransientGaps);
            } else if (rowsUpdated > distinctTransientGapsToAdd.size()) {
                log.warn("[{}] Added {} out of {} new Transient '{}' Gaps.\n" +
                                 "This indicates unexpected row-count behavior for subscriber '{}'.\n" +
                                 "New Transient Gaps to add: {}",
                         subscriberId,
                         rowsUpdated,
                         distinctTransientGapsToAdd.size(),
                         aggregateType,
                         subscriberId,
                         distinctTransientGapsToAdd);
            } else {
                log.debug("[{}] Added {} out of {} new Transient '{}' Gaps.\n" +
                                 "This can happen under non-exclusive subscriptions where transient gaps are reconciled concurrently.\n" +
                                 "New Transient Gaps to add: {}",
                         subscriberId,
                         rowsUpdated,
                         distinctTransientGapsToAdd.size(),
                         aggregateType,
                         distinctTransientGapsToAdd);
            }
            return Math.min(rowsUpdated, distinctTransientGapsToAdd.size());
        }

        /**
         * @return how many transient gaps were actually deleted - fewer than asked when a concurrent reconciliation
         * deleted some of them first
         */
        private int deleteTransientGaps(AggregateType aggregateType, List<GlobalEventOrder> resolvedTransientGaps) {
            if (resolvedTransientGaps.isEmpty()) return 0;
            var distinctResolvedTransientGaps = resolvedTransientGaps.stream()
                                                                     .distinct()
                                                                     .collect(Collectors.toList());
            if (distinctResolvedTransientGaps.isEmpty()) return 0;

            var unitOfWork = unitOfWorkFactory.getRequiredUnitOfWork();
            var gaps       = internalGetTransientGapsFor(aggregateType);
            var resolved   = new HashSet<>(distinctResolvedTransientGaps);
            allTransientGaps.put(aggregateType,
                                 gaps.stream()
                                     .filter(gap -> !resolved.contains(gap._1))
                                     .collect(Collectors.toList()));

            // One array parameter, not one per gap: never near PostgreSQL's bind-parameter cap
            var numOfRowsChanges = unitOfWork.handle().createUpdate("DELETE FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + "\n" +
                                                                            "    WHERE aggregate_type = :aggregate_type and subscriber_id = :subscriber_id and gap_global_event_order = ANY(CAST(:resolveTransientGaps AS bigint[]))")
                                             .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                             .bind("subscriber_id", subscriberId)
                                             .bindArray("resolveTransientGaps", Long.class, distinctResolvedTransientGaps.stream().map(GlobalEventOrder::longValue).toList())
                                             .execute();
            if (numOfRowsChanges > distinctResolvedTransientGaps.size()) {
                log.warn("[{}] Wanted to delete {} resolved Transient '{}' gaps, but was only able to delete {} transient gaps.\n" +
                                 "This indicates unexpected row-count behavior for subscriber '{}'.\n" +
                                 "Resolved Transient Gaps to delete: {}",
                         subscriberId,
                         distinctResolvedTransientGaps.size(),
                         aggregateType,
                         numOfRowsChanges,
                         subscriberId,
                         distinctResolvedTransientGaps);
            } else if (numOfRowsChanges < distinctResolvedTransientGaps.size()) {
                log.debug("[{}] Deleted {} out of {} resolved Transient '{}' gaps.\n" +
                                  "This can happen under non-exclusive subscriptions where transient gaps are reconciled concurrently.\n" +
                                  "Resolved Transient Gaps to delete: {}",
                          subscriberId,
                          numOfRowsChanges,
                          distinctResolvedTransientGaps.size(),
                          aggregateType,
                          distinctResolvedTransientGaps);
            } else {
                log.debug("[{}] Deleted {} resolved Transient '{}' gaps. " +
                                  "Resolved Transient Gaps deleted: {}\n" +
                                  "All Transient '{}' Gaps: {}",
                          subscriberId,
                          distinctResolvedTransientGaps.size(),
                          aggregateType,
                          distinctResolvedTransientGaps,
                          aggregateType,
                          allTransientGaps);
            }
            return Math.min(numOfRowsChanges, distinctResolvedTransientGaps.size());
        }

        private List<Pair<GlobalEventOrder, OffsetDateTime>> internalGetTransientGapsFor(AggregateType aggregateType) {
            requireNonNull(aggregateType, "No aggregateType provided");
            var gaps = allTransientGaps.get(aggregateType);
            if (gaps == null || gaps.isEmpty() || transientGapsLastRefreshedFromStorage == null || ChronoUnit.SECONDS.between(transientGapsLastRefreshedFromStorage, now()) >= refreshTransientGapsFromStorageEverySeconds) {
                transientGapsLastRefreshedFromStorage = now();
                allTransientGaps.put(aggregateType, unitOfWorkFactory.getRequiredUnitOfWork()
                                                                     .handle()
                                                                     .createQuery("SELECT gap_global_event_order, first_discovered FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + "\n" +
                                                                                          "    WHERE aggregate_type = :aggregate_type and subscriber_id = :subscriber_id\n" +
                                                                                          "    ORDER BY gap_global_event_order ASC")
                                                                     .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                                                     .bind("subscriber_id", subscriberId)
                                                                     .map((rs, ctx) -> Pair.of(GlobalEventOrder.of(rs.getLong("gap_global_event_order")),
                                                                                               rs.getObject("first_discovered", OffsetDateTime.class)))
                                                                     .list());
            }
            return allTransientGaps.computeIfAbsent(aggregateType, aggregateType_ -> new ArrayList<>());
        }

        @Override
        public List<GlobalEventOrder> resetTransientGapsFor(AggregateType aggregateType) {
            return unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                                                            unitOfWork.handle()
                                                                      .createQuery("DELETE FROM " + TRANSIENT_SUBSCRIBER_GAPS_TABLE_NAME + "\n" +
                                                                                           "    WHERE aggregate_type = :aggregate_type and subscriber_id = :subscriber_id\n" +
                                                                                           "    RETURNING gap_global_event_order")
                                                                      .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                                                      .bind("subscriber_id", subscriberId)
                                                                      .mapTo(GlobalEventOrder.class)
                                                                      .list()
                                                   );
        }

        @Override
        public List<GlobalEventOrder> getTransientGapsFor(AggregateType aggregateType) {
            return unitOfWorkFactory.withUnitOfWork(unitOfWork ->
                                                            internalGetTransientGapsFor(aggregateType)
                                                                    .stream()
                                                                    .map(Pair::_1)
                                                                    .collect(Collectors.toList()));
        }

        @Override
        public Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType) {
            return unitOfWorkFactory.getRequiredUnitOfWork()
                                    .handle()
                                    .createQuery("SELECT gap_global_event_order FROM " + PERMANENT_GAPS_TABLE_NAME + "\n" +
                                                         "    WHERE aggregate_type = :aggregate_type\n" +
                                                         "    ORDER BY gap_global_event_order ASC")
                                    .bind("aggregate_type", requireNonNull(aggregateType, "No aggregateType provided"))
                                    .mapTo(GlobalEventOrder.class)
                                    .stream();
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o.getClass().equals(PostgresqlSubscriptionGapHandler.class))) return false;
            PostgresqlSubscriptionGapHandler that = (PostgresqlSubscriptionGapHandler) o;
            return subscriberId.equals(that.subscriberId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(subscriberId);
        }

        @Override
        public String toString() {
            return "PostgresqlSubscriptionGapHandler{" +
                    "subscriberId=" + subscriberId +
                    ", transientGapsLastRefreshedFromStorage=" + transientGapsLastRefreshedFromStorage +
                    ", transientGaps(#" + allTransientGaps.size() + ")=" + allTransientGaps +
                    '}';
        }
    }

    /**
     * Strategy the allows user of the {@link PostgresqlEventStreamGapHandler} to determine which and how many transient gaps to include in
     * a given call to the {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)} during {@link EventStore#pollEvents(AggregateType, long, Optional, Optional, Optional, Optional, Optional)}
     */
    @FunctionalInterface
    public interface ResolveTransientGapsToIncludeInQueryStrategy {
        /**
         * Based on the <code>globalOrderQueryRange</code> resolve which transient gaps that should be included in the {@link EventStore#loadEventsByGlobalOrder(AggregateType, LongRange, List, Tenant)}
         *
         * @param forAggregateType      the aggregate type we want to resolve gaps for
         * @param globalOrderQueryRange the global order query range being used when querying for new events
         * @param allTransientGaps      all the currently known transient gaps ({@link Pair#_1} is the Gap {@link GlobalEventOrder}
         *                              and {@link Pair#_2} is the gaps firstDiscoveredTimestamp)
         * @return a list of {@link GlobalEventOrder} gaps (can be null or empty if no transient gaps exists for this subscriber)
         */
        List<GlobalEventOrder> resolveTransientGaps(AggregateType forAggregateType, LongRange globalOrderQueryRange, List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps);

        /**
         * The strategy the {@link PostgresqlEventStreamGapHandler} constructors that take no strategy use: every open transient gap
         * while there are at most 50, and beyond that the 20 highest (where a late commit lands), the 10 lowest (the next to be
         * promoted) and a window of 20 of the ones in between that rotates on every call - so every open gap is asked for again
         * well before it is promoted, however many are open. Use it to keep that selection when passing a
         * {@link ResolveTransientGapsToPermanentGapsPromotionStrategy} or other options to the longer constructors, or compose it
         * in your own strategy, e.g. to include extra gaps:
         * <pre>{@code
         * var defaultSelection = ResolveTransientGapsToIncludeInQueryStrategy.defaultSelection();
         * ResolveTransientGapsToIncludeInQueryStrategy mine = (type, range, gaps) -> {
         *     var selected = new ArrayList<>(defaultSelection.resolveTransientGaps(type, range, gaps));
         *     // ... add or remove gaps ...
         *     return selected;
         * };
         * }</pre>
         * Every call returns a new instance. Whenever the {@link PostgresqlEventStreamGapHandler} asks for gaps - passed it
         * as is, or called from your own strategy or a decorator around it - the rotation is the asking subscription's
         * own, per instance and aggregate type, so each subscription sees every gap in between at least once every
         * {@code ceil(gapsInBetween / 20)} polls. Two instances composed in one strategy - e.g. one per subset of the
         * gaps - keep a rotation each. An instance advances its rotation on every call, so call each instance once per
         * ask: the guarantee assumes one call per poll.
         * <p>
         * The per-subscription rotation applies only while the selection is called on the thread the gap handler asks on.
         * Called directly, outside the gap handler, or from another thread (an executor, a {@code CompletableFuture}), the
         * instance rotates with its own rotation per aggregate type - shared by every subscription that reaches it that
         * way, so each of them sees only part of the rotation.
         *
         * @return a new default selection strategy
         */
        static ResolveTransientGapsToIncludeInQueryStrategy defaultSelection() {
            return new DefaultResolveTransientGapsToIncludeInQueryStrategy();
        }
    }

    /**
     * Strategy the allows user of the {@link PostgresqlEventStreamGapHandler} to determine when
     * a transient gap is promoted to a permanent gap
     *
     * @see #thresholdBased(int)
     */
    @FunctionalInterface
    public interface ResolveTransientGapsToPermanentGapsPromotionStrategy {
        /**
         * Determine which transient gaps should be promoted to permanent gaps.<br>
         * The simplest solution is to use a time based approach where any transient gap that has existed for longer than the specified
         * Jdbi <code>handle.getConfig(SqlStatements.class).setQueryTimeout(seconds);</code> or Spring <code>PlatformTransactionManager#setDefaultTimeout(seconds)</code>
         * is promoted to permanent gaps - see {@link #thresholdBased(int)}
         *
         * @param forAggregateType the aggregate type we want to determine which transient gaps should be promoted to permanent gaps
         * @param allTransientGaps all the currently known transient gaps ({@link Pair#_1} is the Gap {@link GlobalEventOrder}
         *                         and {@link Pair#_2} is the gaps firstDiscoveredTimestamp)
         * @return a list of {@link GlobalEventOrder} transient gaps that will be promoted to permanent gaps
         */
        List<GlobalEventOrder> resolveTransientGapsReadyToBePromotedToPermanentGaps(AggregateType forAggregateType, List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps);

        /**
         * The fixed age after which this strategy promotes a transient gap, if it has one - what
         * {@link SubscriptionGapHandler#transientGapGiveUpThreshold()} reports, so a subscription that tracks gaps itself
         * (CDC) gives up on a gap when this handler would. Empty for a strategy whose rule is not a plain age, which is
         * what a lambda implementing this interface gets; {@link #thresholdBased(int)} returns it
         *
         * @return the age after which a transient gap is promoted, or empty if there is no such fixed age
         */
        default Optional<Duration> permanentGapThreshold() {
            return Optional.empty();
        }

        /**
         * Default strategy where the time between the transient gaps firstDiscoveredTimestamp ({@link Pair#_2}) and now is larger than
         * <code>permanentGapThresholdInSeconds</code> then the transient gap is promoted to a permanent gap
         *
         * @param permanentGapThresholdInSeconds if the time between the transient gaps firstDiscoveredTimestamp ({@link Pair#_2}) and <b>now</b> is larger than
         *                                       <code>permanentGapThresholdInSeconds</code> then a transient gap is promoted to a permanent gap
         * @return the default threshold based strategy
         */
        static ResolveTransientGapsToPermanentGapsPromotionStrategy thresholdBased(int permanentGapThresholdInSeconds) {
            requireTrue(permanentGapThresholdInSeconds > 0, "permanentGapThresholdInSeconds must be > 0");
            return new ResolveTransientGapsToPermanentGapsPromotionStrategy() {
                @Override
                public Optional<Duration> permanentGapThreshold() {
                    return Optional.of(Duration.ofSeconds(permanentGapThresholdInSeconds));
                }

                @Override
                public List<GlobalEventOrder> resolveTransientGapsReadyToBePromotedToPermanentGaps(AggregateType forAggregateType, List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps) {
                var now = now();
                return allTransientGaps.stream()
                                       .filter(globalEventOrderFirstDiscoveredTimestampPair -> {
                                           var secondsBetween = ChronoUnit.SECONDS.between(globalEventOrderFirstDiscoveredTimestampPair._2, now);
                                           if (log.isTraceEnabled()) {
                                               log.trace("{} seconds since '{}' Transient Gap with GlobalOrder {} was first discovered",
                                                         secondsBetween,
                                                         forAggregateType,
                                                         globalEventOrderFirstDiscoveredTimestampPair._1);
                                           }
                                           return secondsBetween >
                                                   permanentGapThresholdInSeconds;
                                       })
                                       .map(Pair::_1)
                                       .collect(Collectors.toList());
                }
            };
        }
    }

    /**
     * Identifies one rotation in a subscription's rotations ({@link #SUBSCRIPTION_ROTATIONS}): that of one default
     * selection instance for one aggregate type
     *
     * @param defaultSelection the default selection instance that rotates
     * @param aggregateType    the aggregate type whose gaps it rotates through
     */
    record RotationKey(ResolveTransientGapsToIncludeInQueryStrategy defaultSelection, AggregateType aggregateType) {
    }

    /**
     * Runs {@code action} - a call to an include strategy - with {@code rotations} as the rotations every default selection
     * it calls on this thread uses (see {@link #SUBSCRIPTION_ROTATIONS})
     */
    static <T> T withRotationsOf(ConcurrentMap<RotationKey, TransientGapsQuerySelection> rotations, Supplier<T> action) {
        return ScopedValue.where(SUBSCRIPTION_ROTATIONS, requireNonNull(rotations, "No rotations provided"))
                          .call(action::get);
    }

    /**
     * See {@link ResolveTransientGapsToIncludeInQueryStrategy#defaultSelection()}. Rotates with its rotation among those of
     * the subscription whose gap handler is asking ({@link #SUBSCRIPTION_ROTATIONS}); called directly or on another thread,
     * with its own, which are then shared by the callers of this instance.
     */
    private static final class DefaultResolveTransientGapsToIncludeInQueryStrategy implements ResolveTransientGapsToIncludeInQueryStrategy {
        private final ConcurrentMap<AggregateType, TransientGapsQuerySelection> selections = new ConcurrentHashMap<>();

        @Override
        public List<GlobalEventOrder> resolveTransientGaps(AggregateType forAggregateType, LongRange globalOrderQueryRange, List<Pair<GlobalEventOrder, OffsetDateTime>> allTransientGaps) {
            var rotation = SUBSCRIPTION_ROTATIONS.isBound()
                           ? SUBSCRIPTION_ROTATIONS.get().computeIfAbsent(new RotationKey(this, forAggregateType), key -> new TransientGapsQuerySelection())
                           : selections.computeIfAbsent(forAggregateType, type -> new TransientGapsQuerySelection());
            return rotation.select(allTransientGaps);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(Clock.systemUTC());
    }
}
