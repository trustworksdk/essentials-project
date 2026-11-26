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
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The event-log engine's counterpart of the old inbox-based {@code EventProcessor}: it reads the
 * events of its aggregate types and puts each one whose payload a {@link MessageHandler} method of the subclass handles
 * into the {@link Inbox} named after the processor; the inbox's consumer calls that method through foundation's
 * {@link PatternMatchingMessageHandler}, with {@link CausationContext} bound to the event. Porting an old
 * {@code EventProcessor} keeps its handlers as they are.
 * <pre>{@code
 * public class PaymentRouterReaction extends EventLogInboxProcessor {
 *     public PaymentRouterReaction(EventLogProcessorDependencies dependencies, Inboxes inboxes,
 *                                  EventStoreUnitOfWorkFactory<?> unitOfWorkFactory, PaymentRouter router) {
 *         super("payment-router", List.of("Payments"), dependencies, inboxes, unitOfWorkFactory);
 *         this.router = router;
 *     }
 *
 *     @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
 *     void on(DepositRequested e) {
 *         router.on(e);
 *     }
 * }
 * }</pre>
 * <ul>
 *     <li>The events are read {@code CONTIGUOUS}, from the beginning, by one instance at a time (an
 *     {@link EventLogViewProcessor} inside). A page of up to {@link #forwardPageSize()} events is forwarded in one page
 *     transaction: every enqueue commits together with the subscription's progress, so each event is forwarded exactly
 *     once, at one commit per page (the old processor's {@code getForwardBatchSize()}).</li>
 *     <li>The enqueue joins the page transaction through {@code unitOfWorkFactory}, so that factory must be the one the
 *     inbox's queues and {@link EventLogProcessorDependencies#pageTransactions()} share - in Spring the transaction-aware
 *     one. Outside a page the enqueue fails.</li>
 *     <li>An event is forwarded with its payload (the old processor forwards a reference and reads the event back), keyed
 *     by aggregate id and ordered by event order, with the event's metadata and its event id under
 *     {@link #EVENT_ID_METADATA_KEY}. A payload no handler takes is not forwarded; when every handled payload type is
 *     final it is not even read ({@link #eventTypeFilter()}).</li>
 *     <li>Each handler runs in its own unit of work as its {@link MessageHandler#unitOfWork()} declares; failures follow
 *     {@link #inboxRedeliveryPolicy()}, as in the old processor.</li>
 *     <li>An aggregate type not yet registered with the {@link EventLog} is registered (default tables) at {@link #start()}.</li>
 * </ul>
 * Started and stopped as a foundation {@link Lifecycle} - by the Spring Boot starter's lifecycle manager, or by hand.
 */
public abstract class EventLogInboxProcessor implements Lifecycle {
    /** {@link MessageMetaData} key of the forwarded event's id: the inbox consumer binds it as the cause */
    public static final String EVENT_ID_METADATA_KEY = "EVENT_LOG_FORWARDED_EVENT_ID";

    private static final Logger log = LoggerFactory.getLogger(EventLogInboxProcessor.class);

    private final String                                     name;
    private final List<String>                               aggregateTypes;
    private final EventLogProcessorDependencies              dependencies;
    private final Inboxes                                    inboxes;
    private final UnitOfWorkFactory<? extends UnitOfWork>    unitOfWorkFactory;
    private final PatternMatchingMessageHandler              handler;
    private final LifecycleGate                              gate  = new LifecycleGate();
    private ProcessorSeams                                   seams = ProcessorSeams.NONE;
    private Inbox                                            inbox;
    private PageForwarder                                    forwarder;
    private volatile boolean                                 started;

    /**
     * @param name              the processor name: the subscriber name of each aggregate type's subscription and the inbox name
     * @param aggregateTypes    the aggregate types whose events are forwarded, started and stopped together
     * @param dependencies      the event log, serializer and page transactions
     * @param inboxes           where the processor's {@link Inbox} is created
     * @param unitOfWorkFactory the factory the page transactions, the inbox's queues and the handlers' units of work share
     */
    protected EventLogInboxProcessor(String name,
                                     List<String> aggregateTypes,
                                     EventLogProcessorDependencies dependencies,
                                     Inboxes inboxes,
                                     UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.name = requireNonBlank(name, "No name provided");
        this.aggregateTypes = List.copyOf(requireNonEmpty(aggregateTypes, "No aggregateTypes provided"));
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.inboxes = requireNonNull(inboxes, "No inboxes provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.handler = new PatternMatchingMessageHandler(this, List.of());
        handler.allowUnmatchedMessages();
        handler.setUnitOfWorkFactory(unitOfWorkFactory);
    }

    /**
     * @return {@link MessageConsumptionMode#GlobalCompetingConsumers} when the queues keep a key in order across
     * instances ({@link Inboxes#keepsKeyOrderAcrossInstances()}), else {@link MessageConsumptionMode#SingleGlobalConsumer}
     */
    protected MessageConsumptionMode inboxMessageConsumptionMode() {
        return inboxes.keepsKeyOrderAcrossInstances() ? MessageConsumptionMode.GlobalCompetingConsumers : MessageConsumptionMode.SingleGlobalConsumer;
    }

    /** 1 unless overridden */
    protected int numberOfParallelInboxMessageConsumers() {
        return 1;
    }

    /** A {@link FailoverRedeliveryPolicy} outlasting the queues' {@link Inboxes#maxUnservedAfterInstanceLoss()}, as the old processor's default */
    protected RedeliveryPolicy inboxRedeliveryPolicy() {
        return FailoverRedeliveryPolicy.outlasting(Optional.empty(), inboxes.maxUnservedAfterInstanceLoss()).policy();
    }

    /** @return the most events forwarded in one page transaction: the engine's page size unless overridden */
    protected int forwardPageSize() {
        return Math.min(dependencies.eventLog().settings().pageSize(), ErrorSettings.MAX_PAGE_SIZE);
    }

    /** Empty: the engine's retry and backoff defaults ({@link ErrorSettings#defaults(EventLogSettings)}); the page size is always {@link #forwardPageSize()} */
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

    public String name() {
        return name;
    }

    public List<String> aggregateTypes() {
        return aggregateTypes;
    }

    /** @return the processor's inbox; empty before the first {@link #start()} */
    public Optional<Inbox> inbox() {
        return Optional.ofNullable(inbox);
    }

    /** @return one projection per aggregate type, those of the reading side (for keys, status); empty before the first start */
    public List<ViewProjection> projections() {
        var current = forwarder;
        return current == null ? List.of() : current.projections();
    }

    /**
     * Create (or reuse) the inbox and start its consumer, then the reading side, which registers any unregistered
     * aggregate type. Idempotent; start and stop run one at a time through a {@link LifecycleGate}.
     */
    @Override
    public void start() {
        try (var ignored = gate.enter()) {
            if (started) {
                return;
            }
            inbox = inboxes.getOrCreateInbox(InboxConfig.builder()
                                                        .inboxName(InboxName.of(name))
                                                        .messageConsumptionMode(inboxMessageConsumptionMode())
                                                        .numberOfParallelMessageConsumers(numberOfParallelInboxMessageConsumers())
                                                        .redeliveryPolicy(inboxRedeliveryPolicy())
                                                        .build(),
                                             new InboxConsumer());
            // The Inbox outlives a stop(): stopConsuming() drops its consumer and getOrCreateInbox returns it as it is
            if (!inbox.hasAMessageConsumer()) {
                inbox.consume(new InboxConsumer());
            }
            try {
                if (forwarder == null) {
                    forwarder = new PageForwarder(this);
                }
                forwarder.start();
            } catch (RuntimeException e) {
                inbox.stopConsuming();
                throw e;
            }
            started = true;
        }
        log.info("[{}] Started: forwarding the events of {} into its inbox", name, aggregateTypes);
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
                inbox.stopConsuming();
                started = false;
            }
        }
        log.info("[{}] Stopped", name);
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /** Test-only: mutants and subscribe path, before the first {@link #start()} */
    void seams(ProcessorSeams seams) {
        try (var ignored = gate.enter()) {
            requireTrue(forwarder == null, "Seams must be set before the first start");
            this.seams = requireNonNull(seams, "No seams provided");
        }
    }

    /**
     * Runs inside the page transaction: the enqueue joins the page's unit of work and commits with the page and its
     * progress. Outside a page it fails.
     */
    void forward(DeliveredEvent event) {
        if (!handler.handlesMessageWithPayload(event.payload().getClass())) {
            return;
        }
        var message = new ForwardedEvent(event.payload(), event.raw().aggregateId(), event.eventOrder().longValue(), messageMetaData(event));
        if (seams.has(ProcessorMutant.INBOX_FORWARD_OUTSIDE_PAGE_TRANSACTION)) {
            log.warn("[{}] MUTANT INBOX_FORWARD_OUTSIDE_PAGE_TRANSACTION: {} enqueued in a unit of work of its own", name, event.globalOrder());
            CompletableFuture.runAsync(() -> unitOfWorkFactory.usingUnitOfWork(() -> inbox.addMessageReceived(message))).join();
            return;
        }
        unitOfWorkFactory.getRequiredUnitOfWork();
        inbox.addMessageReceived(message);
    }

    /** @return the event's metadata as message metadata, plus its event id under {@link #EVENT_ID_METADATA_KEY} */
    static MessageMetaData messageMetaData(DeliveredEvent event) {
        var metaData = new HashMap<String, String>(event.metaData());
        metaData.put(EVENT_ID_METADATA_KEY, event.eventId().toString());
        return new MessageMetaData(metaData);
    }

    /** The inbox's consumer: binds the event as the cause, then calls the matching {@link MessageHandler} */
    Consumer<Message> inboxConsumer() {
        return new InboxConsumer();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " on " + aggregateTypes + "]";
    }

    /**
     * An event forwarded with its payload, identified by aggregate id and event order, so a re-forward of one still
     * queued is absorbed by a queue that keeps them unique.
     */
    static final class ForwardedEvent extends OrderedMessage implements IdentifiedByKeyAndOrder {
        @SuppressWarnings("PMD.LooseCoupling") // MessageMetaData is the type OrderedMessage's constructor takes
        ForwardedEvent(Object payload, String key, long order, MessageMetaData metaData) {
            super(payload, key, order, metaData);
        }
    }

    /**
     * The reading side: an {@link EventLogViewProcessor} whose single handler takes every event and hands it to the
     * owner's {@link #forward(DeliveredEvent)}, inside the page transaction. Kept apart from the owner because
     * {@link EventLogViewProcessor} dispatches to the {@link MessageHandler} methods of {@code this}: were the owner itself
     * the view processor, it would call its own handlers directly instead of forwarding.
     */
    private static final class PageForwarder extends EventLogViewProcessor {
        private final EventLogInboxProcessor owner;
        private final ErrorSettings          errorSettings;

        PageForwarder(EventLogInboxProcessor owner) {
            super(owner.name, owner.aggregateTypes, owner.dependencies);
            this.owner = owner;
            var base     = owner.errorSettings().orElseGet(() -> ErrorSettings.defaults(owner.dependencies.eventLog().settings()));
            var pageSize = owner.forwardPageSize();
            requireTrue(pageSize >= 1 && pageSize <= ErrorSettings.MAX_PAGE_SIZE, "forwardPageSize must be 1.." + ErrorSettings.MAX_PAGE_SIZE + ", was " + pageSize);
            this.errorSettings = new ErrorSettings(base.retryAttempts(), base.backoffStart(), base.backoffMax(), pageSize, base.parkedRetryInitial(), base.parkedRetryMax());
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

        @MessageHandler
        void handle(Object payload, DeliveredEvent event) {
            owner.forward(event);
        }
    }

    /**
     * Owns the unit of work boundary, as the old processor's consumer does: the {@link PatternMatchingMessageHandler}
     * opens one per handler call, so {@code UnitOfWorkMode.NONE} handlers get none.
     */
    private final class InboxConsumer implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
            var eventId = message.getMetaData().get(EVENT_ID_METADATA_KEY);
            if (eventId != null) {
                CausationContext.where(EventId.of(eventId)).run(() -> handler.accept(message));
            } else {
                handler.accept(message);
            }
        }

        @Override
        public boolean hasNonTransactionalMessageHandlers() {
            return handler.hasNonTransactionalMessageHandlers();
        }
    }
}
