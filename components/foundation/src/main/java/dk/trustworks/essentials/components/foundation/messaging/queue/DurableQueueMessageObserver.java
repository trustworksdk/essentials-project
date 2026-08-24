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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import org.slf4j.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Observes how each message delivery <b>ended</b> — handled, retried, dead-lettered — for statistics and
 * observability.
 *
 * <h2>Why this is not a {@link DurableQueuesInterceptor}</h2>
 * An interceptor sees the operation, not the outcome. {@code HandleQueuedMessage} carries the full
 * {@link QueuedMessage}, but {@code chain.proceed()} covers only the handler invocation — the acknowledgement, the
 * dead-lettering and the retry all happen <em>after</em> it returns, and the operations that carry those
 * ({@code AcknowledgeMessageAsHandled}, {@code DeleteMessage}) carry only a {@link QueueEntryId}. An
 * interceptor-only implementation therefore has to keep a map of in-flight messages keyed by id, plus a size cap
 * and a sweep for the entries whose acknowledgement never arrives — avoidable state that leaks by default.
 * <p>
 * The two places that decide the outcome — {@link CentralizedMessageFetcher} and
 * {@link DefaultDurableQueueConsumer} — hold the {@link QueuedMessage} <em>and</em> know how the delivery ended,
 * so observing there removes the correlation problem instead of managing it. Every consumer path funnels through
 * those two, so {@code Inbox}, {@code Outbox} and {@code DurableLocalCommandBus} deliveries are covered with no
 * extra wiring.
 *
 * <h2>Contract</h2>
 * <ul>
 *     <li><b>An observer must never affect delivery.</b> The framework wraps whatever it is given in
 *     {@link #safe(DurableQueueMessageObserver)}, which swallows every exception and logs once — but an
 *     implementation should still not throw, and must not block: these methods run on the delivery threads.</li>
 *     <li><b>{@link #messageHandled} fires after the acknowledgement is issued</b>, so its count means "delivered
 *     and removed from the queue" rather than "the handler returned".</li>
 *     <li><b>This is not a single-slot SPI.</b> {@link #composite(List)} exists so statistics and Micrometer can
 *     coexist without one wrapping the other — the mistake {@code EventStoreSubscriptionObserver} made, where
 *     anything new has to decorate rather than register.</li>
 *     <li><b>Administrative operations are not deliveries.</b> {@code deleteMessage} and {@code purgeQueue} do not
 *     notify an observer. That is deliberate: the trigger-based statistics this replaces counted a 100 000-row
 *     purge as 100 000 delivered messages, each with a delivery latency measured to the moment of the purge.</li>
 * </ul>
 */
public interface DurableQueueMessageObserver {

    /**
     * The message was handled successfully and its acknowledgement has been issued.
     *
     * @param message         the message as delivered
     * @param handlerDuration how long the message handler itself took, excluding fetch and acknowledgement
     */
    default void messageHandled(QueuedMessage message, Duration handlerDuration) {
    }

    /**
     * The handler returned normally but asked for the message to be delivered again
     * ({@link QueuedMessage#markForRedeliveryIn(Duration)}). Not a failure, and not a delivery.
     */
    default void messageRedeliveryRequested(QueuedMessage message) {
    }

    /**
     * The handler threw and the message will be delivered again after {@code redeliveryDelay}.
     */
    default void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
    }

    /**
     * The handler threw and the message has exhausted its redelivery policy, or failed permanently.
     */
    default void messageDeadLettered(QueuedMessage message, Throwable cause) {
    }

    /**
     * @return an observer that records nothing - the default for every {@link DurableQueues} implementation
     */
    static DurableQueueMessageObserver none() {
        return new DurableQueueMessageObserver() {
            @Override
            public String toString() {
                return "DurableQueueMessageObserver.none()";
            }
        };
    }

    /**
     * Fans every callback out to all the given observers, guarded individually so one throwing observer cannot
     * stop the others from being notified.
     *
     * @param observers the observers to notify; an empty list yields {@link #none()}
     */
    static DurableQueueMessageObserver composite(List<DurableQueueMessageObserver> observers) {
        requireNonNull(observers, "No observers provided");
        var guarded = observers.stream().map(DurableQueueMessageObserver::safe).toList();
        if (guarded.isEmpty()) {
            return none();
        }
        if (guarded.size() == 1) {
            return guarded.get(0);
        }
        return new CompositeDurableQueueMessageObserver(guarded);
    }

    /**
     * Wraps the observer so that any exception it throws is swallowed and logged <b>once</b>, rather than
     * propagating into the delivery path.
     * <p>
     * Logged once rather than per occurrence on purpose: a broken observer fires on every message, and an
     * unthrottled stack trace per delivery turns a cosmetic fault into an outage of its own. Same reasoning, and
     * same shape, as {@code StatisticsCollectingEventStoreSubscriptionObserver}.
     */
    static DurableQueueMessageObserver safe(DurableQueueMessageObserver observer) {
        requireNonNull(observer, "No observer provided");
        if (observer instanceof SafeDurableQueueMessageObserver || observer instanceof CompositeDurableQueueMessageObserver) {
            return observer;
        }
        return new SafeDurableQueueMessageObserver(observer);
    }

    /**
     * @see #safe(DurableQueueMessageObserver)
     */
    final class SafeDurableQueueMessageObserver implements DurableQueueMessageObserver {
        private static final Logger log = LoggerFactory.getLogger(SafeDurableQueueMessageObserver.class);

        private final DurableQueueMessageObserver delegate;
        private final AtomicBoolean               hasLoggedFailure = new AtomicBoolean();

        private SafeDurableQueueMessageObserver(DurableQueueMessageObserver delegate) {
            this.delegate = delegate;
        }

        private void guard(String callback, Runnable notification) {
            try {
                notification.run();
            } catch (Throwable e) {
                if (hasLoggedFailure.compareAndSet(false, true)) {
                    log.warn("Observer '{}' threw from {} - suppressing this and any further failures from it. "
                                     + "Message delivery is unaffected.", delegate, callback, e);
                }
            }
        }

        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            guard("messageHandled", () -> delegate.messageHandled(message, handlerDuration));
        }

        @Override
        public void messageRedeliveryRequested(QueuedMessage message) {
            guard("messageRedeliveryRequested", () -> delegate.messageRedeliveryRequested(message));
        }

        @Override
        public void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
            guard("messageRetried", () -> delegate.messageRetried(message, cause, redeliveryDelay));
        }

        @Override
        public void messageDeadLettered(QueuedMessage message, Throwable cause) {
            guard("messageDeadLettered", () -> delegate.messageDeadLettered(message, cause));
        }

        @Override
        public String toString() {
            return "safe(" + delegate + ")";
        }
    }

    /**
     * @see #composite(List)
     */
    final class CompositeDurableQueueMessageObserver implements DurableQueueMessageObserver {
        private final List<DurableQueueMessageObserver> observers;

        private CompositeDurableQueueMessageObserver(List<DurableQueueMessageObserver> observers) {
            this.observers = observers;
        }

        @Override
        public void messageHandled(QueuedMessage message, Duration handlerDuration) {
            observers.forEach(observer -> observer.messageHandled(message, handlerDuration));
        }

        @Override
        public void messageRedeliveryRequested(QueuedMessage message) {
            observers.forEach(observer -> observer.messageRedeliveryRequested(message));
        }

        @Override
        public void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
            observers.forEach(observer -> observer.messageRetried(message, cause, redeliveryDelay));
        }

        @Override
        public void messageDeadLettered(QueuedMessage message, Throwable cause) {
            observers.forEach(observer -> observer.messageDeadLettered(message, cause));
        }

        @Override
        public String toString() {
            return "composite(" + observers + ")";
        }
    }
}
