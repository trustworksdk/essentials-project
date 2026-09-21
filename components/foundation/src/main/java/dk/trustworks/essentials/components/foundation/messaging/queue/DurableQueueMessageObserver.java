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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Notified of how a message delivery <em>ended</em>, from the two places that know: {@link CentralizedMessageFetcher}
 * and {@link DefaultDurableQueueConsumer}. Every consumer path funnels through one of those, so {@code Inbox},
 * {@code Outbox} and {@code DurableLocalCommandBus} are covered with no extra wiring.
 *
 * <h2>Why not a {@link DurableQueuesInterceptor}</h2>
 * An interceptor sees the operation, not the outcome. {@code chain.proceed()} on {@code HandleQueuedMessage} covers
 * the handler invocation only — the acknowledgement, the dead-lettering and the retry all happen after it returns,
 * and the operations carrying those ({@code AcknowledgeMessageAsHandled}, {@code DeleteMessage}) carry nothing but a
 * {@link QueueEntryId}. An interceptor-based collector would have to keep a map of in-flight messages keyed by id,
 * plus a size cap, plus a sweep for entries whose acknowledgement never arrives — state that leaks by default.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>It must never affect delivery.</b> The framework wraps whatever it is given in {@link #safe(DurableQueueMessageObserver)},
 *       which swallows and logs once per observer. These methods run on delivery threads, so an implementation must
 *       not block either.</li>
 *   <li><b>{@link #messageHandled} fires after the acknowledgement is issued</b>, so its count means "delivered and
 *       removed", not "the handler returned".</li>
 *   <li><b>Not a single-slot SPI.</b> {@link #composite(List)} lets a statistics registry and a Micrometer observer
 *       coexist without one decorating the other.</li>
 *   <li><b>Administrative operations are not deliveries.</b> {@code deleteMessage} and {@code purgeQueue} do not
 *       notify. Counting them is how the trigger removed in 0.60 reported a 100 000-row purge as 100 000 delivered
 *       messages, each with a delivery latency measured to the moment of the purge.</li>
 * </ul>
 */
public interface DurableQueueMessageObserver {

    /**
     * A delivery succeeded and the message has been acknowledged and removed from the queue.
     *
     * @param message         the message that was delivered
     * @param handlerDuration how long the message handler took
     */
    default void messageHandled(QueuedMessage message, Duration handlerDuration) {
    }

    /**
     * The handler asked for the message to be redelivered without failing — see
     * {@code QueuedMessageHandler}'s redelivery request path.
     *
     * @param message the message that will be redelivered
     */
    default void messageRedeliveryRequested(QueuedMessage message) {
    }

    /**
     * A delivery failed and the message will be redelivered.
     *
     * @param message         the message that failed
     * @param cause           the error the handler threw
     * @param redeliveryDelay how long until the message becomes deliverable again
     */
    default void messageRetried(QueuedMessage message, Throwable cause, Duration redeliveryDelay) {
    }

    /**
     * A delivery failed and the message was marked as a Poison-Message/Dead-Letter-Message.
     *
     * @param message the message that was dead-lettered
     * @param cause   the error the handler threw
     * @param outcome why: {@link MessageDeliveryOutcome#PERMANENT_ERROR} — the failure was never going to
     *                succeed — or {@link MessageDeliveryOutcome#REDELIVERIES_EXHAUSTED} — it was retried the
     *                configured number of times and kept failing. An operator needs to tell these apart, and a
     *                metric needs it as a tag
     */
    default void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
    }

    /**
     * @return an observer that does nothing — the default for a {@link DurableQueues} implementation that has none
     */
    static DurableQueueMessageObserver none() {
        return NoOp.INSTANCE;
    }

    /**
     * Fan out to several observers. Each is invoked even if an earlier one threw, provided the observers are
     * wrapped in {@link #safe(DurableQueueMessageObserver)} — which is what the framework does.
     *
     * @param observers the observers to notify, in order
     * @return one observer notifying all of them; {@link #none()} when the list is empty
     */
    static DurableQueueMessageObserver composite(List<DurableQueueMessageObserver> observers) {
        requireNonNull(observers, "No observers provided");
        var notNull = observers.stream().filter(Objects::nonNull).map(DurableQueueMessageObserver::safe).toList();
        if (notNull.isEmpty()) {
            return none();
        }
        if (notNull.size() == 1) {
            return notNull.get(0);
        }
        return new Composite(notNull);
    }

    /**
     * Wrap an observer so a failure inside it cannot break message delivery. The first failure of each wrapped
     * observer is logged at WARN with its stack trace; later ones at DEBUG, so a consistently broken observer does
     * not flood the log from a delivery thread.
     *
     * @param observer the observer to wrap
     * @return the wrapped observer, or {@code observer} itself if it is already wrapped or is {@link #none()}
     */
    static DurableQueueMessageObserver safe(DurableQueueMessageObserver observer) {
        requireNonNull(observer, "No observer provided");
        if (observer instanceof Safe || observer instanceof NoOp || observer instanceof Composite) {
            return observer;
        }
        return new Safe(observer);
    }

    /**
     * @see #none()
     */
    final class NoOp implements DurableQueueMessageObserver {
        private static final NoOp INSTANCE = new NoOp();

        private NoOp() {
        }

        @Override
        public String toString() {
            return "DurableQueueMessageObserver.none()";
        }
    }

    /**
     * @see #composite(List)
     */
    final class Composite implements DurableQueueMessageObserver {
        private final List<DurableQueueMessageObserver> observers;

        private Composite(List<DurableQueueMessageObserver> observers) {
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
        public void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
            observers.forEach(observer -> observer.messageDeadLettered(message, cause, outcome));
        }

        @Override
        public String toString() {
            return "DurableQueueMessageObserver.composite(" + observers + ")";
        }
    }

    /**
     * @see #safe(DurableQueueMessageObserver)
     */
    final class Safe implements DurableQueueMessageObserver {
        private static final Logger log = LoggerFactory.getLogger(Safe.class);

        private final DurableQueueMessageObserver delegate;
        private final AtomicBoolean               failureLogged = new AtomicBoolean();

        private Safe(DurableQueueMessageObserver delegate) {
            this.delegate = delegate;
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
        public void messageDeadLettered(QueuedMessage message, Throwable cause, MessageDeliveryOutcome outcome) {
            guard("messageDeadLettered", () -> delegate.messageDeadLettered(message, cause, outcome));
        }

        private void guard(String callback, Runnable notification) {
            try {
                notification.run();
            } catch (Throwable e) {
                if (failureLogged.compareAndSet(false, true)) {
                    log.warn("DurableQueueMessageObserver '{}' threw from {} - observability must never affect " +
                                     "message delivery, so the failure is swallowed. Later failures from this " +
                                     "observer are logged at DEBUG",
                             delegate, callback, e);
                } else {
                    log.debug("DurableQueueMessageObserver '{}' threw from {}", delegate, callback, e);
                }
            }
        }

        @Override
        public String toString() {
            return "DurableQueueMessageObserver.safe(" + delegate + ")";
        }
    }
}
