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

import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.PatternMatchingMessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.jdbi.v3.core.Handle;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The new-engine twin of {@link TradingDemoPartitionedViewEventProcessor}: an {@link EventLogForwarder} reads the
 * aggregate types' events ({@code FRESH}, per-aggregate order, one instance at a time) and queues each one this
 * class handles, with its payload, on the durable queue {@code <name>:queue} keyed by aggregate type and id
 * ({@link PartitionedViewEventProcessor#qualifiedKey(AggregateType, String)}); every instance consumes that queue
 * ({@code trading-demo.views.partitioned-consumers} threads each), so the shard-owned engine spreads the aggregates
 * over the instances and keeps each aggregate's events in order.
 * <p>
 * A message is handled in one unit of work: a handler writes through {@link #messageHandle()} and its
 * {@code @MessageHandler} methods take the payload and optionally the {@code OrderedMessage} (whose key is the plain
 * aggregate id), with {@link CausationContext} bound to the event. {@code UnitOfWorkMode.NONE} handlers are refused,
 * as in the classic processor. A {@link Lifecycle} bean, started by the components starter's lifecycle manager.
 */
public abstract class TradingDemoEventLogPartitionedViewProcessor implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(TradingDemoEventLogPartitionedViewProcessor.class);
    private static final String AGGREGATE_TYPE_METADATA_KEY = "EVENT_LOG_FORWARDED_AGGREGATE_TYPE";

    private final String                                                      name;
    private final List<AggregateType>                                         aggregateTypes;
    private final EventLogProcessorDependencies                               dependencies;
    private final DurableQueues                                               durableQueues;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final PatternMatchingMessageHandler                               handler;
    private final QueueName                                                   queueName;
    private       int                                                         consumers = 1;
    private       DurableQueueConsumer                                        queueConsumer;
    private       EventLogForwarder                                           forwarder;
    private final LifecycleGate                                               gate = new LifecycleGate();
    private volatile boolean                                                  started;

    protected TradingDemoEventLogPartitionedViewProcessor(String name,
                                                          List<AggregateType> aggregateTypes,
                                                          EventLogProcessorDependencies dependencies,
                                                          DurableQueues durableQueues,
                                                          EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.name = requireNonBlank(name, "No name provided");
        this.aggregateTypes = List.copyOf(requireNonEmpty(aggregateTypes, "No aggregateTypes provided"));
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.durableQueues = requireNonNull(durableQueues, "No durableQueues provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.queueName = QueueName.of(name + ":queue");
        this.handler = new PatternMatchingMessageHandler(this, List.of());
        handler.allowUnmatchedMessages();
        if (handler.hasNonTransactionalMessageHandlers()) {
            throw new IllegalStateException(name + " declares a @MessageHandler with UnitOfWorkMode.NONE: a partitioned view handles each message in one unit of work");
        }
    }

    @Value("${trading-demo.views.partitioned-consumers:1}")
    void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    protected RedeliveryPolicy queueRedeliveryPolicy() {
        return FailoverRedeliveryPolicy.outlasting(Optional.empty(), durableQueues.maxUnservedAfterInstanceLoss()).policy();
    }

    /**
     * @return the JDBI handle of the unit of work the current message is handled in
     */
    protected Handle messageHandle() {
        return unitOfWorkFactory.getRequiredUnitOfWork().handle();
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
            queueConsumer = durableQueues.consumeFromQueue(queueName,
                                                           queueRedeliveryPolicy(),
                                                           consumers,
                                                           queuedMessage -> unitOfWorkFactory.usingUnitOfWork(uow -> handle(queuedMessage.getMessage())));
            if (forwarder == null) {
                forwarder = new EventLogForwarder(name, aggregateTypes, dependencies, this::forward);
            }
            try {
                forwarder.start();
            } catch (RuntimeException e) {
                queueConsumer.cancel();
                throw e;
            }
            started = true;
        }
        log.info("[{}] Started: forwarding {} events to '{}', consumed with {} thread(s) on this instance", name, aggregateTypes, queueName, consumers);
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
                queueConsumer.cancel();
                queueConsumer = null;
                started = false;
            }
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    private void forward(DeliveredEvent event) {
        if (!handler.handlesMessageWithPayload(event.payload().getClass())) {
            return;
        }
        var metaData = EventLogForwarder.messageMetaData(event);
        metaData.put(AGGREGATE_TYPE_METADATA_KEY, event.aggregateType().toString());
        var message = new EventLogForwarder.ForwardedEvent(event.payload(),
                                                                          PartitionedViewEventProcessor.qualifiedKey(event.aggregateType(), event.raw().aggregateId()),
                                                                          event.eventOrder().longValue(),
                                                                          metaData);
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(queueName, message));
    }

    private void handle(Message message) {
        if (!(message instanceof OrderedMessage ordered) || ordered.getMetaData().get(AGGREGATE_TYPE_METADATA_KEY) == null) {
            handler.accept(message);
            return;
        }
        // Hand the handlers the plain aggregate id, as the classic processor does
        var prefix  = ordered.getMetaData().get(AGGREGATE_TYPE_METADATA_KEY) + PartitionedViewEventProcessor.KEY_SEPARATOR;
        var key     = ordered.getKey();
        var plain   = OrderedMessage.of(ordered.getPayload(), key.startsWith(prefix) ? key.substring(prefix.length()) : key, ordered.getOrder(), ordered.getMetaData());
        var eventId = ordered.getMetaData().get(EventLogForwarder.EVENT_ID_METADATA_KEY);
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
}
