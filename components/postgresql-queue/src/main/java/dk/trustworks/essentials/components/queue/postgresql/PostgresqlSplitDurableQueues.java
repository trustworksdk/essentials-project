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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.reactive.*;
import org.slf4j.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.*;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.interceptor.DefaultInterceptorChain.sortInterceptorsByOrder;
import static dk.trustworks.essentials.shared.interceptor.InterceptorChain.newInterceptorChainForOperation;

/**
 * {@link DurableQueues} that stores ordered and unordered messages in <b>separate tables</b>, so each carries only
 * the indexes its own access patterns need.
 *
 * <h2>What this measures at, through this class</h2>
 * Unordered traffic: <b>1.07× total</b> at 40 000 messages — insert 1.34–1.60×, drain at parity, 8–9% fewer index
 * bytes (measurements §21–§23). Not the <b>1.38×/1.62×</b> quoted historically, which came from raw SQL against
 * prototype schemas and never described this implementation.
 * <p>
 * <b>Ordered traffic is unmeasured.</b> Repeat runs of an identical configuration differ by 4.75×, so no ratio is
 * quoted until there is a stable harness.
 * <p>
 * It reached parity only after measurement found two defects that reasoning had not. Both are worth knowing before
 * changing anything here, because both came from reusing v1's statements against a schema that deliberately is not
 * v1's: the composite asked each table for messages it cannot hold (fixed by {@code ClaimScope}), and the unordered
 * index omitted {@code key IS NULL} so every claim fell back to heap fetches (§23). Together they made the split
 * <b>5× slower</b> than the table it replaces.
 * <p>
 * It is a <b>composition, not a rewrite</b>. {@link DurableQueuesSql} generates its statements for whatever table
 * name it is constructed with, and both split tables keep the shared table's columns, so each is driven by
 * {@link PostgresqlDurableQueues}' existing, tested statements unchanged — see
 * {@code docs/durable-queues-implementation-plan.md} §7c. This class owns the schema of both tables and routes
 * operations between two storage delegates; it introduces no new SQL.
 *
 * <h2>Routing</h2>
 * <ul>
 *     <li><b>Writes</b> route on the message: an {@link OrderedMessage} goes to the ordered table, anything else
 *     to the unordered one. Nothing has to be declared, which is why there is no new consumer API — §7a.</li>
 *     <li><b>Reads and writes by {@link QueueEntryId}</b> try both tables, because the SPI addresses messages by
 *     id alone and an id carries no mode. Two statements, one transaction — and the transaction is what costs,
 *     not the statement (§7, §7b).</li>
 *     <li><b>Queries and counts</b> merge across both.</li>
 *     <li><b>Consumption</b> is served by <em>one</em> {@link CentralizedMessageFetcher} owned here, over this
 *     composite. That is what keeps a single {@code parallelConsumers} budget: registering a consumer per
 *     delegate would double in-flight work. The delegates are storage only and never run fetchers of their
 *     own.</li>
 * </ul>
 */
public final class PostgresqlSplitDurableQueues implements BatchMessageFetchingCapableDurableQueues {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlSplitDurableQueues.class);

    public static final String UNORDERED_TABLE_SUFFIX = "_unordered";
    public static final String ORDERED_TABLE_SUFFIX   = "_ordered";

    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork>           unitOfWorkFactory;
    private final PostgresqlSplitDurableQueuesSettings                                    settings;
    private final PostgresqlDurableQueues                                                  unorderedStore;
    private final PostgresqlDurableQueues                                                  orderedStore;
    private final String                                                                   unorderedTableName;
    private final String                                                                   orderedTableName;
    private final Optional<MultiTableChangeListener<TableChangeNotification>>               multiTableChangeListener;
    private final Function<QueueName, QueuePollingOptimizer>                                queuePollingOptimizerFactory;
    private final List<DurableQueuesInterceptor>                                           interceptors           = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<QueueName, CentralizedMessageFetcherDurableQueueConsumer> durableQueueConsumers = new ConcurrentHashMap<>();
    private final CentralizedMessageFetcher                                                centralizedMessageFetcher;
    private final DurableQueueMessageObserver                                              messageObserver;

    private volatile boolean started;

    public static PostgresqlSplitDurableQueuesBuilder builder() {
        return new PostgresqlSplitDurableQueuesBuilder();
    }

    /**
     * @param multiTableChangeListener          the LISTEN/NOTIFY bridge, or {@code null} for polling only. When
     *                                          present, both tables get a change-notification trigger and an
     *                                          enqueue on either of them wakes the queue's poll - see
     *                                          {@link #installNotificationTriggers} and
     *                                          {@link #subscribeToWakeUpNotifications}
     * @param centralizedQueuePollingOptimizerFactory per-queue backoff strategy, or {@code null} for the default
     *                                          (which is {@link QueuePollingOptimizer#None()} when no listener is
     *                                          configured, since without wake-ups a backed-off queue has nothing
     *                                          to un-back it off)
     */
    public PostgresqlSplitDurableQueues(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                        JSONSerializer jsonSerializer,
                                        MultiTableChangeListener<TableChangeNotification> multiTableChangeListener,
                                        Function<QueueName, QueuePollingOptimizer> centralizedQueuePollingOptimizerFactory,
                                        PostgresqlSplitDurableQueuesSettings settings) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.settings = requireNonNull(settings, "No settings provided");
        requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.multiTableChangeListener = Optional.ofNullable(multiTableChangeListener);
        this.messageObserver = DurableQueueMessageObserver.safe(requireNonNull(settings.messageObserver(), "No messageObserver provided"));
        this.queuePollingOptimizerFactory = centralizedQueuePollingOptimizerFactory != null
                                            ? centralizedQueuePollingOptimizerFactory
                                            : this::createDefaultQueuePollingOptimizerFor;
        this.unorderedTableName = settings.baseQueueTableName() + UNORDERED_TABLE_SUFFIX;
        this.orderedTableName = settings.baseQueueTableName() + ORDERED_TABLE_SUFFIX;

        // Both delegates are storage only: no centralized fetcher of their own, and this composite never
        // registers a consumer on them. SPLIT_DELEGATE because the per-mode index sets are this class's business
        // rather than the shared table's, and because a miss on a by-id operation is expected here.
        this.unorderedStore = storageDelegateFor(unorderedTableName, jsonSerializer);
        this.orderedStore = storageDelegateFor(orderedTableName, jsonSerializer);

        // One fetcher, over this composite rather than over either delegate: that is what keeps a single
        // parallelConsumers budget across both tables. Its acknowledgement buffer, when enabled, acknowledges
        // through the composite too, so an id is looked up in both tables.
        var acknowledgementSettings = settings.batchedAcknowledgementSettings();
        if (acknowledgementSettings.enabled()) {
            requireTrue(settings.transactionalMode() == TransactionalMode.SingleOperationTransaction,
                        "Batched acknowledgement requires TransactionalMode.SingleOperationTransaction - it relies on "
                                + "resetMessagesStuckBeingDelivered to recover acknowledgements lost before a flush, which "
                                + "FullyTransactional does not provide");
        }
        var acknowledgementBuffer = acknowledgementSettings.enabled()
                                    ? new BatchedAcknowledgementBuffer(this,
                                                                       acknowledgementSettings.maxBatchSize(),
                                                                       acknowledgementSettings.flushInterval(),
                                                                       settings.messageHandlingTimeout())
                                    : null;
        this.centralizedMessageFetcher = new CentralizedMessageFetcher(this,
                                                                       interceptors,
                                                                       new CentralizedMessageFetcherSettings(settings.pollingInterval().toMillis(),
                                                                                                             settings.useBatchedFetch(),
                                                                                                             settings.batchedFetchSwitchThreshold(),
                                                                                                             acknowledgementBuffer));
    }

    private PostgresqlDurableQueues storageDelegateFor(String tableName, JSONSerializer jsonSerializer) {
        return new PostgresqlDurableQueues(unitOfWorkFactory,
                                           jsonSerializer,
                                           tableName,
                                           null,
                                           null,
                                           settings.transactionalMode(),
                                           settings.messageHandlingTimeout(),
                                           false,
                                           settings.pollingInterval(),
                                           null,
                                           true,
                                           false,
                                           0,
                                           5000,
                                           BatchedAcknowledgementSettings.disabled(),
                                           settings.orderedMessageDuplicateStrategy(),
                                           PostgresqlDurableQueues.Role.SPLIT_DELEGATE,
                                           // none(): the composite owns the observer, because the composite is
                                           // what the fetcher holds. A delegate never runs a fetcher, so its own
                                           // observer would never be consulted - and if it were, every delivery
                                           // would be reported twice.
                                           DurableQueueMessageObserver.none(),
                                           // The cursor is not wired into the split yet: it replaces the ordered
                                           // claim, and the split's ordered delegate would need its own key-state
                                           // table. Measure it on the shared table first - the two are
                                           // independent opt-ins and combining them multiplies what a measurement
                                           // has to control for.
                                           false);
    }

    // ------------------------------------------------------------------------------------------------
    // Lifecycle and schema
    // ------------------------------------------------------------------------------------------------

    @Override
    public void start() {
        if (started) {
            return;
        }
        createSchema();
        unorderedStore.start();
        orderedStore.start();
        centralizedMessageFetcher.start();
        durableQueueConsumers.values().forEach(CentralizedMessageFetcherDurableQueueConsumer::start);
        subscribeToWakeUpNotifications();
        started = true;
        log.info("Started with unordered table '{}' and ordered table '{}'", unorderedTableName, orderedTableName);
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        durableQueueConsumers.values().forEach(consumer -> {
            try {
                consumer.stop();
            } catch (Exception e) {
                log.error("Error occurred while stopping consumer for '{}'", consumer.queueName(), e);
            }
        });
        try {
            centralizedMessageFetcher.stop();
        } catch (Exception e) {
            log.error("Error occurred while stopping CentralizedMessageFetcher", e);
        }
        multiTableChangeListener.ifPresent(listener -> bothTableNames().forEach(tableName -> {
            try {
                listener.unlistenToNotificationsFor(tableName);
            } catch (Exception e) {
                log.debug("Error occurred while performing unlistenToNotificationsFor '{}'", tableName, e);
            }
        }));
        orderedStore.stop();
        unorderedStore.stop();
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * Creates both tables with the same columns and <b>different index sets</b>, which is the entire point: the
     * unordered table gets one secondary index, the ordered table two.
     */
    private void createSchema() {
        var unorderedSql = new DurableQueuesSql(unorderedTableName);
        var orderedSql   = new DurableQueuesSql(orderedTableName);
        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            PostgresqlUtil.acquireBootstrapLock(unitOfWork.handle());

            unitOfWork.handle().execute(unorderedSql.getCreateSplitQueueTableSql());
            unitOfWork.handle().execute(unorderedSql.getCreateSplitUnorderedReadyIndexSql());

            unitOfWork.handle().execute(orderedSql.getCreateSplitQueueTableSql());
            unitOfWork.handle().execute(orderedSql.getCreateSplitOrderedHeadIndexSql());
            unitOfWork.handle().execute(orderedSql.getCreateSplitOrderedKeyIndexSql(
                    settings.orderedMessageDuplicateStrategy() == OrderedMessageDuplicateStrategy.REJECT));

            installNotificationTriggers(unitOfWork);
        });
    }

    /**
     * Installs the change-notification trigger on <b>both</b> tables, so an enqueue to either wakes the queue's
     * poll.
     * <p>
     * The delegates cannot do this: v1 installs it inside its schema initialization, which a
     * {@link PostgresqlDurableQueues.Role#SPLIT_DELEGATE} skips. Until this existed the split had no wake-up at
     * all and polled at its fixed interval - correct, but a silent latency regression for a deployment moving
     * onto the split with a listener configured.
     */
    private void installNotificationTriggers(HandleAwareUnitOfWork unitOfWork) {
        multiTableChangeListener.ifPresent(listener -> bothTableNames().forEach(tableName ->
                ListenNotify.addChangeNotificationTriggerToTable(unitOfWork.handle(),
                                                                 tableName,
                                                                 List.of(ListenNotify.SqlOperation.INSERT, ListenNotify.SqlOperation.UPDATE),
                                                                 "id", "queue_name", "added_ts", "next_delivery_ts", "delivery_ts",
                                                                 "is_dead_letter_message", "is_being_delivered")));
    }

    /**
     * Routes a notification from <em>either</em> table to the consumer for its queue, which resets that queue's
     * backoff so the next tick polls both tables.
     * <p>
     * Routing by <b>queue name</b> rather than by table is what makes this correct on a split, and it is not a
     * change from v1 - v1 already routes on {@code QueueTableNotification.queueName}, and both split tables carry
     * that column. A table-keyed wake-up would have let an ordered enqueue advance state the queue's single poll
     * decision never reads. See §7e of {@code docs/durable-queues-implementation-plan.md}.
     */
    private void subscribeToWakeUpNotifications() {
        multiTableChangeListener.ifPresent(listener -> {
            bothTableNames().forEach(tableName -> listener.listenToNotificationsFor(tableName, QueueTableNotification.class));
            listener.getEventBus().addAsyncSubscriber(new AnnotatedEventHandler() {
                @Handler
                void handle(QueueTableNotification notification) {
                    try {
                        var queueName = QueueName.of(notification.queueName);
                        var consumer  = durableQueueConsumers.get(queueName);
                        if (consumer != null) {
                            consumer.messageAdded(PostgresqlDurableQueues.createDefaultQueuedMessage(notification, queueName));
                        }
                    } catch (Exception e) {
                        log.error("Error occurred while handling notification", e);
                    }
                }
            });
        });
    }

    private List<String> bothTableNames() {
        return List.of(unorderedTableName, orderedTableName);
    }

    /**
     * Backoff only makes sense when something can end it. With no {@link MultiTableChangeListener} there are no
     * wake-ups, so a backed-off queue would simply be slower with nothing to recover it - hence
     * {@link QueuePollingOptimizer#None()}. This mirrors how v1 resolves its own optimizer.
     */
    private QueuePollingOptimizer createDefaultQueuePollingOptimizerFor(QueueName queueName) {
        if (multiTableChangeListener.isEmpty()) {
            return QueuePollingOptimizer.None();
        }
        var pollingIntervalMs = settings.pollingInterval().toMillis();
        return new CentralizedQueuePollingOptimizer(queueName,
                                                    Math.max(1L, (long) (pollingIntervalMs * 0.5d)),
                                                    pollingIntervalMs * 20,
                                                    1.5,
                                                    0.1);
    }

    /**
     * Moves every message from a v1 shared queue table into this instance's two tables, so a deployment with a
     * backlog can switch to the split without stranding it.
     * <p>
     * <b>The split does not read the shared table.</b> Pointing a split instance at a base name whose v1 table
     * still holds messages leaves those messages invisible - not lost, but never delivered - which is why this
     * exists rather than a line in the release notes saying "drain first".
     * <p>
     * It is a plain {@code INSERT ... SELECT} per mode, and it is that simple only because the split tables keep
     * v1's columns exactly (see {@code docs/durable-queues-implementation-plan.md} §7c): no column mapping, no
     * re-serialization, no id rewriting. Delivery counts, timestamps, dead-letter state and last errors all carry
     * over unchanged, so a half-delivered message keeps its history.
     *
     * <h2>Run it with the old consumers stopped</h2>
     * <b>This refuses to run if any row in the shared table is marked {@code is_being_delivered}</b>, which is the
     * observable signature of a v1 instance still consuming. That check is the difference between a documented
     * procedure and an enforced one: migrating rows out from under a live v1 pod would hand the same message to
     * two instances. It is not a distributed lock - a v1 pod that is idle at this instant passes the check - so
     * the procedure is still: stop the old consumers, migrate, start the new ones.
     * <p>
     * Everything runs in one transaction under the bootstrap lock, so a concurrent start of another split
     * instance cannot interleave with it, and a failure leaves the shared table untouched. The shared table is
     * emptied but <b>not dropped</b> - dropping it is the operator's call once they are satisfied, and it is what
     * makes this reversible up to that point.
     *
     * @param sharedQueueTableName the v1 table to migrate from - typically
     *                             {@link PostgresqlDurableQueues#DEFAULT_DURABLE_QUEUES_TABLE_NAME}. Concatenated
     *                             into SQL, so it must be a trusted value
     * @return how many messages moved into each table
     * @throws IllegalStateException if the shared table still has messages being delivered
     */
    public MigrationResult migrateFromSharedTable(String sharedQueueTableName) {
        requireNonNull(sharedQueueTableName, "No sharedQueueTableName provided");
        PostgresqlUtil.checkIsValidTableOrColumnName(sharedQueueTableName);
        requireTrue(!sharedQueueTableName.equalsIgnoreCase(unorderedTableName) && !sharedQueueTableName.equalsIgnoreCase(orderedTableName),
                    "The shared table to migrate from cannot be one of this instance's own tables");

        return unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
            PostgresqlUtil.acquireBootstrapLock(unitOfWork.handle());

            var tableExists = unitOfWork.handle()
                                        .createQuery("SELECT to_regclass(:tableName) IS NOT NULL")
                                        .bind("tableName", sharedQueueTableName)
                                        .mapTo(Boolean.class)
                                        .one();
            if (!tableExists) {
                log.info("No shared queue table '{}' to migrate from", sharedQueueTableName);
                return new MigrationResult(0, 0);
            }

            var beingDelivered = unitOfWork.handle()
                                           .createQuery("SELECT count(*) FROM " + sharedQueueTableName + " WHERE is_being_delivered = TRUE")
                                           .mapTo(Long.class)
                                           .one();
            if (beingDelivered > 0) {
                throw new IllegalStateException(msg("Refusing to migrate from '{}': {} message(s) are marked as being delivered, "
                                                            + "which means a consumer is still running against it. Stop the consumers on the "
                                                            + "shared table, let in-flight handling finish or time out, and retry.",
                                                    sharedQueueTableName, beingDelivered));
            }

            var unorderedMoved = unitOfWork.handle()
                                           .execute("INSERT INTO " + unorderedTableName
                                                            + " SELECT * FROM " + sharedQueueTableName + " WHERE key IS NULL");
            var orderedMoved = unitOfWork.handle()
                                         .execute("INSERT INTO " + orderedTableName
                                                          + " SELECT * FROM " + sharedQueueTableName + " WHERE key IS NOT NULL");
            unitOfWork.handle().execute("DELETE FROM " + sharedQueueTableName);

            log.info("Migrated {} unordered and {} ordered message(s) out of '{}' - the table is now empty but has "
                             + "deliberately not been dropped", unorderedMoved, orderedMoved, sharedQueueTableName);
            return new MigrationResult(unorderedMoved, orderedMoved);
        });
    }

    /**
     * How many messages {@link #migrateFromSharedTable(String)} moved into each table.
     */
    public record MigrationResult(int unorderedMessagesMoved, int orderedMessagesMoved) {
        public int totalMessagesMoved() {
            return unorderedMessagesMoved + orderedMessagesMoved;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Routing helpers
    // ------------------------------------------------------------------------------------------------

    private PostgresqlDurableQueues storeFor(Message message) {
        return message instanceof OrderedMessage ? orderedStore : unorderedStore;
    }

    private List<PostgresqlDurableQueues> bothStores() {
        return List.of(unorderedStore, orderedStore);
    }

    /**
     * Runs an operation against whichever table holds the id, trying the unordered one first.
     * <p>
     * The SPI addresses messages by {@link QueueEntryId} alone, so the mode cannot be derived from the id. Two
     * statements in one transaction is the cost, and §7b explains why that is the right trade rather than making
     * the id a structured format.
     */
    private <T> Optional<T> firstPresent(java.util.function.Function<PostgresqlDurableQueues, Optional<T>> operation) {
        // Wrapped so a miss on the first table and the retry on the second share one transaction. Each delegate
        // opens its own otherwise, which turns every by-id operation on an ordered message into two commits - and
        // §7b's argument for trying both tables rests explicitly on it being "two statements, one transaction".
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            for (var store : bothStores()) {
                var result = operation.apply(store);
                if (result.isPresent()) {
                    return result;
                }
            }
            return Optional.<T>empty();
        });
    }

    private boolean anyTrue(java.util.function.Predicate<PostgresqlDurableQueues> operation) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            for (var store : bothStores()) {
                if (operation.test(store)) {
                    return true;
                }
            }
            return false;
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------------------------------------

    @Override
    public QueueEntryId queueMessage(QueueMessage operation) {
        return storeFor(operation.getMessage()).queueMessage(operation);
    }

    @Override
    public QueueEntryId queueMessageAsDeadLetterMessage(QueueMessageAsDeadLetterMessage operation) {
        return storeFor(operation.getMessage()).queueMessageAsDeadLetterMessage(operation);
    }

    /**
     * Partitions the batch by delivery mode and enqueues each part against its own table, preserving the order
     * within each part.
     * <p>
     * The returned ids are in the order the caller supplied the messages, not grouped by table: callers correlate
     * ids to messages positionally, so regrouping them would silently mismatch.
     */
    @Override
    public List<QueueEntryId> queueMessages(QueueMessages operation) {
        var messages = operation.getMessages();
        var idsByIndex = new QueueEntryId[messages.size()];

        var orderedIndexes   = new ArrayList<Integer>();
        var unorderedIndexes = new ArrayList<Integer>();
        for (var i = 0; i < messages.size(); i++) {
            (messages.get(i) instanceof OrderedMessage ? orderedIndexes : unorderedIndexes).add(i);
        }

        enqueuePartition(operation, messages, unorderedIndexes, unorderedStore, idsByIndex);
        enqueuePartition(operation, messages, orderedIndexes, orderedStore, idsByIndex);

        return Arrays.asList(idsByIndex);
    }

    private void enqueuePartition(QueueMessages operation,
                                  List<? extends Message> messages,
                                  List<Integer> indexes,
                                  PostgresqlDurableQueues store,
                                  QueueEntryId[] idsByIndex) {
        if (indexes.isEmpty()) {
            return;
        }
        // Copied into a List<Message> because that is what the builder takes, while getMessages() hands back a
        // List<? extends Message>.
        var partition = indexes.stream().<Message>map(messages::get).collect(Collectors.toCollection(ArrayList::new));
        var ids = store.queueMessages(QueueMessages.builder()
                                                   .setQueueName(operation.queueName)
                                                   .setMessages(partition)
                                                   .setDeliveryDelay(operation.getDeliveryDelay())
                                                   .build());
        for (var i = 0; i < indexes.size(); i++) {
            idsByIndex[indexes.get(i)] = ids.get(i);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // By-id operations - both tables
    // ------------------------------------------------------------------------------------------------

    @Override
    public Optional<QueuedMessage> getQueuedMessage(GetQueuedMessage operation) {
        return firstPresent(store -> store.getQueuedMessage(operation));
    }

    @Override
    public Optional<QueuedMessage> getDeadLetterMessage(GetDeadLetterMessage operation) {
        return firstPresent(store -> store.getDeadLetterMessage(operation));
    }

    @Override
    public Optional<QueueName> getQueueNameFor(QueueEntryId queueEntryId) {
        return firstPresent(store -> store.getQueueNameFor(queueEntryId));
    }

    @Override
    public boolean acknowledgeMessageAsHandled(AcknowledgeMessageAsHandled operation) {
        return anyTrue(store -> store.acknowledgeMessageAsHandled(operation));
    }

    @Override
    public int acknowledgeMessagesAsHandled(AcknowledgeMessagesAsHandled operation) {
        return bothStores().stream().mapToInt(store -> store.acknowledgeMessagesAsHandled(operation)).sum();
    }

    @Override
    public boolean deleteMessage(DeleteMessage operation) {
        return anyTrue(store -> store.deleteMessage(operation));
    }

    @Override
    public Optional<QueuedMessage> retryMessage(RetryMessage operation) {
        return firstPresent(store -> store.retryMessage(operation));
    }

    @Override
    public Optional<QueuedMessage> markAsDeadLetterMessage(MarkAsDeadLetterMessage operation) {
        return firstPresent(store -> store.markAsDeadLetterMessage(operation));
    }

    @Override
    public boolean markAsDeadLetterMessageDirect(MarkAsDeadLetterMessageDirect operation) {
        return anyTrue(store -> store.markAsDeadLetterMessageDirect(operation));
    }

    @Override
    public Optional<QueuedMessage> resurrectDeadLetterMessage(ResurrectDeadLetterMessage operation) {
        return firstPresent(store -> store.resurrectDeadLetterMessage(operation));
    }

    // ------------------------------------------------------------------------------------------------
    // Queries and counts - merged
    // ------------------------------------------------------------------------------------------------

    @Override
    public Set<QueueName> getQueueNames() {
        var queueNames = bothStores().stream()
                                     .flatMap(store -> store.getQueueNames().stream())
                                     .collect(Collectors.toCollection(HashSet::new));
        queueNames.addAll(getActiveQueueNames());
        return queueNames;
    }

    /**
     * From this class's registry, not the delegates': the delegates never have a consumer registered on them.
     */
    @Override
    public Set<QueueName> getActiveQueueNames() {
        return Set.copyOf(durableQueueConsumers.keySet());
    }

    @Override
    public long getTotalMessagesQueuedFor(GetTotalMessagesQueuedFor operation) {
        return bothStores().stream().mapToLong(store -> store.getTotalMessagesQueuedFor(operation)).sum();
    }

    @Override
    public long getTotalDeadLetterMessagesQueuedFor(GetTotalDeadLetterMessagesQueuedFor operation) {
        return bothStores().stream().mapToLong(store -> store.getTotalDeadLetterMessagesQueuedFor(operation)).sum();
    }

    @Override
    public QueuedMessageCounts getQueuedMessageCountsFor(GetQueuedMessageCountsFor operation) {
        var counts = bothStores().stream().map(store -> store.getQueuedMessageCountsFor(operation)).toList();
        return new QueuedMessageCounts(operation.queueName,
                                       counts.stream().mapToLong(QueuedMessageCounts::numberOfQueuedMessages).sum(),
                                       counts.stream().mapToLong(QueuedMessageCounts::numberOfQueuedDeadLetterMessages).sum());
    }

    @Override
    public List<QueuedMessage> getQueuedMessages(GetQueuedMessages operation) {
        return pagedAcrossBothTables(operation.getQueueingSortOrder(),
                                     operation.getStartIndex(),
                                     operation.getPageSize(),
                                     (store, pageSize) -> store.getQueuedMessages(GetQueuedMessages.builder()
                                                                                                   .setQueueName(operation.queueName)
                                                                                                   .setQueueingSortOrder(operation.getQueueingSortOrder())
                                                                                                   .setStartIndex(0)
                                                                                                   .setPageSize(pageSize)
                                                                                                   .build()));
    }

    @Override
    public List<QueuedMessage> getDeadLetterMessages(GetDeadLetterMessages operation) {
        return pagedAcrossBothTables(operation.getQueueingSortOrder(),
                                     operation.getStartIndex(),
                                     operation.getPageSize(),
                                     (store, pageSize) -> store.getDeadLetterMessages(GetDeadLetterMessages.builder()
                                                                                                           .setQueueName(operation.queueName)
                                                                                                           .setQueueingSortOrder(operation.getQueueingSortOrder())
                                                                                                           .setStartIndex(0)
                                                                                                           .setPageSize(pageSize)
                                                                                                           .build()));
    }

    /**
     * Exact global paging across the two tables.
     * <p>
     * <b>The offset cannot be pushed down.</b> Handing each delegate the caller's {@code startIndex} makes each
     * skip that many of <em>its own</em> rows, which returns the wrong rows and up to {@code 2 × pageSize} of
     * them. So each table is read from offset 0 up to {@code startIndex + pageSize} rows, the two are merged, and
     * the caller's window is taken from the merge.
     * <p>
     * <b>The merge order must be exactly the delegates' order</b>, or the window is taken from a differently
     * sorted list than the one each table was truncated by, and page boundaries go wrong. The delegates order by
     * {@code added_ts, id COLLATE "C"}; this compares the added timestamp as an {@link Instant} and then the id as
     * a string, which is the same total order — {@code COLLATE "C"} is byte order, and for the ASCII UUIDs
     * {@code QueueEntryId} carries that matches {@code String.compareTo}.
     * <p>
     * The cost is read amplification proportional to the page's depth: page <i>n</i> reads
     * {@code n × pageSize} rows from each table. Fine for the admin browse surface this serves, which pages
     * shallowly, and the price of exactness — a cheaper deep-page story needs a keyset cursor, which would change
     * the SPI rather than the storage.
     */
    private List<QueuedMessage> pagedAcrossBothTables(DurableQueues.QueueingSortOrder sortOrder,
                                                      long startIndex,
                                                      long pageSize,
                                                      java.util.function.BiFunction<PostgresqlDurableQueues, Long, List<QueuedMessage>> readFrom) {
        if (pageSize <= 0) {
            return List.of();
        }
        var rowsNeededPerTable = startIndex + pageSize;
        var comparator = Comparator.comparing((QueuedMessage message) -> message.getAddedTimestamp().toInstant())
                                   .thenComparing(message -> message.getId().toString());

        return bothStores().stream()
                           .map(store -> readFrom.apply(store, rowsNeededPerTable))
                           .flatMap(List::stream)
                           .sorted(sortOrder == DurableQueues.QueueingSortOrder.DESC ? comparator.reversed() : comparator)
                           .skip(startIndex)
                           .limit(pageSize)
                           .toList();
    }

    @Override
    public int purgeQueue(PurgeQueue operation) {
        return bothStores().stream().mapToInt(store -> store.purgeQueue(operation)).sum();
    }

    // ------------------------------------------------------------------------------------------------
    // Fetching - merged, so a single fetcher can serve both tables under one slot budget
    // ------------------------------------------------------------------------------------------------

    @Override
    public Optional<QueuedMessage> getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery operation) {
        // Ordered first: an ordered key that is due has a stricter delivery constraint than any unordered
        // message, so serving it first reduces the chance of a key waiting behind unrelated traffic.
        var ordered = orderedStore.getNextMessageReadyForDelivery(operation);
        return ordered.isPresent() ? ordered : unorderedStore.getNextMessageReadyForDelivery(operation);
    }

    @Override
    public List<QueuedMessage> fetchNextBatchOfMessages(Collection<QueueName> queueNames,
                                                        Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                        Map<QueueName, Integer> availableWorkerSlotsPerQueue) {
        return fetchAcrossBothTables(queueNames,
                                     availableWorkerSlotsPerQueue,
                                     (store, activeQueues, slots) ->
                                             store.claimNextBatchOfMessages(activeQueues, excludeKeysPerQueue, slots, true,
                                                                            // Each table is asked only for what it can hold. Asking the
                                                                            // unordered table for ordered messages is a scan, not an empty
                                                                            // result - its single index cannot serve `key IS NOT NULL`,
                                                                            // because that index is precisely what the split removed.
                                                                            store == orderedStore
                                                                            ? PostgresqlDurableQueues.ClaimScope.ORDERED_ONLY
                                                                            : PostgresqlDurableQueues.ClaimScope.UNORDERED_ONLY));
    }

    @Override
    public List<QueuedMessage> fetchNextBatchOfMessagesBatched(Collection<QueueName> queueNames,
                                                               Map<QueueName, Set<String>> excludeKeysPerQueue,
                                                               Map<QueueName, Integer> availableWorkerSlotsPerQueue) {
        return fetchAcrossBothTables(queueNames,
                                     availableWorkerSlotsPerQueue,
                                     (store, activeQueues, slots) -> store.claimNextBatchOfMessagesBatched(activeQueues, excludeKeysPerQueue, slots));
    }

    /**
     * A poll: pick the queues worth visiting <b>once</b>, claim from the ordered table, then claim whatever slots
     * are left from the unordered one, then report the combined outcome to the optimizers.
     * <p>
     * The registry and the optimizers live here rather than in the delegates, which is why the delegates expose
     * claiming separately from
     * {@link PostgresqlDurableQueues#fetchNextBatchOfMessages(Collection, Map, Map)}: asked through their public
     * fetch methods, each would consult its own empty consumer registry and skip every queue.
     */
    private List<QueuedMessage> fetchAcrossBothTables(Collection<QueueName> queueNames,
                                                      Map<QueueName, Integer> availableWorkerSlotsPerQueue,
                                                      ClaimFromTable claim) {
        requireNonNull(queueNames, "No queueNames provided");
        requireNonNull(availableWorkerSlotsPerQueue, "No availableWorkerSlotsPerQueue provided");

        var activeQueues = PostgresqlDurableQueues.selectQueuesReadyForPolling(queueNames, availableWorkerSlotsPerQueue, durableQueueConsumers);
        if (activeQueues.isEmpty()) {
            return List.of();
        }

        // ONE transaction across both tables, not one per table.
        //
        // Each delegate's claim opens its own UnitOfWork, and a UnitOfWorkFactory joins an ambient one rather than
        // nesting - only the outermost commits. Without this wrapper a poll therefore committed twice where v1
        // commits once, which measured as a 6x regression on the unordered drain (§21). The transaction is what
        // costs, not the statement, and that is the finding this whole investigation rests on; the composite was
        // paying it twice per poll.
        return unitOfWorkFactory.withUnitOfWork(uow -> claimAcrossBothTables(activeQueues, availableWorkerSlotsPerQueue, claim));
    }

    private List<QueuedMessage> claimAcrossBothTables(List<QueueName> activeQueues,
                                                      Map<QueueName, Integer> availableWorkerSlotsPerQueue,
                                                      ClaimFromTable claim) {
        var fromOrdered = claim.claim(orderedStore, activeQueues, availableWorkerSlotsPerQueue);

        // The remaining slot budget after the ordered table has taken its share. Without this the two tables
        // would each fill the full budget and the fetcher would hand out up to twice parallelConsumers - the
        // over-fetch that Bug #19's comment in CentralizedMessageFetcher warns about.
        var takenPerQueue = fromOrdered.stream()
                                       .collect(Collectors.groupingBy(QueuedMessage::getQueueName, Collectors.counting()));
        var remainingSlots = new HashMap<QueueName, Integer>();
        availableWorkerSlotsPerQueue.forEach((queueName, slots) ->
                                                     remainingSlots.put(queueName,
                                                                        Math.max(0, slots - takenPerQueue.getOrDefault(queueName, 0L).intValue())));
        var stillHasSlots = activeQueues.stream().filter(queueName -> remainingSlots.getOrDefault(queueName, 0) > 0).toList();

        var fromUnordered = stillHasSlots.isEmpty()
                            ? List.<QueuedMessage>of()
                            : claim.claim(unorderedStore, stillHasSlots, remainingSlots);

        var all = Stream.concat(fromOrdered.stream(), fromUnordered.stream()).toList();
        // Reported once, over both tables' results: reporting per table would tell a queue's optimizer "no
        // messages" for the ordered table even when the unordered one just served it, and it would back off.
        PostgresqlDurableQueues.reportPollingOutcome(activeQueues, all, durableQueueConsumers);
        return all;
    }

    /**
     * Which of the two claim statements a poll is using - the per-queue one or the single batched one.
     */
    private interface ClaimFromTable {
        List<QueuedMessage> claim(PostgresqlDurableQueues store, List<QueueName> activeQueues, Map<QueueName, Integer> availableWorkerSlotsPerQueue);
    }

    // ------------------------------------------------------------------------------------------------
    // Consumption - one fetcher, one registry, one parallelConsumers budget across both tables
    // ------------------------------------------------------------------------------------------------

    @Override
    public DurableQueueConsumer consumeFromQueue(ConsumeFromQueue operation) {
        requireNonNull(operation, "No operation provided");
        if (durableQueueConsumers.containsKey(operation.queueName)) {
            throw new DurableQueueException("There is already a DurableConsumer for this queue", operation.queueName);
        }
        operation.validate();

        return durableQueueConsumers.computeIfAbsent(operation.queueName, _queueName -> {
            var consumer = (CentralizedMessageFetcherDurableQueueConsumer) newInterceptorChainForOperation(operation,
                                                                                                          interceptors,
                                                                                                          (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                                                                                          () -> (DurableQueueConsumer) new CentralizedMessageFetcherDurableQueueConsumer(
                                                                                                                  operation,
                                                                                                                  this,
                                                                                                                  this::removeQueueConsumer,
                                                                                                                  centralizedMessageFetcher,
                                                                                                                  queuePollingOptimizerFactory.apply(operation.getQueueName()))).proceed();
            if (started) {
                consumer.start();
            }
            log.info("[{}] {} - {} {}",
                     operation.queueName,
                     operation.consumerName,
                     started ? "Started" : "Created",
                     consumer.getClass().getSimpleName());
            return consumer;
        });
    }

    private void removeQueueConsumer(DurableQueueConsumer durableQueueConsumer) {
        requireNonNull(durableQueueConsumer, "You must provide a durableQueueConsumer");
        requireFalse(durableQueueConsumer.isStarted(),
                     msg("Cannot remove DurableQueueConsumer '{}' since it's started!", durableQueueConsumer.queueName()));
        var operation = new StopConsumingFromQueue(durableQueueConsumer);
        newInterceptorChainForOperation(operation,
                                        interceptors,
                                        (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                        () -> {
                                            var queueName = durableQueueConsumer.queueName();
                                            centralizedMessageFetcher.unregisterConsumer(queueName);
                                            return (DurableQueueConsumer) durableQueueConsumers.remove(queueName);
                                        })
                .proceed();
    }

    // ------------------------------------------------------------------------------------------------
    // Remaining reads - both tables
    // ------------------------------------------------------------------------------------------------

    @Override
    public boolean hasMessagesQueuedFor(QueueName queueName) {
        return anyTrue(store -> store.hasMessagesQueuedFor(queueName));
    }

    /**
     * Only the ordered table can hold a message with a key, so this is not a dual lookup.
     */
    @Override
    public boolean hasOrderedMessageQueuedForKey(QueueName queueName, String key) {
        return orderedStore.hasOrderedMessageQueuedForKey(queueName, key);
    }

    @Override
    public List<NextQueuedMessage> queryForMessagesSoonReadyForDelivery(QueueName queueName,
                                                                       Instant withNextDeliveryTimestampAfter,
                                                                       int maxNumberOfMessagesToReturn) {
        return bothStores().stream()
                           .flatMap(store -> store.queryForMessagesSoonReadyForDelivery(queueName, withNextDeliveryTimestampAfter, maxNumberOfMessagesToReturn).stream())
                           .sorted()
                           .limit(maxNumberOfMessagesToReturn)
                           .toList();
    }

    // ------------------------------------------------------------------------------------------------
    // Configuration and interceptors
    // ------------------------------------------------------------------------------------------------

    @Override
    public TransactionalMode getTransactionalMode() {
        return settings.transactionalMode();
    }

    /**
     * The composite's own observer, not either delegate's - so a delivery is reported once for the queue rather
     * than once per table. This is also what makes delivery statistics work over the split with no per-table
     * configuration: collection is keyed by {@link QueueName}, which both tables share.
     */
    @Override
    public DurableQueueMessageObserver getMessageObserver() {
        return messageObserver;
    }

    @Override
    public Optional<UnitOfWorkFactory<? extends UnitOfWork>> getUnitOfWorkFactory() {
        return Optional.of(unitOfWorkFactory);
    }

    /**
     * Registers the interceptor here <em>and</em> on both delegates, and that is not double-registration: the
     * operations this class runs through its own chain - {@link ConsumeFromQueue}, {@link StopConsumingFromQueue}
     * and the message handling the fetcher drives - are never executed on a delegate, and every operation a
     * delegate runs through its chain is one this class only forwards. So each operation passes an interceptor
     * exactly once.
     */
    @Override
    public DurableQueues addInterceptor(DurableQueuesInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        log.info("Adding interceptor: {}", interceptor);
        interceptor.setDurableQueues(this);
        interceptors.add(interceptor);
        sortInterceptorsByOrder(interceptors);
        bothStores().forEach(store -> store.addInterceptor(interceptor));
        // The delegates set themselves as the interceptor's DurableQueues - point it back at the composite, which
        // is the instance a consumer or an admin operation has a handle on.
        interceptor.setDurableQueues(this);
        return this;
    }

    @Override
    public DurableQueues removeInterceptor(DurableQueuesInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        log.info("Removing interceptor: {}", interceptor);
        interceptors.remove(interceptor);
        bothStores().forEach(store -> store.removeInterceptor(interceptor));
        return this;
    }

    /**
     * @return the unordered table's name, for tests and diagnostics
     */
    public String getUnorderedTableName() {
        return unorderedTableName;
    }

    /**
     * @return the ordered table's name, for tests and diagnostics
     */
    public String getOrderedTableName() {
        return orderedTableName;
    }
}
