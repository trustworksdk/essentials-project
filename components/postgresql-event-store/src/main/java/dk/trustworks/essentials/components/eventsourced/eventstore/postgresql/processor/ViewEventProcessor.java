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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.fencedlock.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.slf4j.*;

import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Experimental: The {@code ViewEventProcessor} class is an abstraction for processing events that are projected into views (e.g. in a relational database).<br>
 * It integrates with a distributed locking mechanism to ensure exclusive access during processing.<br>
 * {@link PersistedEvent}'s are processed directly and only in case of an error handling the event will this event (and later events associated with the same aggregate id)
 * by queued onto the underlying durable queue associated with this processor.
 * <p>
 * The direct handling runs under a savepoint in the subscription's transaction: when it fails - a failed SQL statement
 * that aborts the transaction, or an event payload that cannot be deserialized, included - only the handler's own
 * writes are rolled back, and the event is queued in the same transaction. A failed handler that had appended events
 * through the {@link EventStore} or changed an aggregate (state the savepoint cannot undo) is not queued in that
 * transaction: the subscription's transaction is rolled back, and once its {@code SubscriptionErrorPolicy} has used up
 * its retries the event is queued in a transaction of its own instead of being skipped or stopping the subscription
 * (see {@link PersistedEventHandler#handOffFailedEvent(PersistedEvent, Throwable)}). Only if that queueing fails too does
 * the policy give up on the event. A handler that only loaded an aggregate is queued like any other failure.
 * <p>
 * <h3>Event Queuing</h3>
 * When events from the {@link EventStore} need to be queued for processing, they are converted to {@link OrderedMessage}s where:
 * <ul>
 *  <li>The event's {@link PersistedEvent#aggregateId()} becomes the {@link OrderedMessage#getKey()}</li>
 *  <li>The event's {@link PersistedEvent#eventOrder()} becomes the {@link OrderedMessage#getOrder()}</li>
 * </ul>
 * <p>
 * <h3>Validation failures inside a {@code @MessageHandler} dead-letter the message immediately</h3>
 * Once an event has been queued, the {@link DurableQueueConsumer} classifies a set of exception types as permanent
 * errors and marks the message as a Poison-Message/Dead-Letter-Message on the <em>first</em> delivery attempt,
 * bypassing the {@link RedeliveryPolicy}'s backoff entirely: {@code DurableQueueDeserializationException},
 * {@code MismatchedInputException}, {@link NoClassDefFoundError}, {@link ClassCastException} and
 * {@link IllegalArgumentException}. A match anywhere in the failure's cause chain counts.
 * <p>
 * {@link IllegalArgumentException} is the one that catches handler authors out.
 * {@code FailFast.requireNonNull(...)} and {@code requireTrue(...)} — the validation idiom used throughout
 * Essentials — both throw it, and so does Kotlin's {@code require(...)}. Opt out for a specific type with
 * {@code MessageDeliveryErrorHandler.builder().alwaysRetryOn(IllegalArgumentException.class)}, which overrides the
 * built-in list for {@link IllegalArgumentException} and {@link ClassCastException} but not for the three that can
 * never succeed on a later attempt.
 * <p>
 * This matters more for a view projector than for most handlers: a projection routinely reads state that another
 * subscription has not written yet. Throw a retryable exception for "not there yet" and reserve
 * {@link IllegalArgumentException} for a message that can never be processed. See {@code LLM/LLM-foundation.md}
 * for the full description.
 */
public abstract class ViewEventProcessor extends AbstractEventProcessor {
    private final Logger                        logger = LoggerFactory.getLogger(this.getClass());
    private final PatternMatchingMessageHandler patternMatchingMessageHandlerDelegate;
    private final FencedLockManager             fencedLockManager;
    private final DurableQueues                 durableQueues;
    private final Consumer<Message>             queuedMessageConsumer;
    private       DurableQueueConsumer          durableQueueConsumer;
    private       LockName                      lockName;

    /**
     * Constructs a {@code ViewEventProcessor} using the provided dependencies.
     *
     * @param eventProcessorDependencies the dependencies required for initializing the {@code ViewEventProcessor}.
     *                                   Must include an {@code EventStoreSubscriptionManager}, a {@code FencedLockManager},
     *                                   {@code DurableQueues}, a {@code DurableLocalCommandBus}, and a list of
     *                                   {@code MessageHandlerInterceptor}s.
     * @throws IllegalArgumentException if {@code eventProcessorDependencies} is null.
     */
    protected ViewEventProcessor(ViewEventProcessorDependencies eventProcessorDependencies) {
        this(requireNonNull(eventProcessorDependencies, "eventProcessorDependencies is null").eventStoreSubscriptionManager(),
             eventProcessorDependencies.fencedLockManager(),
             eventProcessorDependencies.durableQueues(),
             eventProcessorDependencies.commandBus(),
             eventProcessorDependencies.messageHandlerInterceptors());
    }

    /**
     * Constructs a {@code ViewEventProcessor} using the provided dependencies.
     *
     * @param subscriptionManager the {@code EventStoreSubscriptionManager} instance used for managing event store subscriptions.
     * @param fencedLockManager   the {@code FencedLockManager} instance used for obtaining distributed locks.
     * @param durableQueues       the {@code DurableQueues} instance used for managing durable message queues.
     * @param commandBus          the {@code DurableLocalCommandBus} instance used for dispatching commands locally.
     * @param interceptors        a list of {@code MessageHandlerInterceptor} instances used to intercept and process messages.
     * @throws IllegalArgumentException if any of the required parameters are null.
     */
    protected ViewEventProcessor(
            EventStoreSubscriptionManager subscriptionManager,
            FencedLockManager fencedLockManager,
            DurableQueues durableQueues,
            DurableLocalCommandBus commandBus,
            List<MessageHandlerInterceptor> interceptors) {
        super(subscriptionManager, commandBus, interceptors);
        this.fencedLockManager = requireNonNull(fencedLockManager, "fencedLockManager is null");
        this.durableQueues = requireNonNull(durableQueues, "durableQueues is null");
        patternMatchingMessageHandlerDelegate = new PatternMatchingMessageHandler(this, getMessageHandlerInterceptors());
        patternMatchingMessageHandlerDelegate.allowUnmatchedMessages();
        queuedMessageConsumer = handleQueuedMessageConsumer(patternMatchingMessageHandlerDelegate);
    }

    @Override
    public void start() {
        if (started) return;
        started = true;
        var processorName              = requireNonNull(getProcessorName(), "getProcessorName() returned null");
        var subscribeToEventsRelatedTo = requireNonNull(reactsToEventsRelatedToAggregateTypes(), "reactsToEventsRelatedToAggregateTypes() returned null");
        // A ViewEventProcessor deliberately handles each queued message inside a single UnitOfWork, so that the view
        // update and the message acknowledgement commit together. There is therefore no UnitOfWork-free window to
        // offer a UnitOfWorkMode.NONE handler - reject it instead of silently running the blocking call in a transaction.
        if (patternMatchingMessageHandlerDelegate.hasNonTransactionalMessageHandlers()) {
            throw new IllegalStateException(msg("ViewEventProcessor '{}' declares one or more @MessageHandler methods with UnitOfWorkMode.NONE, which a ViewEventProcessor doesn't support - " +
                                                "it handles every message inside a single UnitOfWork. Use an EventProcessor for handlers that need to perform blocking I/O",
                                                processorName));
        }
        logger.info("🎑⚙️  [{}] Starting ViewEventProcessor - will subscribe to events related to these AggregatesType's: {}",
                    processorName,
                    subscribeToEventsRelatedTo);
        this.durableQueueName = QueueName.of(processorName + ":queue");
        this.lockName = LockName.of(processorName + ":lock");
        fencedLockManager.acquireLockAsync(lockName,
                                           LockCallback.builder()
                                                       .onLockAcquired(lock -> {
                                                           logger.info("FencedLock '{}' for ViewProcessor '{}' with DurableQueue '{}' was ACQUIRED - will start Exclusive DurableQueueConsumer", lockName, processorName, durableQueueName);
                                                           startSubscribersAndDurableConsumer(processorName, subscribeToEventsRelatedTo, lock);
                                                           logger.info("Exclusive DurableQueueConsumer for Queue '{}': {}", durableQueueName, durableQueueConsumer);
                                                       })
                                                       .onLockReleased(lock -> {
                                                           if (durableQueueConsumer != null) {
                                                               logger.info("FencedLock '{}' for ViewProcessor '{}' was RELEASED - will stop {} subscriber(s)", lockName, processorName, eventStoreSubscriptions.size());
                                                               eventStoreSubscriptions.forEach(Lifecycle::stop);
                                                               logger.info("FencedLock '{}' for ViewProcessor '{}' and DurableQueue '{}' was RELEASED - will stop Exclusive DurableQueueConsumer: {}", lockName, processorName, durableQueueName, durableQueueConsumer);
                                                               durableQueueConsumer.cancel();
                                                               logger.info("Stopped Exclusive DurableQueueConsumer for Queue '{}': {}", durableQueueName, durableQueueConsumer);
                                                           } else {
                                                               logger.warn("FencedLock '{}' for ViewProcessor '{}' was RELEASED - didn't find an Exclusive DurableQueueConsumer for Queue '{}'!", lockName, processorName, durableQueueName);
                                                           }
                                                       })
                                                       .build());
    }

    private void startSubscribersAndDurableConsumer(String processorName, List<AggregateType> subscribeToEventsRelatedTo, FencedLock lock) {
        durableQueueConsumer = durableQueues.consumeFromQueue(durableQueueName,
                                                              getDurableQueueRedeliveryPolicy(),
                                                              getNumberOfParallelQueuedMessageConsumers(),
                                                              queuedMessage -> {
                                                                  queuedMessage.getMetaData().put(MessageMetaData.FENCED_LOCK_TOKEN,
                                                                                                  lock.getCurrentToken().toString());
                                                                  eventStore.getUnitOfWorkFactory().usingUnitOfWork(uow -> {
                                                                      handleQueuedMessage(queuedMessage);
                                                                  });
                                                              }
                                                             );
        eventStoreSubscriptions = subscribeToEventsRelatedTo.stream()
                                                            .map(aggregateType -> {
                                                                var subscriberId = AbstractEventProcessor.resolveSubscriberId(aggregateType, processorName);
                                                                var subscription = eventStoreSubscriptionManager.subscribeToAggregateEventsAsynchronously(
                                                                        subscriberId,
                                                                        aggregateType,
                                                                        resolveStartSubscriptionFromGlobalEventOrder(),
                                                                        new PersistedEventHandler() {
                                                                            @Override
                                                                            public void handle(PersistedEvent event) {
                                                                                handlePersistedEvent(event);
                                                                            }

                                                                            @Override
                                                                            public boolean handOffFailedEvent(PersistedEvent event, Throwable failure) {
                                                                                queueAfterRollback(event, failure);
                                                                                return true;
                                                                            }

                                                                            @Override
                                                                            public String toString() {
                                                                                return processorName;
                                                                            }
                                                                        });
                                                                logger.info("🎑⚙️ [{}] Created non-exclusive async '{}' subscription: {}",
                                                                            processorName,
                                                                            aggregateType,
                                                                            subscription);
                                                                return subscription;
                                                            })
                                                            .toList();

        logger.info("🎑⚙️  [{}] Started. There are #{} undelivered queue messages",
                    processorName,
                    durableQueues.getTotalMessagesQueuedFor(durableQueueName));
    }

    @Override
    public void stop() {
        if (!started) return;
        started = false;
        logger.info("🎑⚙️  [{}] Stopping ViewEventProcessor",
                    getProcessorName());
        fencedLockManager.cancelAsyncLockAcquiring(lockName);
        logger.info("🎑⚙️  [{}] ViewEventProcessor Stopped ", getProcessorName());
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * Retrieves the durable queues associated with this event processor.
     *
     * @return the {@link DurableQueues} instance associated with this event processor.
     */
    public DurableQueues getDurableQueues() {
        return durableQueues;
    }

    /**
     * Handle the event directly, and queue it if that fails.
     * <p>
     * Everything that can fail is inside the failure handling - deserializing the payload included, so an event that
     * cannot be deserialized is queued (and, since it cannot be deserialized there either, dead-lettered) instead of
     * failing the subscription, which would skip it.
     * <p>
     * The direct handler runs inside the subscription's {@link UnitOfWork}, the same transaction the fallback
     * {@link DurableQueues#queueMessage} writes in. A failed SQL statement aborts a Postgres transaction, so without
     * isolation the fallback would fail as well and the event would be skipped. The direct handler therefore runs
     * under a savepoint - see {@link #handleDirectlyUnderSavepoint(OrderedMessage)}.
     */
    private void handlePersistedEvent(PersistedEvent event) {
        var             aggregateType = event.aggregateType();
        var             serializer    = resolveAggregateIdSerializer(aggregateType);
        var             key           = serializer.serialize(event.aggregateId());
        var             order         = event.eventOrder().longValue();
        MessageMetaData meta          = null;

        try {
            meta = new MessageMetaData(event.metaData().deserialize());
            if (durableQueues.hasOrderedMessageQueuedForKey(durableQueueName, key)) {
                logger.debug("[{}:{}] The queue already has ordered message queued for key '{}'. Queueing message with event-order '{}'", aggregateType, key, key, order);
                durableQueues.queueMessage(durableQueueName,
                                           new EventReferenceOrderedMessage(aggregateType, key, event.eventOrder(), meta));
            } else {
                var payload = event.event().deserialize();
                handleDirectlyUnderSavepoint(OrderedMessage.of(payload, key, order, meta));
            }
        } catch (UnitOfWorkRequiresRollbackException e) {
            throw e;
        } catch (Exception e) {
            logger.debug("[{}:{}] Direct handling failed for event '{}', enqueuing for retry.",
                         aggregateType, key, event.event().getEventTypeOrNamePersistenceValue(), e);
            durableQueues.queueMessage(durableQueueName, new EventReferenceOrderedMessage(
                    aggregateType, key, event.eventOrder(), meta != null ? meta : new MessageMetaData()));
        }
    }

    /**
     * Queue an event whose failure could not be queued in the subscription's {@link UnitOfWork} - see
     * {@link #handleDirectlyUnderSavepoint(OrderedMessage)} - now that the subscriber has rolled that {@link UnitOfWork}
     * back. Queued in a {@link UnitOfWork} of its own, so the queued message commits on its own; the subscriber only moves
     * its resume point past the event once this has returned, and applies its {@code SubscriptionErrorPolicy} to the
     * event if this throws.
     * <p>
     * Runs on the subscription's delivery thread before the next event is handled, so a later event for the same
     * aggregate finds this one queued and is queued behind it.
     */
    private void queueAfterRollback(PersistedEvent event, Throwable failure) {
        var aggregateType = event.aggregateType();
        var key           = resolveAggregateIdSerializer(aggregateType).serialize(event.aggregateId());
        MessageMetaData meta;
        try {
            meta = new MessageMetaData(event.metaData().deserialize());
        } catch (RuntimeException e) {
            meta = new MessageMetaData();
        }
        var message = new EventReferenceOrderedMessage(aggregateType, key, event.eventOrder(), meta);
        eventStore.getUnitOfWorkFactory().usingUnitOfWork(() -> durableQueues.queueMessage(durableQueueName, message));
        logger.warn(msg("[{}:{}] Direct handling of the '{}' event with event-order '{}' failed and left state in the subscription's UnitOfWork that could not " +
                        "be committed - the UnitOfWork was rolled back and the event is queued for redelivery instead",
                        aggregateType, key, event.event().getEventTypeOrNamePersistenceValue(), event.eventOrder()), failure);
    }

    private static final String DIRECT_HANDLING_SAVEPOINT     = "essentials_view_event_processor_direct_handling";
    private static final String UNIT_OF_WORK_UNABLE_TO_COMMIT = "The direct handler's failure left the UnitOfWork unable to commit - the event cannot be queued in it";

    /**
     * Run the direct handler under a savepoint in the subscription's transaction, so a failing handler rolls back its
     * own writes - and only those - and leaves the transaction usable for queueing the event.
     * <p>
     * A savepoint undoes SQL. It cannot undo state the handler left in the {@link UnitOfWork} itself, and committing
     * the {@link UnitOfWork} to queue the event would act on that state although the handler failed:
     * <ul>
     *     <li>events the handler appended through the {@link EventStore} ({@link EventStoreUnitOfWork#getNumberOfEventsPersisted()})
     *     would be handed to the in-transaction subscriptions and published on the local event bus, while their rows
     *     were rolled back to the savepoint - and appended once more when the queued event is retried</li>
     *     <li>resources registered for commit-time processing that have pending changes
     *     ({@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()}), such as an aggregate the handler loaded
     *     and applied an event to, would have their callbacks persist the aggregate's uncommitted events</li>
     * </ul>
     * So when the failed handler left either kind of state behind, the event is not queued here: the failure is rethrown as
     * {@link UnitOfWorkRequiresRollbackException}, the whole {@link UnitOfWork} is rolled back, the subscription's
     * {@link SubscriptionErrorPolicy} retries it as it would any failure, and when the policy gives up the subscriber hands
     * the event back to {@link #queueAfterRollback(PersistedEvent, Throwable)}, which queues it in a {@link UnitOfWork} of
     * its own.
     * Every registered resource is asked, not only one registered by the handler, because a repository hands out the
     * instance already registered in the {@link UnitOfWork}, so the handler may have changed it without registering
     * anything. A resource without pending changes - an aggregate the handler only loaded - is left alone by the
     * commit, so it doesn't prevent queueing. A {@link UnitOfWork} that cannot report this state is treated as having it,
     * as is a resource whose {@link UnitOfWorkLifecycleCallback} doesn't implement
     * {@link UnitOfWorkLifecycleCallback#hasPendingChanges(Object)}.
     * The same applies if the handler joined the {@link UnitOfWork} through {@code usingUnitOfWork}/{@code withUnitOfWork}
     * and so marked it rollback-only: nothing written in it can commit any more.
     * Only a failure that left no such state behind is queued.
     *
     * @param msg the message to handle
     */
    private void handleDirectlyUnderSavepoint(OrderedMessage msg) {
        var currentUnitOfWork = eventStore.getUnitOfWorkFactory().getCurrentUnitOfWork();
        if (currentUnitOfWork.isEmpty() || !currentUnitOfWork.get().handle().isInTransaction()) {
            patternMatchingMessageHandlerDelegate.accept(msg);
            return;
        }
        var unitOfWork                    = currentUnitOfWork.get();
        var handle                        = unitOfWork.handle();
        var numberOfEventsPersistedBefore = numberOfEventsPersisted(unitOfWork);
        handle.savepoint(DIRECT_HANDLING_SAVEPOINT);
        try {
            patternMatchingMessageHandlerDelegate.accept(msg);
        } catch (RuntimeException handlerFailure) {
            try {
                // Jdbi forgets the savepoint on rollback, so there is nothing to release afterwards
                handle.rollbackToSavepoint(DIRECT_HANDLING_SAVEPOINT);
            } catch (RuntimeException rollbackFailure) {
                // The transaction is unusable (e.g. the connection is gone) - queueing in it would fail too
                rollbackFailure.addSuppressed(handlerFailure);
                throw new UnitOfWorkRequiresRollbackException(UNIT_OF_WORK_UNABLE_TO_COMMIT, rollbackFailure);
            }
            if (unitOfWork.status() == UnitOfWorkStatus.MarkedForRollbackOnly) {
                throw new UnitOfWorkRequiresRollbackException(UNIT_OF_WORK_UNABLE_TO_COMMIT, handlerFailure);
            }
            var eventsPersisted = numberOfEventsPersistedBefore.isEmpty() || !numberOfEventsPersistedBefore.equals(numberOfEventsPersisted(unitOfWork));
            if (eventsPersisted || hasLifecycleCallbackResourcesWithPendingChanges(unitOfWork)) {
                throw new UnitOfWorkRequiresRollbackException("The direct handler failed after persisting events or changing resources registered in the UnitOfWork, which a savepoint cannot undo - " +
                                                              "the event cannot be queued in it, as committing it would persist or publish them",
                                                              handlerFailure);
            }
            throw handlerFailure;
        }
        handle.release(DIRECT_HANDLING_SAVEPOINT);
    }

    /**
     * @return {@link EventStoreUnitOfWork#getNumberOfEventsPersisted()}, or empty if the {@link UnitOfWork} doesn't support it
     */
    private static OptionalLong numberOfEventsPersisted(EventStoreUnitOfWork unitOfWork) {
        try {
            return OptionalLong.of(unitOfWork.getNumberOfEventsPersisted());
        } catch (UnsupportedOperationException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * @return {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()} - {@code true} if the {@link UnitOfWork} doesn't support it
     */
    private static boolean hasLifecycleCallbackResourcesWithPendingChanges(UnitOfWork unitOfWork) {
        try {
            return unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges();
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    /**
     * The direct handler's failure left the {@link UnitOfWork} unable to commit, or in a state it must not be committed
     * in, so the event cannot be queued in it.
     * Not caught by {@link #handlePersistedEvent(PersistedEvent)}: it propagates to the subscription.
     */
    private static final class UnitOfWorkRequiresRollbackException extends RuntimeException {
        UnitOfWorkRequiresRollbackException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void handleQueuedMessage(QueuedMessage queuedMessage) {
        // Per-message, so TRACE; and guarded, for a reason beyond the usual cost one.
        //
        // Log arguments are evaluated eagerly, so an unguarded getId() runs on EVERY delivery no
        // matter the configured level. A DurableQueues implementation is entitled to not have a
        // QueueEntryId available on its push delivery path — the shard-owned engine's handler
        // receives (key, payload, payloadType), and its adapter throws rather than stub an id that
        // by-id operations would then address the wrong message with. Calling it here therefore
        // failed every message on that engine and dead-lettered it, from a log statement nobody
        // had enabled.
        if (logger.isTraceEnabled()) {
            if (queuedMessage instanceof EventReferenceOrderedMessage orderedMessage) {
                logger.trace("[{}] Handling queued message '{}' for Aggregate '{}' with key '{}' and event-order '{}'", durableQueueName, queuedMessage.getId(), orderedMessage.getPayload(), orderedMessage.key, orderedMessage.order);
            } else {
                logger.trace("[{}] Handling queued message '{}'", durableQueueName, queuedMessage.getId());
            }
        }
        var msg = queuedMessage.getMessage();
        queuedMessageConsumer.accept(msg);
    }

    /**
     * Reset all event store subscriptions and purge the durable queue.
     *
     * @see AbstractEventProcessor#resetAllSubscriptions(Consumer)
     */
    public void resetAllSubscriptions() {
        resetAllSubscriptions(durableQueues::purgeQueue);
    }

    /**
     * Resets the subscriptions for specified aggregate types starting from the given global event order
     * and optionally purges the durable queue associated with them.
     *
     * @param resetAggregateSubscriptionsFromAndIncluding a map where the key represents the {@link AggregateType} for which the subscription
     *                                                    will be reset, and the value specifies the {@link GlobalEventOrder} from where the
     *                                                    subscription should start processing events.
     * @param resetDurableQueue                           a flag indicating whether the durable queue associated with the subscriptions
     *                                                    should be purged during the reset.
     */
    public void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                   boolean resetDurableQueue) {
        resetSubscriptions(resetAggregateSubscriptionsFromAndIncluding,
                           resetDurableQueue,
                           durableQueues::purgeQueue);

    }

    /**
     * Resets the given event store subscription to start processing events from the specified global order
     * and optionally purges the associated durable queue.
     *
     * @param eventStoreSubscription      the event store subscription that will be reset
     * @param resubscribeFromAndIncluding the global order from which the subscription will start processing events
     * @param resetDurableQueue           flag indicating whether the associated durable queue should be purged
     */
    public void doResetSubscription(EventStoreSubscription eventStoreSubscription,
                                    GlobalEventOrder resubscribeFromAndIncluding,
                                    boolean resetDurableQueue) {
        doResetSubscription(eventStoreSubscription, resubscribeFromAndIncluding, resetDurableQueue, durableQueues::purgeQueue);
    }

    @Override
    public String toString() {
        return "🎑⚙️ " + this.getClass().getSimpleName() + " { " +
                "processorName='" + getProcessorName() + "'" +
                ", reactsToEventsRelatedToAggregateTypes=" + reactsToEventsRelatedToAggregateTypes() +
                ", started=" + started +
                " }";
    }

}
