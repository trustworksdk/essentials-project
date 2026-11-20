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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.FailoverRedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.PatternMatchingMessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The event-log engine's counterpart of the old {@code PartitionedViewEventProcessor}: a view whose
 * {@link MessageHandler} methods run on every instance, each instance handling its share of the aggregates.
 * <pre>{@code
 * public class AccountOverviewView extends EventLogPartitionedViewProcessor {
 *     public AccountOverviewView(EventLogProcessorDependencies dependencies, DurableQueues durableQueues,
 *                                EventStoreUnitOfWorkFactory<?> unitOfWorkFactory) {
 *         super("account-overview", List.of("Accounts"), dependencies, durableQueues, unitOfWorkFactory);
 *     }
 *
 *     @MessageHandler
 *     void on(AccountOpened e, OrderedMessage message) {
 *         messageUnitOfWork().handle().execute("INSERT ...");
 *     }
 *
 *     @Override
 *     protected void onRebuild(ProjectionContext context) {
 *         context.handle().execute("TRUNCATE account_overview");
 *     }
 * }
 * }</pre>
 * <ul>
 *     <li>One instance at a time reads the events {@code CONTIGUOUS}, from the beginning (an {@link EventLogViewProcessor}
 *     inside), and queues every event a handler takes on the durable queue {@code <name>:queue} (when every handled
 *     payload type is final, only those event types are read: {@link #eventTypeFilter()}): one
 *     {@link DurableQueues#queueMessages(QueueName, List)} per page of up to {@link #forwardPageSize()} events, in the page
 *     transaction, so the messages commit with the subscription's progress and each event is queued exactly once. The
 *     enqueue joins the page transaction through {@code unitOfWorkFactory}: it must be the factory the queues and
 *     {@link EventLogProcessorDependencies#pageTransactions()} share.</li>
 *     <li>The messages are the old processor's payload messages: key {@code <aggregateType>|<aggregateId>}
 *     ({@link #KEY_SEPARATOR}), order = event order, the event's metadata plus {@link #AGGREGATE_TYPE_METADATA_KEY} and
 *     {@link #EVENT_ID_METADATA_KEY} under the old processor's names. Old reference messages are not resolved: drain the
 *     queue before switching, as when switching to the old class.</li>
 *     <li>Every instance consumes the queue with {@link #numberOfParallelQueuedMessageConsumers()} threads; the queue
 *     implementation must keep a key in order across instances ({@link #start()} refuses otherwise), so each aggregate's
 *     events are handled one at a time, in event order. A message is handled in one unit of work ({@link #messageUnitOfWork()}),
 *     with {@link CausationContext} bound to the event and the plain aggregate id as {@link OrderedMessage#getKey()};
 *     {@code UnitOfWorkMode.NONE} handlers are refused. Handlers must be idempotent, as in the old processor.</li>
 *     <li>{@link #rebuild()} clears the view ({@link #onRebuild(ProjectionContext)}), purges the queue and resets the reading
 *     side in one transaction; only while no other instance consumes the queue.</li>
 *     <li>An aggregate type not yet registered with the {@link EventLog} is registered (default tables) at {@link #start()}.</li>
 * </ul>
 * Started and stopped as a foundation {@link Lifecycle} - by the Spring Boot starter's lifecycle manager, or by hand.
 */
public abstract class EventLogPartitionedViewProcessor implements Lifecycle {
    /** Separates the aggregate type from the aggregate id in the queue key, as the old processor's {@code KEY_SEPARATOR} */
    public static final String KEY_SEPARATOR               = "|";
    /** {@link MessageMetaData} key of the queued event's aggregate type - the old processor's key */
    public static final String AGGREGATE_TYPE_METADATA_KEY = "PARTITIONED_VIEW_AGGREGATE_TYPE";
    /** {@link MessageMetaData} key of the queued event's id, bound as the cause - the old processor's key */
    public static final String EVENT_ID_METADATA_KEY       = "PARTITIONED_VIEW_EVENT_ID";

    private static final Logger log = LoggerFactory.getLogger(EventLogPartitionedViewProcessor.class);

    private final String                                  name;
    private final List<String>                            aggregateTypes;
    private final EventLogProcessorDependencies           dependencies;
    private final DurableQueues                           durableQueues;
    private final UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;
    private final PatternMatchingMessageHandler           handler;
    private final QueueName                               queueName;
    private final LifecycleGate                           gate  = new LifecycleGate();
    private ProcessorSeams                                seams = ProcessorSeams.NONE;
    private PageForwarder                                 forwarder;
    private DurableQueueConsumer                          consumer;
    private volatile boolean                              started;

    /**
     * @param name              the processor name: the subscriber name of each aggregate type's subscription; the queue is {@code <name>:queue}
     * @param aggregateTypes    the aggregate types whose events the view handles
     * @param dependencies      the event log, serializer and page transactions
     * @param durableQueues     the queue the events go through; must keep a key in order across instances
     * @param unitOfWorkFactory the factory the page transactions, the queues and the handlers' units of work share
     */
    protected EventLogPartitionedViewProcessor(String name,
                                               List<String> aggregateTypes,
                                               EventLogProcessorDependencies dependencies,
                                               DurableQueues durableQueues,
                                               UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.name = requireNonBlank(name, "No name provided");
        this.aggregateTypes = List.copyOf(requireNonEmpty(aggregateTypes, "No aggregateTypes provided"));
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.queueName = QueueName.of(name + ":queue");
        this.handler = new PatternMatchingMessageHandler(this, List.of());
        handler.allowUnmatchedMessages();
    }

    /** @return the queue key of an aggregate's events: {@code <aggregateType>|<aggregateId>}, as the old processor's {@code qualifiedKey} */
    public static String qualifiedKey(String aggregateType, String serializedAggregateId) {
        return requireNonNull(aggregateType, "No aggregateType provided") + KEY_SEPARATOR + requireNonNull(serializedAggregateId, "No serializedAggregateId provided");
    }

    /** 1 unless overridden: the consumer threads on this instance */
    protected int numberOfParallelQueuedMessageConsumers() {
        return 1;
    }

    /** A {@link FailoverRedeliveryPolicy} outlasting the queue's {@link DurableQueues#maxUnservedAfterInstanceLoss()}, as the old processor's default */
    protected RedeliveryPolicy durableQueueRedeliveryPolicy() {
        return FailoverRedeliveryPolicy.outlasting(Optional.empty(), durableQueues.maxUnservedAfterInstanceLoss()).policy();
    }

    /** @return the most events queued in one page transaction: the engine's page size unless overridden (the old {@code getForwardBatchSize()}) */
    protected int forwardPageSize() {
        return Math.min(dependencies.eventLog().settings().pageSize(), ErrorSettings.MAX_PAGE_SIZE);
    }

    /** Empty: the engine's retry and backoff defaults; the page size is always {@link #forwardPageSize()} */
    protected Optional<ErrorSettings> errorSettings() {
        return Optional.empty();
    }

    /**
     * The event types this processor subscribes to. The engine selects only events of these types in SQL; an event of any
     * other type advances the subscription's position without being read, deserialized or delivered.
     * <p>
     * By default derived once, when the subscriptions are built, from the payload parameter types of the
     * {@link MessageHandler} methods: when every one is a final class (a record, an enum, a final class) the filter holds
     * exactly their event type names ({@code FQCN:} + class name); when any is an interface, an abstract or other
     * non-final class, {@code Object} or a JDK type such as {@code String}, it is {@link EventTypeFilter#all()}, because a
     * handler for such a type also receives events of other classes. The choice and its reason are logged at INFO.
     * <p>
     * Override it to widen the filter, for example return {@link EventTypeFilter#all()} when the event serializer maps
     * stored event type names to classes other than the named ones, or to add names. Changing the filter of an existing
     * subscription keeps its position: events behind it are not delivered again.
     *
     * @return the filter; never {@code null}
     */
    protected EventTypeFilter eventTypeFilter() {
        return HandledEventTypes.derive(name, getClass());
    }

    /**
     * Clear the view at the start of a {@link #rebuild()}: delete or truncate what the handlers write. Does nothing unless
     * overridden. Runs in the rebuild's transaction, opened from the page source (write through {@link ProjectionContext#handle()}
     * or the bound unit of work / Spring transaction); its writes commit with the queue purge and the progress reset, or not
     * at all. It must not end the transaction.
     */
    protected void onRebuild(ProjectionContext context) throws Exception {
    }

    /** @return the unit of work the current message is handled in */
    protected UnitOfWork messageUnitOfWork() {
        return unitOfWorkFactory.getRequiredUnitOfWork();
    }

    public String name() {
        return name;
    }

    public List<String> aggregateTypes() {
        return aggregateTypes;
    }

    /** @return {@code <name>:queue} */
    public QueueName queueName() {
        return queueName;
    }

    public DurableQueues durableQueues() {
        return durableQueues;
    }

    /** @return one projection per aggregate type, those of the reading side; empty before the first start */
    public List<ViewProjection> projections() {
        var current = forwarder;
        return current == null ? List.of() : current.projections();
    }

    /**
     * Start consuming the queue on this instance, then the reading side, which registers any unregistered aggregate type.
     * Idempotent; start, stop and rebuild run one at a time through a {@link LifecycleGate}.
     *
     * @throws IllegalStateException if the queues do not keep a key in order across instances, or a handler is {@code UnitOfWorkMode.NONE}
     */
    @Override
    public void start() {
        try (var ignored = gate.enter()) {
            if (started) {
                return;
            }
            if (!durableQueues.keepsKeyOrderAcrossInstances()) {
                throw new IllegalStateException("[" + name + "] EventLogPartitionedViewProcessor needs a queue implementation that keeps an ordered message key in order across instances, which "
                                                + durableQueues.getClass().getSimpleName() + " does not. Use the shard-owned queue engine, or extend EventLogViewProcessor");
            }
            if (handler.hasNonTransactionalMessageHandlers()) {
                throw new IllegalStateException("[" + name + "] declares a @MessageHandler with UnitOfWorkMode.NONE: a partitioned view handles each message in one unit of work");
            }
            startConsuming();
            try {
                if (forwarder == null) {
                    forwarder = new PageForwarder(this);
                }
                forwarder.start();
            } catch (RuntimeException e) {
                stopConsuming();
                throw e;
            }
            started = true;
        }
        log.info("[{}] Started: queuing the events of {} on '{}', consumed with {} thread(s) on this instance", name, aggregateTypes, queueName, numberOfParallelQueuedMessageConsumers());
    }

    @Override
    public void stop() {
        try (var ignored = gate.enter()) {
            if (!started) {
                return;
            }
            try {
                forwarder.stop();
            } finally {
                stopConsuming();
                started = false;
            }
        }
        log.info("[{}] Stopped", name);
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * Rebuild the view from the start, as one lifecycle transition: stop this instance's consumers, check that no instance
     * consumes the queue, then in ONE transaction of {@link EventLogProcessorDependencies#pageTransactions()} call
     * {@link #onRebuild(ProjectionContext)}, purge the queue (queued, in-flight and dead-lettered messages) and reset every
     * aggregate type's reading side to the start; commit, and start again if it was started. The cleared view, the empty
     * queue and the reset progress commit together or not at all. Call it outside a unit of work.
     *
     * @return {@code false} if no aggregate type has progress yet: nothing reset, the clear and the purge rolled back
     * @throws IllegalStateException if another instance consumes the queue or that cannot be checked (stop the processor
     *                               on every other instance first), or the rebuild failed (rolled back)
     */
    public boolean rebuild() {
        boolean reset;
        try (var ignored = gate.enter()) {
            if (forwarder == null) {
                forwarder = new PageForwarder(this);
            }
            var wasStarted = started;
            if (wasStarted) {
                stopConsuming();
            }
            try {
                var consumers = durableQueues.getQueueConsumers(queueName)
                                             .orElseThrow(() -> new IllegalStateException("[" + name + "] Cannot rebuild: " + durableQueues.getClass().getSimpleName()
                                                                                          + " cannot tell who consumes '" + queueName + "'"));
                if (!consumers.none()) {
                    throw new IllegalStateException("[" + name + "] Cannot rebuild while other instances consume '" + queueName + "' (" + consumers
                                                    + "): stop the processor on every other instance first");
                }
                reset = forwarder.rebuild();
            } finally {
                if (wasStarted) {
                    startConsuming();
                }
            }
        }
        log.info("[{}] Rebuilt: {}", name, reset ? "queue purged, replaying from the start" : "no progress yet, nothing reset");
        return reset;
    }

    /** Test-only: mutants and subscribe path, before the first {@link #start()} */
    void seams(ProcessorSeams seams) {
        try (var ignored = gate.enter()) {
            requireTrue(forwarder == null, "Seams must be set before the first start");
            this.seams = requireNonNull(seams, "No seams provided");
        }
    }

    private void startConsuming() {
        consumer = durableQueues.consumeFromQueue(queueName,
                                                  durableQueueRedeliveryPolicy(),
                                                  numberOfParallelQueuedMessageConsumers(),
                                                  queuedMessage -> unitOfWorkFactory.usingUnitOfWork(uow -> handle(queuedMessage.getMessage())));
    }

    private void stopConsuming() {
        var current = consumer;
        consumer = null;
        if (current != null) {
            current.cancel();
        }
    }

    /** Runs inside the page transaction: one enqueue for the page's handled events, committed with the page's progress */
    void forward(List<DeliveredEvent> page) {
        var messages = new ArrayList<OrderedMessage>(page.size());
        for (var event : page) {
            if (handler.handlesMessageWithPayload(event.payload().getClass())) {
                messages.add(toMessage(event));
            }
        }
        if (messages.isEmpty()) {
            return;
        }
        if (seams.has(ProcessorMutant.PARTITIONED_ENQUEUE_OUTSIDE_PAGE_TRANSACTION)) {
            log.warn("[{}] MUTANT PARTITIONED_ENQUEUE_OUTSIDE_PAGE_TRANSACTION: {} message(s) enqueued in a unit of work of their own", name, messages.size());
            CompletableFuture.runAsync(() -> unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessages(queueName, messages))).join();
            return;
        }
        unitOfWorkFactory.getRequiredUnitOfWork();
        durableQueues.queueMessages(queueName, messages);
    }

    /** Runs inside the rebuild transaction, after {@link #onRebuild(ProjectionContext)} and before the progress reset */
    void purgeInRebuild() {
        if (seams.has(ProcessorMutant.PARTITIONED_REBUILD_LEAVES_QUEUED_MESSAGES)) {
            log.warn("[{}] MUTANT PARTITIONED_REBUILD_LEAVES_QUEUED_MESSAGES: '{}' not purged", name, queueName);
            return;
        }
        unitOfWorkFactory.getRequiredUnitOfWork();
        durableQueues.purgeQueue(queueName);
    }

    @SuppressWarnings("PMD.LooseCoupling") // MessageMetaData is the type OrderedMessage's constructor takes
    static ForwardedEvent toMessage(DeliveredEvent event) {
        var aggregateType = event.aggregateType().toString();
        var metaData      = new MessageMetaData(new HashMap<>(event.metaData()));
        metaData.put(AGGREGATE_TYPE_METADATA_KEY, aggregateType);
        metaData.put(EVENT_ID_METADATA_KEY, event.eventId().toString());
        return new ForwardedEvent(event.payload(), qualifiedKey(aggregateType, event.raw().aggregateId()), event.eventOrder().longValue(), metaData);
    }

    /** Hands the handlers the plain aggregate id, bound to the event as its cause, as the old processor does */
    void handle(Message message) {
        if (!(message instanceof OrderedMessage ordered) || ordered.getMetaData().get(AGGREGATE_TYPE_METADATA_KEY) == null) {
            if (message.getPayload() != null && message.getPayload().getClass().getName().endsWith(".AggregateType")) {
                throw new IllegalStateException("[" + name + "] A message referring to an event (queued by the old PartitionedViewEventProcessor) cannot be resolved here: drain '"
                                                + queueName + "' before switching");
            }
            handler.accept(message);
            return;
        }
        var prefix  = ordered.getMetaData().get(AGGREGATE_TYPE_METADATA_KEY) + KEY_SEPARATOR;
        var key     = ordered.getKey();
        var plain   = OrderedMessage.of(ordered.getPayload(), key.startsWith(prefix) ? key.substring(prefix.length()) : key, ordered.getOrder(), ordered.getMetaData());
        var eventId = ordered.getMetaData().get(EVENT_ID_METADATA_KEY);
        if (eventId != null) {
            CausationContext.where(EventId.of(eventId)).run(() -> handler.accept(plain));
        } else {
            handler.accept(plain);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " on " + aggregateTypes + "]";
    }

    /** An event queued with its payload, identified by key and order, so a re-queue of one still queued is absorbed */
    static final class ForwardedEvent extends OrderedMessage implements IdentifiedByKeyAndOrder {
        @SuppressWarnings("PMD.LooseCoupling") // MessageMetaData is the type OrderedMessage's constructor takes
        ForwardedEvent(Object payload, String key, long order, MessageMetaData metaData) {
            super(payload, key, order, metaData);
        }
    }

    /**
     * The reading side: an {@link EventLogViewProcessor} applying whole pages, each through the owner's
     * {@link #forward(List)}; its rebuild hook clears the owner's view and purges the queue in the reset transaction.
     */
    private static final class PageForwarder extends EventLogViewProcessor {
        private final EventLogPartitionedViewProcessor owner;
        private final ErrorSettings                    errorSettings;

        PageForwarder(EventLogPartitionedViewProcessor owner) {
            super(owner.name, owner.aggregateTypes, owner.dependencies);
            this.owner = owner;
            var base     = owner.errorSettings().orElseGet(() -> ErrorSettings.defaults(owner.dependencies.eventLog().settings()));
            var pageSize = owner.forwardPageSize();
            requireTrue(pageSize >= 1 && pageSize <= ErrorSettings.MAX_PAGE_SIZE, "forwardPageSize must be 1.." + ErrorSettings.MAX_PAGE_SIZE + ", was " + pageSize);
            this.errorSettings = new ErrorSettings(base.retryAttempts(), base.backoffStart(), base.backoffMax(), pageSize);
            seams(owner.seams);
        }

        @Override
        protected Optional<ErrorSettings> errorSettings() {
            return Optional.of(errorSettings);
        }

        /** The owner's filter: the forwarder's own handler takes every payload */
        @Override
        protected EventTypeFilter eventTypeFilter() {
            return owner.eventTypeFilter();
        }

        @Override
        ViewProjection.Builder applier(ViewProjection.Builder builder) {
            return builder.onPage((events, context) -> owner.forward(events));
        }

        @Override
        protected void onRebuild(ProjectionContext context) throws Exception {
            owner.onRebuild(context);
            owner.purgeInRebuild();
        }
    }
}
