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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.PersistedEventHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionErrorPolicy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.SubscriptionResumePoint;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.json.JSONDeserializationException;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.reactive.Handler;
import dk.trustworks.essentials.reactive.command.*;
import dk.trustworks.essentials.shared.Exceptions;
import dk.trustworks.essentials.shared.Lifecycle;
import dk.trustworks.essentials.shared.functional.*;
import dk.trustworks.essentials.types.LongRange;
import org.slf4j.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

/**
 * The AbstractEventProcessor provides base functionality for event processors that react to events
 * associated with one or more aggregate event streams stored in the {@link EventStore}.
 * It manages event subscriptions and message handling mechanisms such a retries (see {@link #getDurableQueueRedeliveryPolicy()}) and pattern matching (through {@link PatternMatchingQueuedMessageHandler}).
 * <br>
 * It includes integration with {@link DurableQueues} and supports handling of {@link MessageHandlerInterceptor},
 * {@link DurableLocalCommandBus} handling, and resettable event store subscriptions.
 * <p>
 * <h3>Event Queuing</h3>
 * When events from the {@link EventStore} need to be queued for processing, they are converted to {@link OrderedMessage}s where:
 * <ul>
 *  <li>The event's {@link PersistedEvent#aggregateId()} becomes the {@link OrderedMessage#getKey()}</li>
 *  <li>The event's {@link PersistedEvent#eventOrder()} becomes the {@link OrderedMessage#getOrder()}</li>
 * </ul>
 * <br>
 * Subclasses can customize behavior by overriding specific methods or providing their own
 * configurations such as message handler interceptors, subscription policies, and processing logic.
 * This class requires concrete subclasses to define the set of {@link AggregateType}s they are reacting to
 * as well as the processor's name.
 */
public abstract class AbstractEventProcessor implements Lifecycle {
    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final EventStoreSubscriptionManager eventStoreSubscriptionManager;
    protected final DurableLocalCommandBus        commandBus;
    protected final EventStore                    eventStore;

    protected volatile boolean                         started;
    protected          List<EventStoreSubscription>    eventStoreSubscriptions;
    protected          AnnotatedCommandHandler         commandBusHandlerDelegate;
    protected          List<MessageHandlerInterceptor> messageHandlerInterceptors;

    protected QueueName durableQueueName;

    /**
     * Whether the processor has logged that the event store is no {@link AwaitedGapInspector} - logged once per processor,
     * when it starts (see {@link #warnOnceIfEventStoreIsNoAwaitedGapInspector()})
     */
    private final AtomicBoolean warnedEventStoreIsNoAwaitedGapInspector = new AtomicBoolean();

    /**
     * Resolves a unique {@link SubscriberId} by combining the processor name and aggregate type.
     *
     * @param aggregateType the type of aggregate associated with the subscriber
     * @param processorName the name of the processor for which the {@link SubscriberId} is being resolved
     * @return a unique {@link SubscriberId} for the given processor name and aggregate type
     */
    public static SubscriberId resolveSubscriberId(AggregateType aggregateType, String processorName) {
        requireNonNull(aggregateType, "aggregateType is null");
        requireNonNull(processorName, "processorName is null");
        return SubscriberId.of(processorName + ":" + aggregateType);
    }


    /**
     * Create a new {@link AbstractEventProcessor} instance
     *
     * @param eventStoreSubscriptionManager The {@link EventStoreSubscriptionManager} used for managing {@link EventStore} subscriptions<br>
     *                                      The  {@link EventStore} instance associated with the {@link EventStoreSubscriptionManager} is used to only query references to
     *                                      the {@link PersistedEvent}. Before an event reference message is forwarded to the corresponding {@link MessageHandler} we load the {@link PersistedEvent}'s
     *                                      payload and forward it to the {@link MessageHandler} annotated method. This avoids double storing event payloads
     * @param commandBus                    The {@link CommandBus} where any {@link Handler} or {@link CmdHandler} annotated methods in the subclass of the {@link AbstractEventProcessor} will be registered
     * @param messageHandlerInterceptors    The {@link MessageHandlerInterceptor}'s that will intercept calls to the {@link MessageHandler} annotated methods.<br>
     *                                      Unless you override {@link #getMessageHandlerInterceptors()} then these are the {@link MessageHandlerInterceptor}'s that will be used.
     */
    protected AbstractEventProcessor(EventStoreSubscriptionManager eventStoreSubscriptionManager,
                                     DurableLocalCommandBus commandBus,
                                     List<MessageHandlerInterceptor> messageHandlerInterceptors) {
        this.eventStoreSubscriptionManager = requireNonNull(eventStoreSubscriptionManager, "No eventStoreSubscriptionManager provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.eventStore = requireNonNull(eventStoreSubscriptionManager.getEventStore(), "No eventStore is associated with the eventStoreSubscriptionManager provided");
        this.messageHandlerInterceptors = new CopyOnWriteArrayList<>(requireNonNull(messageHandlerInterceptors, "No messageHandlerInterceptors list provided"));
        setupCommandHandler();
    }

    /**
     * Setup the EventProcessor's {@link CommandHandler} capabilities - default delegates any matching commands on to
     * {@link Handler} or {@link CmdHandler} annotated methods in the subclass of the {@link AbstractEventProcessor}
     */
    protected void setupCommandHandler() {
        commandBusHandlerDelegate = new AnnotatedCommandHandler(this);
        commandBus.addCommandHandler(commandBusHandlerDelegate);
    }

    /**
     * Default: The {@link MessageHandlerInterceptor}'s provided in the {@link AbstractEventProcessor#AbstractEventProcessor(EventStoreSubscriptionManager, DurableLocalCommandBus, List)} constructor<br>
     * You can also override this method to provide your own {@link MessageHandlerInterceptor}'s that should be used when calling {@link MessageHandler} annotated methods<br>
     * This method will be called during {@link AbstractEventProcessor#start()} time
     *
     * @return the {@link MessageHandlerInterceptor}'s that should be used when calling {@link MessageHandler} annotated methods
     */
    protected List<MessageHandlerInterceptor> getMessageHandlerInterceptors() {
        return messageHandlerInterceptors;
    }

    /**
     * This method returns true if one or more underlying {@link EventStoreSubscription} are active<br>
     * Otherwise it returns false
     *
     * @return see description above
     */
    public boolean isActive() {
        return eventStoreSubscriptions.stream()
                                      .anyMatch(EventStoreSubscription::isActive);
    }

    /**
     * Unsubscribes each of a processor's subscriptions when the processor stops - used by {@link EventProcessor#stop()} and
     * {@link InTransactionEventProcessor#stop()}.<br>
     * Unsubscribed, not just stopped: the {@link EventStoreSubscriptionManager} keeps a subscription that was only stopped
     * and hands it, unstarted, to the next subscribe with the same {@link SubscriberId} and {@link AggregateType}, so a
     * processor started again would handle no events. Each subscription is unsubscribed on its own, so one that fails does
     * not leave the ones after it subscribed - the shared rule, {@link Exceptions#forEachThenThrowFirst(Iterable, Consumer)}.
     *
     * @param subscriptions the processor's subscriptions - nothing is done when {@code null} (no subscription was created)
     * @throws RuntimeException the first failure, when it is a {@link RuntimeException}, with each later, distinct one
     *                          added as suppressed, once every subscription was tried
     * @throws Error            the first failure, when it is a non-critical {@link Error} (an {@link AssertionError}, say),
     *                          with each later, distinct one added as suppressed, once every subscription was tried; or a
     *                          critical error ({@link Exceptions#isCriticalError(Throwable)}), rethrown at once without
     *                          trying the subscriptions after it
     */
    static void unsubscribeEach(List<EventStoreSubscription> subscriptions) {
        if (subscriptions == null) {
            return;
        }
        Exceptions.forEachThenThrowFirst(subscriptions, EventStoreSubscription::unsubscribe);
    }

    /**
     * Unsubscribes each of the subscriptions a start created before it failed, adding every failure to that start's
     * {@code failure} as suppressed and going on with the next one, so a failing unsubscribe neither replaces the start's
     * failure nor leaves the ones after it subscribed - the shared rule,
     * {@link Exceptions#forEachCollectingFailures(Iterable, Consumer, Throwable)}. Throws nothing itself but a critical
     * error, which it rethrows at once with {@code failure} added to it - see {@link #unsubscribeEach(List)} for why a
     * subscription is unsubscribed and not just stopped.
     *
     * @param subscriptions the subscriptions - nothing is done when {@code null} (no subscription was created)
     * @param failure       the failure of the start, which the caller throws afterwards
     */
    static void unsubscribeEachAddingFailuresTo(List<EventStoreSubscription> subscriptions, Throwable failure) {
        requireNonNull(failure, "failure is null");
        if (subscriptions == null) {
            return;
        }
        Exceptions.forEachCollectingFailures(subscriptions, EventStoreSubscription::unsubscribe, failure);
    }

    /**
     * Subscribes to each of a processor's aggregate types in turn, when the processor starts (or, for a
     * {@link ViewEventProcessor}, acquires its lock) - used by {@link EventProcessor#start()},
     * {@link InTransactionEventProcessor#start()} and {@link ViewEventProcessor}. When one fails to subscribe, the
     * subscriptions created before it are unsubscribed again through
     * {@link #unsubscribeEachAddingFailuresTo(List, Throwable)} and the subscribe failure is rethrown: a failed start must
     * not leave subscriptions running that nothing refers to. The subscriptions are returned, not assigned, so the
     * processor's {@code eventStoreSubscriptions} keep the (unsubscribed) ones of an earlier run until every subscribe
     * succeeded. Undoing anything else the start did (its {@code started} flag, an {@code Inbox} consumption) is left to the
     * caller - {@link #startOrRollBack(Runnable, Runnable, Runnable)}.
     * <p>
     * A {@link RuntimeException}, a non-critical {@link Error} and a checked exception the subscribe throws without
     * declaring it (sneakily - a Kotlin override or interceptor can) are rolled back alike, as
     * {@link Exceptions#forEachCollectingFailures(Iterable, Consumer, Throwable)} treats them; a critical error
     * ({@link Exceptions#isCriticalError(Throwable)}) is rethrown at once, without the rollback.
     *
     * @param aggregateTypes the aggregate types to subscribe to, in their order
     * @param subscribe      subscribes to one aggregate type and returns the subscription
     * @param <T>            the type of the aggregate types
     * @return the subscriptions, one per aggregate type in its order, as an unmodifiable list
     * @throws RuntimeException the subscribe failure, when it is a {@link RuntimeException} (or a checked exception thrown
     *                          sneakily, rethrown as it is), with each failure to unsubscribe one of the subscriptions
     *                          created before it added as suppressed
     * @throws Error            the subscribe failure, when it is a non-critical {@link Error}, with each failure to
     *                          unsubscribe added as suppressed; or a critical error, rethrown at once - by the subscribe,
     *                          without the rollback, or by an unsubscribe during the rollback, with the subscribe failure
     *                          added to it as suppressed
     */
    static <T> List<EventStoreSubscription> subscribeEachOrUnsubscribe(List<T> aggregateTypes, Function<? super T, EventStoreSubscription> subscribe) {
        requireNonNull(aggregateTypes, "aggregateTypes is null");
        requireNonNull(subscribe, "subscribe is null");
        var created = new ArrayList<EventStoreSubscription>(aggregateTypes.size());
        try {
            for (var aggregateType : aggregateTypes) {
                created.add(subscribe.apply(aggregateType));
            }
        } catch (Throwable e) {
            // Throwable, not RuntimeException | Error: a checked exception thrown sneakily would skip the rollback.
            // Rethrown as it is - the try declares no checked exception, so 'throw e' needs no sneaky throw
            Exceptions.rethrowIfCriticalError(e);
            // Each one on its own, so a failing unsubscribe neither replaces the subscribe failure nor leaves the ones after it subscribed
            unsubscribeEachAddingFailuresTo(created, e);
            throw e;
        }
        return Collections.unmodifiableList(created);
    }

    /**
     * Runs the steps of a processor's {@code start()} after the caller has marked it started, and rolls the start back
     * when a step fails - used by {@link EventProcessor#start()}, {@link InTransactionEventProcessor#start()} and
     * {@link ViewEventProcessor#start()}, so the shape of a failed start is written once. On a failure, {@code undo} runs - it stops
     * what the steps left running beyond the subscriptions (an {@link EventProcessor}'s {@code Inbox} consumption), since a
     * {@code stop()} of a processor that is not started does nothing - with a failure of its own added to the step's
     * failure as suppressed, then {@code markNotStarted} runs, so
     * {@code start()} may be called again, and the step's failure is rethrown as it was thrown. The subscriptions the steps
     * created are unsubscribed by {@link #subscribeEachOrUnsubscribe(List, Function)} itself.
     * <p>
     * A {@link RuntimeException}, a non-critical {@link Error} and a checked exception a step throws without declaring it
     * (sneakily - a Kotlin override of a processor hook such as {@code getInboxRedeliveryPolicy()} can) are rolled back
     * alike. A critical error ({@link Exceptions#isCriticalError(Throwable)}) is rethrown at once, without the rollback;
     * one thrown by {@code undo} is rethrown with the step's failure added to it as suppressed, and {@code markNotStarted}
     * does not run.
     * <p>
     * Which checks run before the caller marks the processor started is the caller's: a check that rejects the processor
     * runs before it, so a rejected processor still reports {@code isStarted() == false}.
     *
     * @param steps          the steps of the start, after the processor was marked started
     * @param undo           undoes what the steps did beyond the subscriptions, when a step fails
     * @param markNotStarted marks the processor not started again, when a step fails
     * @throws RuntimeException a step's failure, when it is a {@link RuntimeException} (or a checked exception thrown
     *                          sneakily, rethrown as it is), with {@code undo}'s failure added as suppressed
     * @throws Error            a step's failure, when it is a non-critical {@link Error}, with {@code undo}'s failure added
     *                          as suppressed; or a critical error, rethrown at once
     */
    static void startOrRollBack(Runnable steps, Runnable undo, Runnable markNotStarted) {
        requireNonNull(steps, "steps is null");
        requireNonNull(undo, "undo is null");
        requireNonNull(markNotStarted, "markNotStarted is null");
        try {
            steps.run();
        } catch (Throwable e) {
            // Throwable, not RuntimeException | Error: a checked exception thrown sneakily would skip the rollback and leave
            // the processor started. Rethrown as it is - the try declares no checked exception, so 'throw e' needs no sneaky throw
            Exceptions.rethrowIfCriticalError(e);
            Exceptions.forEachCollectingFailures(List.of(undo), Runnable::run, e);
            markNotStarted.run();
            throw e;
        }
    }

    /**
     * Creates and returns a {@link Consumer} to handle queued messages. The consumer processes the
     * given message by distinguishing between ordered messages containing event references and
     * other generic messages. For event references, it fetches the associated event from the
     * event store and delegates the handling of the deserialized event to the specified
     * {@link PatternMatchingMessageHandler}. If the message does not contain an event reference,
     * it is directly delegated to the handler.
     *
     * @param patternMatchingMessageHandlerDelegate the delegate to handle the processed message,
     *                                              after optional deserialization.
     *                                              This handler is responsible for applying custom
     *                                              processing logic to the incoming message.
     * @return a configured {@link Consumer} of {@link Message}, which processes queued messages
     * appropriately and invokes the specified delegate for handling events or direct messages.
     */
    protected Consumer<Message> handleQueuedMessageConsumer(PatternMatchingMessageHandler patternMatchingMessageHandlerDelegate) {
        return new EventReferenceResolvingMessageConsumer(patternMatchingMessageHandlerDelegate);
    }

    /**
     * Runs the given action inside a {@link UnitOfWork}, joining an already active {@link UnitOfWork} if there is one.
     * <p>
     * Intended for {@link MessageHandler} annotated methods declared with {@link UnitOfWorkMode#NONE}, which run
     * without an ambient {@link UnitOfWork} so that they can perform blocking I/O: use this to wrap the transactional
     * tail that follows the blocking call.
     * <pre>{@code
     * @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
     * void on(InstrumentRegistrationRequested e) {
     *     var decision = riskService.check(e.instrumentId(), e.symbol());   // blocking, no UnitOfWork held
     *     usingUnitOfWork(() -> getCommandBus().sendAndDontWait(new RecordRiskDecision(e.instrumentId(), decision)));
     * }
     * }</pre>
     *
     * @param action the action to perform inside a {@link UnitOfWork}
     */
    protected void usingUnitOfWork(CheckedRunnable action) {
        requireNonNull(action, "No action provided");
        eventStore.getUnitOfWorkFactory().usingUnitOfWork(action);
    }

    /**
     * Runs the given action inside a {@link UnitOfWork} and returns its result, joining an already active
     * {@link UnitOfWork} if there is one.
     *
     * @param action the action to perform inside a {@link UnitOfWork}
     * @param <R>    the result type
     * @return the result of the action
     * @see #usingUnitOfWork(CheckedRunnable)
     */
    protected <R> R withUnitOfWork(CheckedSupplier<R> action) {
        requireNonNull(action, "No action provided");
        return eventStore.getUnitOfWorkFactory().withUnitOfWork(action);
    }

    /**
     * The {@link UnitOfWork} active on the calling thread, if any.
     * <p>
     * Present inside a {@link MessageHandler} running with the default {@link UnitOfWorkMode#REQUIRED} - which is every
     * handler of a {@link ViewEventProcessor} - and inside {@link #usingUnitOfWork(CheckedRunnable)}/
     * {@link #withUnitOfWork(CheckedSupplier)}; empty in the body of a {@link UnitOfWorkMode#NONE} handler outside
     * those wrappers.
     *
     * @return the active {@link EventStoreUnitOfWork}, or {@link Optional#empty()} when none is active
     * @see #getRequiredUnitOfWork()
     */
    protected Optional<EventStoreUnitOfWork> getCurrentUnitOfWork() {
        return eventStore.getUnitOfWorkFactory().getCurrentUnitOfWork();
    }

    /**
     * The {@link UnitOfWork} active on the calling thread. Never opens one: where none may be active (a constructor, a
     * {@link UnitOfWorkMode#NONE} handler), wrap the call in {@link #usingUnitOfWork(CheckedRunnable)} or
     * {@link #withUnitOfWork(CheckedSupplier)}.
     * <p>
     * Inside a {@link MessageHandler} running with the default {@link UnitOfWorkMode#REQUIRED} this is the
     * {@link UnitOfWork} the message is handled in, so a view write through its JDBI {@link EventStoreUnitOfWork#handle()}
     * commits together with the processor's progress - without the processor being handed a {@link UnitOfWorkFactory}
     * of its own:
     * <pre>{@code
     * @MessageHandler
     * void on(PriceUpdated e) {
     *     getRequiredUnitOfWork().handle()
     *                            .createUpdate("UPDATE projection_latest_price SET latest_price = :price WHERE instrument_id = :id")
     *                            .bind("price", e.price().value())
     *                            .bind("id", e.instrumentId().toString())
     *                            .execute();
     * }
     * }</pre>
     *
     * @return the active {@link EventStoreUnitOfWork}
     * @throws NoActiveUnitOfWorkException if no {@link UnitOfWork} is active on the calling thread
     * @see #getCurrentUnitOfWork()
     */
    protected EventStoreUnitOfWork getRequiredUnitOfWork() {
        return getCurrentUnitOfWork().orElseThrow(() -> new NoActiveUnitOfWorkException(msg(
                "No active UnitOfWork in processor '{}' - a @MessageHandler(unitOfWork = UnitOfWorkMode.NONE) method, a constructor or onSubscriptionsReset " +
                        "has none: wrap the call in usingUnitOfWork(...)/withUnitOfWork(...)",
                getProcessorName())));
    }

    /**
     * The {@link UnitOfWorkFactory} of the {@link EventStore} this processor subscribes to - the same factory the
     * processor handles its messages with, so a subclass never needs one injected. Prefer
     * {@link #usingUnitOfWork(CheckedRunnable)}, {@link #withUnitOfWork(CheckedSupplier)} and
     * {@link #getRequiredUnitOfWork()}; reach for the factory when an action wants the {@link UnitOfWork} handed to it
     * ({@code getUnitOfWorkFactory().usingUnitOfWork(uow -> ...)}) or to pass it to a collaborator.
     * <p>
     * The processor deliberately has no {@code usingUnitOfWork(uow -> ...)} overload of its own: next to the
     * no-argument one, Kotlin cannot resolve a parameterless lambda ({@code usingUnitOfWork { ... }}) between the two.
     *
     * @return the {@link EventStoreUnitOfWorkFactory} of this processor's {@link EventStore}
     */
    protected EventStoreUnitOfWorkFactory<EventStoreUnitOfWork> getUnitOfWorkFactory() {
        return eventStore.getUnitOfWorkFactory();
    }

    /**
     * {@link Message} consumer that resolves an event-reference {@link OrderedMessage} into the actual
     * {@link PersistedEvent} payload before delegating to the {@link PatternMatchingMessageHandler}.
     * <p>
     * It owns its {@link UnitOfWork} boundary (see {@link UnitOfWorkBoundaryOwningMessageConsumer}), which splits
     * message handling into distinct phases:
     * <ol>
     *   <li>Loading the event from the {@link EventStore} happens in its own short {@link UnitOfWork} - it is a
     *       database read and cannot be done without one</li>
     *   <li>Invoking the {@link MessageHandler} annotated method is left to the
     *       {@link PatternMatchingMessageHandler}, which opens a {@link UnitOfWork} per method according to that
     *       method's {@link MessageHandler#unitOfWork()} - so a {@link UnitOfWorkMode#NONE} handler runs with no
     *       database connection held and can perform blocking I/O</li>
     * </ol>
     * Phase 2 runs with the resolved event bound as the cause in {@link CausationContext}, so every event the handler
     * method appends - including the ones written lazily when its {@link UnitOfWork} commits - records it as its cause.
     */
    protected class EventReferenceResolvingMessageConsumer implements UnitOfWorkBoundaryOwningMessageConsumer {
        private final PatternMatchingMessageHandler patternMatchingMessageHandlerDelegate;

        protected EventReferenceResolvingMessageConsumer(PatternMatchingMessageHandler patternMatchingMessageHandlerDelegate) {
            this.patternMatchingMessageHandlerDelegate = requireNonNull(patternMatchingMessageHandlerDelegate, "No patternMatchingMessageHandlerDelegate provided");
        }

        @Override
        public void accept(Message msg) {
            if (msg instanceof OrderedMessage orderedMessage && AbstractEventProcessor.EventReferenceOrderedMessage.isEventReference(orderedMessage)) {
                // Phase 1: resolve the event reference into the actual event - a database read, so it needs its own UnitOfWork
                var resolved = withUnitOfWork(() -> resolveEventReference(orderedMessage));
                // Phase 2: hand it to the handler methods, which decide their own UnitOfWork scope. The binding
                // encloses those UnitOfWorks, so it also covers events appended lazily when they commit
                CausationContext.where(resolved.persistedEvent().eventId())
                                .run(() -> patternMatchingMessageHandlerDelegate.accept(resolved.message()));
            } else {
                patternMatchingMessageHandlerDelegate.accept(msg);
            }
        }

        @Override
        public boolean hasNonTransactionalMessageHandlers() {
            return patternMatchingMessageHandlerDelegate.hasNonTransactionalMessageHandlers();
        }

        /**
         * Load the {@link PersistedEvent} that the given event-reference {@link OrderedMessage} points to and return it
         * together with an {@link OrderedMessage} carrying the deserialized event as its payload.<br>
         * <b>Must be called with an active {@link UnitOfWork}</b>
         *
         * @param orderedMessage the event-reference message
         * @return the loaded {@link PersistedEvent} and the resolved {@link OrderedMessage} carrying the deserialized event
         */
        private ResolvedEventReference resolveEventReference(OrderedMessage orderedMessage) {
            var aggregateType         = (AggregateType) orderedMessage.getPayload();
            var aggregateIdSerializer = resolveAggregateIdSerializer(aggregateType);

            var stringAggregateId = orderedMessage.getKey();
            var aggregateId       = aggregateIdSerializer.deserialize(stringAggregateId);

            var eventOrder = orderedMessage.order;
            log.trace("Looking up event for aggregate '{}' with id '{}' and event-order {}",
                      aggregateType,
                      aggregateId,
                      eventOrder);
            var events = eventStore.fetchStream(aggregateType,
                                                aggregateId,
                                                LongRange.only(eventOrder))
                                   .orElseThrow(() -> new IllegalArgumentException(msg("Couldn't find a matching event for aggregate '{}' with id '{}' and event-order {}",
                                                                                       aggregateType,
                                                                                       aggregateId,
                                                                                       eventOrder)))
                                   .eventList();
            if (events.size() != 1) {
                throw new IllegalArgumentException(msg("Couldn't find a matching event for aggregate '{}' with id '{}' and event-order {}",
                                                       aggregateType,
                                                       aggregateId,
                                                       eventOrder));
            }
            var persistedEvent = events.get(0);
            log.debug("[{}:{}] Handling Event of type '{}'", aggregateType, aggregateId, persistedEvent.event().getEventTypeOrNamePersistenceValue());
            try {
                return new ResolvedEventReference(persistedEvent,
                                                  OrderedMessage.of(persistedEvent.event().deserialize(),
                                                                    stringAggregateId,
                                                                    eventOrder,
                                                                    orderedMessage.getMetaData()));
            } catch (JSONDeserializationException e) {
                log.error("Failed to deserialize PersistedEvent '{}'", persistedEvent.event().getEventTypeOrNamePersistenceValue(), e);
                throw e;
            }
        }

        /**
         * The outcome of {@link #resolveEventReference(OrderedMessage)}: the loaded event, kept so it can be bound as the
         * cause, and the message handed to the handler methods
         */
        private record ResolvedEventReference(PersistedEvent persistedEvent, OrderedMessage message) {
        }
    }

    /**
     * Resets all event store subscriptions to their initial state.
     * This method sets the global event order for each subscription to the first global event order, purges the durable queue
     * and invokes the provided callback function to reset durable queues.
     *
     * @param resetDurableQueueCallback a callback function executed if the durable queue is going to be purged - the actual queue reset logic must be implemented in this callback
     */
    protected void resetAllSubscriptions(Consumer<QueueName> resetDurableQueueCallback) {
        var resetParameters = eventStoreSubscriptions.stream()
                                                     .map(EventStoreSubscription::aggregateType)
                                                     .collect(toMap(identity(), aggregateType -> GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER));
        resetSubscriptions(resetParameters, true, resetDurableQueueCallback);
    }

    /**
     * Reset the specific {@link AggregateType} fromAndIncluding the specified {@link GlobalEventOrder}<br>
     * {@link #onSubscriptionsReset(AggregateType, GlobalEventOrder)} will be called for each {@link AggregateType}<br>
     * <br>
     * Note: This method does NOT purge the durable queue
     *
     * @param resetAggregateSubscriptionsFromAndIncluding a map of {@link AggregateType} and reset-fromAndIncluding {@link GlobalEventOrder}
     */
    protected void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding) {
        resetSubscriptions(resetAggregateSubscriptionsFromAndIncluding, false, queueName -> {
        });
    }

    /**
     * Resets the subscriptions for specified {@link AggregateType}s from a given {@link GlobalEventOrder}
     * and optionally resets the durable queue once.<br>
     * {@link #onSubscriptionsReset(AggregateType, GlobalEventOrder)} will be called for each {@link AggregateType}<br>
     *
     * @param resetAggregateSubscriptionsFromAndIncluding a map of {@link AggregateType} and the {@link GlobalEventOrder}
     *                                                    from and including which the subscription should be reset
     * @param resetDurableQueue                           a flag indicating whether the durable queue should be purged
     * @param resetDurableQueueCallback                   a callback function executed if the durable queue is going to be purged - the actual queue reset logic must be implemented in this callback
     */
    protected void resetSubscriptions(Map<AggregateType, GlobalEventOrder> resetAggregateSubscriptionsFromAndIncluding,
                                      boolean resetDurableQueue,
                                      Consumer<QueueName> resetDurableQueueCallback) {
        requireNonNull(resetAggregateSubscriptionsFromAndIncluding, "resetAggregateSubscriptionsFromAndIncluding is null");
        requireNonNull(resetDurableQueueCallback, "resetDurableQueueCallback is null");
        log.info("[{}] Resetting EventProcessor subscriptions with resetDurableQueue '{}'", getProcessorName(), resetDurableQueue);
        var isResetDurableQueue = new AtomicBoolean(resetDurableQueue);
        resetAggregateSubscriptionsFromAndIncluding.forEach((aggregateType, resubscribeFromAndIncluding) -> {
            findSubscription(aggregateType).ifPresentOrElse(eventStoreSubscription -> {
                                                                doResetSubscription(eventStoreSubscription, resubscribeFromAndIncluding, isResetDurableQueue.get(), resetDurableQueueCallback);
                                                                isResetDurableQueue.set(false);
                                                            },
                                                            () -> {
                                                                throw new IllegalArgumentException(msg("[{}] Cannot reset subscription for {} '{}' since the {} doesn't subscribe to events for this aggregate type",
                                                                                                       getProcessorName(),
                                                                                                       AggregateType.class.getSimpleName(),
                                                                                                       aggregateType,
                                                                                                       EventProcessor.class.getSimpleName()));
                                                            });
        });
    }

    /**
     * Reset subscription from and including global order and reset durable queue with callback (called by {@link #resetSubscriptions(Map, boolean, Consumer)} for each {@link AggregateType} being reset)
     *
     * @param eventStoreSubscription      event store subscription
     * @param resubscribeFromAndIncluding reset from and including global order
     * @param resetDurableQueue           should durable queue be purged
     * @param resetDurableQueueCallback   a callback function executed if the durable queue is going to be purged - the actual queue reset logic must be implemented in this callback
     */
    protected void doResetSubscription(EventStoreSubscription eventStoreSubscription,
                                       GlobalEventOrder resubscribeFromAndIncluding,
                                       boolean resetDurableQueue,
                                       Consumer<QueueName> resetDurableQueueCallback) {
        requireNonNull(eventStoreSubscription, "eventStoreSubscription is null");
        requireNonNull(resubscribeFromAndIncluding, "resubscribeFromAndIncluding is null");
        requireNonNull(resetDurableQueueCallback, "resetDurableQueueCallback is null");

        log.info("[{}] Resetting subscription for '{}' restartingFromAndIncluding globalEventOrder '{}'",
                 getProcessorName(),
                 eventStoreSubscription.subscriberId(),
                 resubscribeFromAndIncluding);

        eventStoreSubscription.resetFrom(resubscribeFromAndIncluding, r -> {
            if (resetDurableQueue) {
                log.info("[{}] Deleting all messages for durable queue'{}'", getProcessorName(), durableQueueName);
                resetDurableQueueCallback.accept(durableQueueName);
            }
            onSubscriptionsReset(eventStoreSubscription.aggregateType(), r);
        });
    }

    /**
     * Finds the {@link EventStoreSubscription} for the specified {@link AggregateType}.
     * This method searches through the collection of event store subscriptions
     * to find one that matches the given aggregate type.
     *
     * @param aggregateType the {@link AggregateType} for which the subscription is being searched
     * @return an {@link Optional} containing the found {@link EventStoreSubscription}
     * if one exists for the specified {@link AggregateType}, or an empty {@link Optional} if not
     */
    protected Optional<EventStoreSubscription> findSubscription(AggregateType aggregateType) {
        return eventStoreSubscriptions.stream()
                                      .filter(eventStoreSubscription -> eventStoreSubscription.aggregateType().equals(aggregateType))
                                      .findFirst();
    }

    /**
     * Will be called when {@link #resetSubscriptions(Map, boolean, Consumer)}, {@link #resetAllSubscriptions(Consumer)} or {@link #resetSubscriptions(Map)} - override this method
     * perform any reset related side effects that may be required (e.g. resetting a view)
     *
     * @param aggregateType               the {@link AggregateType} for which the subscription is being reset
     * @param resubscribeFromAndIncluding the {@link GlobalEventOrder} that the subscription will restart from and including
     */
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
    }

    /**
     * Resolves the {@link AggregateIdSerializer} for the specified {@link AggregateType}.
     * Retrieves the {@link AggregateIdSerializer} associated with the given {@link AggregateType}
     * from the {@link EventStore}'s aggregate event stream configuration.
     *
     * @param aggregateType The {@link AggregateType} for which the {@link AggregateIdSerializer} is being resolved.
     * @return The {@link AggregateIdSerializer} associated with the specified {@link AggregateType}.
     */
    protected AggregateIdSerializer resolveAggregateIdSerializer(AggregateType aggregateType) {
        return ConfigurableEventStoreResolver.resolve(eventStore).getAggregateEventStreamConfiguration(aggregateType).aggregateIdSerializer;
    }

    /**
     * Get the name of the underlying durable queue
     *
     * @return the {@link QueueName of the underlying durable queue}
     */
    public QueueName getDurableQueueName() {
        return durableQueueName;
    }

    /**
     * Get the name of the event processor.<br>
     * This name is used as value for the underlying {@link EventStoreSubscription#subscriberId()}'s
     * and for the durable queue's name (queue naming is implementation dependent)
     *
     * @return the name of the event processor
     */
    public abstract String getProcessorName();

    /**
     * Get the {@link AggregateType}'s who's events this {@link EventProcessor} is reacting to
     *
     * @return the {@link AggregateType}'s who's events this {@link EventProcessor} is reacting to
     */
    protected abstract List<AggregateType> reactsToEventsRelatedToAggregateTypes();

    /**
     * Get the number of message consumption threads that will consume messages from the underlying Durable queue<br>
     * Default is: 1
     *
     * @return the number of message consumption threads that will consume messages from the underlying Durable queue
     */
    protected int getNumberOfParallelQueuedMessageConsumers() {
        return 1;
    }

    /**
     * Get the {@link RedeliveryPolicy} used when consuming messages from the underlying Durable queue.
     * <p>
     * Default: {@link #failoverRedeliveryPolicy(Optional)} - quick first retries, and retrying for long enough to outlast a
     * failover, so a handler that waits for another processor's work does not dead-letter while that work moves to
     * another instance. Override for a different budget.
     *
     * @return the {@link RedeliveryPolicy} used when consuming messages from the underlying Durable queue
     */
    protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
        return failoverRedeliveryPolicy(Optional.empty()).policy();
    }

    /**
     * A {@link FailoverRedeliveryPolicy} for this processor: from the lock timeout of its subscription manager's
     * {@link FencedLockManager} and the given queue window ({@code DurableQueues#maxUnservedAfterInstanceLoss()}),
     * each falling back to its default when unknown.
     *
     * @param queueWindow how long the processor's queue can leave a dead instance's messages unserved
     * @return the policy and what it was derived from
     */
    protected final FailoverRedeliveryPolicy failoverRedeliveryPolicy(Optional<Duration> queueWindow) {
        return FailoverRedeliveryPolicy.outlasting(eventStoreSubscriptionManager.getFencedLockManager().flatMap(FencedLockManager::getLockTimeOut),
                                                   queueWindow);
    }

    /**
     * Says at start which retry budget a queue consumer runs with: the derived default's, or that the processor chose its
     * own. One line, so an operator reading a dead letter can tell how long it was retried.
     */
    protected final void logRedeliveryPolicy(String processorName, RedeliveryPolicy inUse, FailoverRedeliveryPolicy derivedDefault) {
        if (inUse.equals(derivedDefault.policy())) {
            log.info("[{}] {}", processorName, derivedDefault.describe());
        } else {
            log.info("[{}] Uses its own RedeliveryPolicy: {}", processorName, inUse);
        }
    }

    /**
     * Determines the starting {@link GlobalEventOrder} for new event subscriptions.
     * Depending on the configuration, it may start from the latest persisted global event order
     * or from the first global event order.
     * <p>
     * If {@link #isStartSubscriptionFromLatestEvent()} is true, the starting global event order
     * will be the next global event order after the highest persisted one for a given {@link AggregateType}.
     * If it's false, the subscription will start from {@link GlobalEventOrder#FIRST_GLOBAL_EVENT_ORDER}.
     *
     * @return a {@link Function} that provides the starting {@link GlobalEventOrder} for each {@link AggregateType}.
     */
    protected Function<AggregateType, GlobalEventOrder> resolveStartSubscriptionFromGlobalEventOrder() {
        if (isStartSubscriptionFromLatestEvent()) {
            return aggregateType -> eventStore.getUnitOfWorkFactory().withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(aggregateType)
                                                                                                     .map(GlobalEventOrder::increment)
                                                                                                     .orElse(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER));
        }
        return aggregateType -> GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER;
    }

    /**
     * The {@link SubscriptionErrorPolicy} for this processor's event store subscriptions, in place of the policy of the
     * {@link EventStoreSubscriptionManager} - override it to give this processor a policy of its own, e.g.
     * <pre>{@code
     * @Override
     * protected Optional<SubscriptionErrorPolicy> getSubscriptionErrorPolicy() {
     *     return Optional.of(SubscriptionErrorPolicy.retryThenStop(5));
     * }
     * }</pre>
     * The policy decides what happens when the subscription's handler fails: for an {@link EventProcessor} that is
     * forwarding the event to its {@code Inbox} (the {@code Inbox}'s {@link RedeliveryPolicy} governs the handling
     * itself); for a {@link ViewEventProcessor} it is how often a failure the processor cannot queue in the
     * subscription's {@code UnitOfWork} is retried before it is queued in its own (see
     * {@link PersistedEventHandler#handOffFailedEvent(PersistedEvent, Throwable)}), and what happens if that queueing fails.
     * Read each time a subscription subscribes, so return the same policy every time. See {@link SubscriptionErrorPolicy}
     * for which setting wins.
     *
     * @return the policy for this processor's subscriptions, or {@link Optional#empty()} (the default) to use the
     * {@link EventStoreSubscriptionManager}'s policy
     */
    protected Optional<SubscriptionErrorPolicy> getSubscriptionErrorPolicy() {
        return Optional.empty();
    }

    /**
     * New subscriptions should start consuming events from the latest event. Default false
     *
     * @return is new subscriptions should start consuming events from the latest event.
     */
    protected boolean isStartSubscriptionFromLatestEvent() {
        return false;
    }

    /**
     * Retrieves the instance of the EventStore associated with this processor
     *
     * @return the {@link EventStore} instance
     */
    protected final EventStore getEventStore() {
        return eventStore;
    }

    /**
     * Retrieves the instance of the {@link DurableLocalCommandBus} associated with this processor.
     *
     * @return the {@link DurableLocalCommandBus} instance
     */
    protected final DurableLocalCommandBus getCommandBus() {
        return commandBus;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // awaitProcessed (ES-4)
    // ------------------------------------------------------------------------------------------------------------------

    /**
     * How often {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)} re-checks whether the event has been processed
     */
    static final Duration AWAIT_PROCESSED_POLL_INTERVAL = Duration.ofMillis(25);

    /**
     * Wait until this processor has processed the event with the given {@link GlobalEventOrder} - a barrier for
     * read-your-writes flows and integration tests, in place of polling a read model under a guessed timeout.
     * <pre>{@code
     * var appended = eventStore.appendToStream(ORDERS, orderId, new OrderPlaced(orderId));   // inside a UnitOfWork that then commits
     * orderViewProcessor.awaitProcessed(ORDERS, appended.firstEvent().globalEventOrder(), Duration.ofSeconds(10));
     * assertThat(orderView.find(orderId)).isPresent();                                       // no polling needed
     * }</pre>
     * The event counts as processed when <b>both</b> hold:
     * <ol>
     *     <li>the processor's subscription for the aggregate type has moved its resume point past the event, and has not
     *     skipped it as a transient gap that it still has to handle - so the subscription is done with it. The event store
     *     answers the second half when it is an {@link AwaitedGapInspector}, as the Essentials event stores are; one that
     *     is not - a decorator that does not forward it - cannot be asked, and a WARN says so once per processor, when it
     *     starts</li>
     *     <li>no message for the event's aggregate is queued in the processor's queue - an {@link EventProcessor} only
     *     forwards the event to its Inbox, and a {@link ViewEventProcessor} queues an event whose direct handling failed
     *     (and every later event of the same aggregate behind it). Dead letters count as queued, so an event that was
     *     dead-lettered is never reported as processed</li>
     * </ol>
     * Both are checked against this JVM's view of the processor: the barrier is for the instance that runs the
     * processor. An exclusive subscription held by another instance is read from its last saved resume point, which
     * trails the in-memory one by up to the subscription manager's snapshot interval; a {@link ViewEventProcessor} whose
     * lock another instance holds has no subscription here, and the barrier times out.
     * <p>
     * The check is conservative: a later event of the same aggregate that is still queued holds the barrier too, and so
     * does an event of a type the processor has no handler for while other messages for its aggregate are queued.
     * <p>
     * Call it after the {@link UnitOfWork} that appended the event has committed - inside it, the subscription cannot see
     * the event yet.
     *
     * @param aggregateType    the aggregate type the event belongs to - one this processor {@link #reactsToEventsRelatedToAggregateTypes() reacts to}
     * @param globalEventOrder the event's {@link GlobalEventOrder}, e.g. {@link PersistedEvent#globalEventOrder()} as returned by
     *                         {@link EventStore#appendToStream}
     * @param timeout          how long to wait at most
     * @throws AwaitProcessedTimeoutException if the event was not processed within the timeout - naming the processor, the
     *                                        event, the processor's current resume point and the number of queued messages
     * @throws IllegalArgumentException       if this processor doesn't react to events of the aggregate type
     * @see #awaitProcessedAsync(AggregateType, GlobalEventOrder, Duration)
     */
    public void awaitProcessed(AggregateType aggregateType, GlobalEventOrder globalEventOrder, Duration timeout) {
        var barrier  = newProcessedEventBarrier(aggregateType, globalEventOrder, timeout);
        var deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var progress = barrier.check();
            if (progress.processed()) {
                return;
            }
            var remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw barrier.timedOut(progress);
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(remainingNanos, AWAIT_PROCESSED_POLL_INTERVAL.toNanos())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(msg("[{}] Interrupted while awaiting the processing of the '{}' event with global-event-order {}",
                                                    getProcessorName(), aggregateType, globalEventOrder), e);
            }
        }
    }

    /**
     * Wait until this processor has processed the given event - see {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)}
     *
     * @param event   the event, e.g. one returned by {@link EventStore#appendToStream}
     * @param timeout how long to wait at most
     * @throws AwaitProcessedTimeoutException if the event was not processed within the timeout
     */
    public void awaitProcessed(PersistedEvent event, Duration timeout) {
        requireNonNull(event, "No event provided");
        awaitProcessed(event.aggregateType(), event.globalEventOrder(), timeout);
    }

    /**
     * Non-blocking variant of {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)}: completes empty once
     * this processor has processed the event, or signals an {@link AwaitProcessedTimeoutException} when the timeout
     * elapses first. Each check runs on {@link Schedulers#boundedElastic()} (it queries the database); no thread is held
     * between checks.
     *
     * @param aggregateType    the aggregate type the event belongs to
     * @param globalEventOrder the event's {@link GlobalEventOrder}
     * @param timeout          how long to wait at most
     * @return a {@link Mono} that completes once the event has been processed
     * @throws IllegalArgumentException if this processor doesn't react to events of the aggregate type (thrown on call, not signalled)
     */
    public Mono<Void> awaitProcessedAsync(AggregateType aggregateType, GlobalEventOrder globalEventOrder, Duration timeout) {
        var barrier = newProcessedEventBarrier(aggregateType, globalEventOrder, timeout);
        return Mono.defer(() -> {
                       var lastProgress = new AtomicReference<ProcessedEventBarrier.Progress>();
                       return Mono.fromCallable(barrier::check)
                                  .subscribeOn(Schedulers.boundedElastic())
                                  .doOnNext(lastProgress::set)
                                  .filter(ProcessedEventBarrier.Progress::processed)
                                  .repeatWhenEmpty(Integer.MAX_VALUE, emptyChecks -> emptyChecks.delayElements(AWAIT_PROCESSED_POLL_INTERVAL))
                                  .timeout(timeout, Mono.defer(() -> {
                                      var progress = lastProgress.get();
                                      return Mono.error(barrier.timedOut(progress != null ? progress : ProcessedEventBarrier.Progress.notYetChecked()));
                                  }))
                                  .then();
                   });
    }

    private ProcessedEventBarrier newProcessedEventBarrier(AggregateType aggregateType, GlobalEventOrder globalEventOrder, Duration timeout) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(globalEventOrder, "No globalEventOrder provided");
        requireNonNull(timeout, "No timeout provided");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException(msg("timeout must not be negative: {}", timeout));
        }
        var reactsTo = reactsToEventsRelatedToAggregateTypes();
        if (reactsTo == null || !reactsTo.contains(aggregateType)) {
            throw new IllegalArgumentException(msg("[{}] Cannot await the processing of a '{}' event: the processor reacts to events of {}, not of '{}'",
                                                   getProcessorName(), aggregateType, reactsTo, aggregateType));
        }
        return new ProcessedEventBarrier(aggregateType, globalEventOrder, timeout);
    }

    /**
     * Called when the processor starts: when the event store is no {@link AwaitedGapInspector},
     * {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)} cannot ask it whether the subscriber still awaits an
     * event as a gap, so it says so - once per processor, and when the processor starts rather than once an
     * {@code awaitProcessed} may already have returned early. Worded conditionally, as it is logged whether or not the
     * application ever calls {@code awaitProcessed}: one that does not can see from the line itself that it does not apply
     */
    final void warnOnceIfEventStoreIsNoAwaitedGapInspector() {
        if (!(eventStore instanceof AwaitedGapInspector) && warnedEventStoreIsNoAwaitedGapInspector.compareAndSet(false, true)) {
            log.warn("[{}] If you call awaitProcessed on this processor, it can report an event as processed before the processor handled it: " +
                             "the event store '{}' does not implement {}, so awaitProcessed cannot tell whether an event the subscription moved past " +
                             "while it was uncommitted (a gap) is still to be handled. To fix it, have the EventStore that decorates the PostgresqlEventStore " +
                             "or CdcEventStore implement {} and forward to its delegate. An application that never calls awaitProcessed can ignore this",
                     getProcessorName(), eventStore.getClass().getName(), AwaitedGapInspector.class.getSimpleName(), AwaitedGapInspector.class.getSimpleName());
        }
    }

    /**
     * Used by {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)}: is a message for the given aggregate
     * still queued in this processor's queue (Dead Letter Messages included)?<br>
     * {@link EventProcessor} and {@link ViewEventProcessor} implement it; the default throws
     * {@link UnsupportedOperationException}, as a processor that doesn't tell can't be awaited.
     *
     * @param aggregateType         the aggregate type
     * @param serializedAggregateId the aggregate id, serialized by the aggregate type's {@link AggregateIdSerializer} -
     *                              the key of the {@link OrderedMessage}s the processor queues for it
     * @return true if at least one message for the aggregate is queued
     */
    protected boolean hasQueuedMessagesForAggregate(AggregateType aggregateType, String serializedAggregateId) {
        throw new UnsupportedOperationException(msg("{} '{}' can't tell whether messages are queued for an aggregate, so it doesn't support awaitProcessed",
                                                    getClass().getName(), getProcessorName()));
    }

    /**
     * Used for the {@link AwaitProcessedTimeoutException} message: the number of queued and dead-letter messages in this
     * processor's queue. Default: unknown
     *
     * @return the processor's queue counts, or empty if unknown
     */
    protected Optional<QueuedMessageCounts> getQueuedMessageCountsForAwaitProcessed() {
        return Optional.empty();
    }

    /**
     * One {@link #awaitProcessed(AggregateType, GlobalEventOrder, Duration)} call's checks. Caches the awaited event's
     * aggregate id once it has been loaded
     */
    private final class ProcessedEventBarrier {
        private final AggregateType    aggregateType;
        private final GlobalEventOrder globalEventOrder;
        private final Duration         timeout;
        private volatile String        serializedAggregateId;

        private ProcessedEventBarrier(AggregateType aggregateType, GlobalEventOrder globalEventOrder, Duration timeout) {
            this.aggregateType = aggregateType;
            this.globalEventOrder = globalEventOrder;
            this.timeout = timeout;
        }

        Progress check() {
            var subscriptions = eventStoreSubscriptions;
            var subscription = subscriptions == null ? Optional.<EventStoreSubscription>empty() :
                               subscriptions.stream().filter(candidate -> candidate.aggregateType().equals(aggregateType)).findFirst();
            if (subscription.isEmpty()) {
                return Progress.notYet(Optional.empty(), "the processor has no subscription for the aggregate type in this JVM - it isn't started, or another instance holds its lock");
            }
            var resumeFromAndIncluding = subscription.get().currentResumePoint().map(SubscriptionResumePoint::getResumeFromAndIncluding);
            if (resumeFromAndIncluding.isEmpty() || resumeFromAndIncluding.get().longValue() <= globalEventOrder.longValue()) {
                return Progress.notYet(resumeFromAndIncluding, "the subscription hasn't moved past the event yet");
            }
            if (isAwaitedAsGap(subscription.get())) {
                return Progress.notYet(resumeFromAndIncluding, "the subscription moved past the event while it was uncommitted and still has to handle it (awaited as a gap)");
            }
            var aggregateId = serializedAggregateId;
            if (aggregateId == null) {
                var resolved = withUnitOfWork(() -> eventStore.loadEventsByGlobalOrder(aggregateType, LongRange.only(globalEventOrder.longValue()))
                                                              .findFirst()
                                                              .map(event -> resolveAggregateIdSerializer(aggregateType).serialize(event.aggregateId())));
                if (resolved.isEmpty()) {
                    var insideUnitOfWork = eventStore.getUnitOfWorkFactory().getCurrentUnitOfWork().isPresent();
                    return Progress.notYet(resumeFromAndIncluding, insideUnitOfWork ?
                                                                   "no committed event with that global-event-order exists - awaitProcessed was called inside an active UnitOfWork, so an event appended in it isn't committed yet" :
                                                                   "no event with that global-event-order exists");
                }
                aggregateId = resolved.get();
                serializedAggregateId = aggregateId;
            }
            if (hasQueuedMessagesForAggregate(aggregateType, aggregateId)) {
                return Progress.notYet(resumeFromAndIncluding, msg("a message for aggregate '{}' is still queued (waiting, being handled, awaiting redelivery or dead-lettered)", aggregateId));
            }
            return Progress.PROCESSED;
        }

        /**
         * Asks the event store, when it is an {@link AwaitedGapInspector}, whether the subscriber still awaits the event as
         * a gap - a transient gap, or the middle of a wide gap awaited in memory only. One that is not cannot be asked: the
         * check is off, as the WARN logged when the processor started says
         */
        private boolean isAwaitedAsGap(EventStoreSubscription subscription) {
            if (eventStore instanceof AwaitedGapInspector awaitedGapInspector) {
                return awaitedGapInspector.isAwaitedAsGap(subscription.subscriberId(), aggregateType, globalEventOrder);
            }
            // For a processor whose start did not log it
            warnOnceIfEventStoreIsNoAwaitedGapInspector();
            return false;
        }

        AwaitProcessedTimeoutException timedOut(Progress progress) {
            Optional<QueuedMessageCounts> queuedMessageCounts;
            try {
                queuedMessageCounts = getQueuedMessageCountsForAwaitProcessed();
            } catch (RuntimeException e) {
                log.debug("[{}] Could not count the queued messages", getProcessorName(), e);
                queuedMessageCounts = Optional.empty();
            }
            return new AwaitProcessedTimeoutException(getProcessorName(),
                                                      aggregateType,
                                                      globalEventOrder,
                                                      timeout,
                                                      progress.resumeFromAndIncluding(),
                                                      progress.reason(),
                                                      queuedMessageCounts);
        }

        record Progress(boolean processed, Optional<GlobalEventOrder> resumeFromAndIncluding, String reason) {
            static final Progress PROCESSED = new Progress(true, Optional.empty(), "processed");

            static Progress notYet(Optional<GlobalEventOrder> resumeFromAndIncluding, String reason) {
                return new Progress(false, resumeFromAndIncluding, reason);
            }

            static Progress notYetChecked() {
                return new Progress(false, Optional.empty(), "the first check had not completed");
            }
        }
    }

    /**
     * Variant of {@link OrderedMessage} that stores the {@link AggregateType} associated with the Message as the payload,
     * the {@link PersistedEvent#aggregateId()} as the {@link OrderedMessage#getKey()} and
     * the {@link PersistedEvent#eventOrder()} as the {@link OrderedMessage#getOrder()}
     */
    public static class EventReferenceOrderedMessage extends OrderedMessage {
        /**
         * {@link MessageMetaData} key, that's used as a flag to indicate
         * that an {@link OrderedMessage} is an {@link EventProcessor.EventReferenceOrderedMessage}
         */
        public static final String EVENT_REFERENCE_METADATA_KEY = "EVENT_REFERENCE";

        public EventReferenceOrderedMessage(AggregateType aggregateType, Object aggregateId, EventOrder eventOrder) {
            this(aggregateType, aggregateId, eventOrder, MessageMetaData.of());
        }

        public EventReferenceOrderedMessage(AggregateType aggregateType, Object aggregateId, EventOrder eventOrder, MessageMetaData metaData) {
            super(aggregateType, aggregateId.toString(), eventOrder.longValue(), metaData);
            metaData.put(EVENT_REFERENCE_METADATA_KEY, "true");
        }

        public static boolean isEventReference(OrderedMessage msg) {
            requireNonNull(msg, "No msg provided");
            return "true".equals(msg.getMetaData().get(EVENT_REFERENCE_METADATA_KEY));
        }
    }
}
