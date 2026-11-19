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
package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.components.eventlog.ErrorSettings;
import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.FailoverRedeliveryPolicy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The new-engine twin of {@link TradingDemoEventProcessor}: an {@link EventLogPageForwarder} reads the aggregate types'
 * events ({@code CONTIGUOUS}, from the beginning as the classic processor's subscription starts, one instance at a time)
 * and puts each one whose payload a {@code @MessageHandler} method of this class handles into the {@link Inbox} named
 * after the processor - the same inbox, consumption mode, parallel
 * consumers and failover redelivery policy as the classic processor - and the inbox's consumer calls those methods
 * through foundation's {@link PatternMatchingMessageHandler}, so a handler keeps its classic shape: payload and an
 * optional {@code OrderedMessage}, with {@link CausationContext} bound to the event.
 * <p>
 * A whole page of up to {@link #forwardPageSize()} events is forwarded in one transaction, the page's: the enqueues
 * commit together with the subscription's progress, so an event is forwarded exactly once, and the forward costs one
 * commit per page rather than one per event (the classic processor's {@code getForwardBatchSize()} equivalent).
 * <p>
 * The event is forwarded with its payload (the classic processor forwards a reference and reads the event back). Each
 * {@code @MessageHandler} runs in its own unit of work ({@code UnitOfWorkMode} as declared on it), as in the classic
 * processor. A {@link Lifecycle} bean, started by the components starter's lifecycle manager.
 */
public abstract class TradingDemoEventLogInboxProcessor implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(TradingDemoEventLogInboxProcessor.class);

    private final String                                                    name;
    private final List<AggregateType>                                       aggregateTypes;
    private final EventLogProcessorDependencies                             dependencies;
    private final Inboxes                                                   inboxes;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final PatternMatchingMessageHandler                             handler;
    private       Inbox                                                     inbox;
    private       EventLogPageForwarder                                     forwarder;
    private final LifecycleGate                                             gate = new LifecycleGate();
    private volatile boolean                                                started;

    protected TradingDemoEventLogInboxProcessor(String name,
                                                List<AggregateType> aggregateTypes,
                                                EventLogProcessorDependencies dependencies,
                                                Inboxes inboxes,
                                                EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
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
     * @return every instance when the queues keep an ordered key in order across instances, else one instance
     */
    protected MessageConsumptionMode inboxMessageConsumptionMode() {
        return inboxes.keepsKeyOrderAcrossInstances() ? MessageConsumptionMode.GlobalCompetingConsumers : MessageConsumptionMode.SingleGlobalConsumer;
    }

    protected int numberOfParallelInboxMessageConsumers() {
        return 1;
    }

    protected RedeliveryPolicy inboxRedeliveryPolicy() {
        return FailoverRedeliveryPolicy.outlasting(Optional.empty(), inboxes.maxUnservedAfterInstanceLoss()).policy();
    }

    /**
     * @return the most events forwarded in one transaction: the engine's {@code essentials.event-log.page-size} unless overridden
     */
    protected int forwardPageSize() {
        return Math.min(dependencies.eventLog().settings().pageSize(), ErrorSettings.MAX_PAGE_SIZE);
    }

    public String name() {
        return name;
    }

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
            if (forwarder == null) {
                forwarder = new EventLogPageForwarder(name, aggregateTypes, dependencies, forwardPageSize(), this::forward);
            }
            try {
                forwarder.start();
            } catch (RuntimeException e) {
                inbox.stopConsuming();
                throw e;
            }
            started = true;
        }
        log.info("[{}] Started: forwarding {} events into its inbox", name, aggregateTypes);
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
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * Runs inside the page transaction: the demo's unit of work factory is Spring transaction aware, so the inbox's
     * enqueue joins the page's transaction and commits with the page and its progress. Outside a page it fails.
     */
    private void forward(DeliveredEvent event) {
        if (!handler.handlesMessageWithPayload(event.payload().getClass())) {
            return;
        }
        var message = new EventLogForwarder.ForwardedEvent(event.payload(), event.raw().aggregateId(), event.eventOrder().longValue(), EventLogForwarder.messageMetaData(event));
        unitOfWorkFactory.getRequiredUnitOfWork();
        inbox.addMessageReceived(message);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " on " + aggregateTypes + "]";
    }

    /**
     * Owns the unit of work boundary, as the classic processor's consumer does: the {@link PatternMatchingMessageHandler}
     * opens one per handler call, so {@code UnitOfWorkMode.NONE} handlers get none.
     */
    private final class InboxConsumer implements UnitOfWorkBoundaryOwningMessageConsumer {
        @Override
        public void accept(Message message) {
            var eventId = message.getMetaData().get(EventLogForwarder.EVENT_ID_METADATA_KEY);
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
