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

package dk.trustworks.essentials.components.foundation.messaging.queue.operations;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;

import java.util.function.BooleanSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Called by the {@link DurableQueueConsumer} when a {@link QueuedMessage} has been selected
 * for processing.
 * Operation also matches {@link DurableQueuesInterceptor#intercept(HandleQueuedMessage, InterceptorChain)}
 */
public final class HandleQueuedMessage {
    /**
     * The {@link QueuedMessage} ready for being handled by the {@link #messageHandler}
     */
    public final QueuedMessage        message;
    /**
     * The {@link QueuedMessageHandler} configured in the {@link DurableQueues#consumeFromQueue(ConsumeFromQueue)} operation
     */
    public final QueuedMessageHandler messageHandler;
    /**
     * Live view of whether the consumer delivering the message is still started, or {@code null} when the caller
     * cannot tell - see {@link #consumerState()}
     */
    private final BooleanSupplier     consumerStarted;
    /**
     * The result of acknowledging the message inside the handler's own transaction, or {@code null} when it was not
     * acknowledged there - see {@link #acknowledgedWithHandler(boolean)}
     */
    private volatile Boolean          acknowledgedWithHandler;

    /**
     * Create a new builder that produces a new {@link HandleQueuedMessage} instance
     *
     * @return a new {@link HandleQueuedMessageBuilder} instance
     */
    public static HandleQueuedMessageBuilder builder() {
        return new HandleQueuedMessageBuilder();
    }

    /**
     * Called by the {@link DurableQueueConsumer} when a {@link QueuedMessage} has been selected
     * for processing.
     *
     * @param message        The {@link QueuedMessage} ready for being handled by the {@link #messageHandler}
     * @param messageHandler The {@link QueuedMessageHandler} configured in the {@link DurableQueues#consumeFromQueue(ConsumeFromQueue)} operation
     */
    public HandleQueuedMessage(QueuedMessage message, QueuedMessageHandler messageHandler) {
        this(message, messageHandler, null);
    }

    /**
     * Target of {@link HandleQueuedMessageBuilder#build()}.
     *
     * @param message         The {@link QueuedMessage} ready for being handled by the {@link #messageHandler}
     * @param messageHandler  The {@link QueuedMessageHandler} configured in the {@link DurableQueues#consumeFromQueue(ConsumeFromQueue)} operation
     * @param consumerStarted Live view of whether the consumer delivering the message is still started, typically
     *                        {@code consumer::isStarted}. It is read each time it is asked, not once, because the
     *                        consumer's {@code stop()} can begin while the handler runs. {@code null} when the caller
     *                        cannot tell: {@link #consumerState()} then answers {@link ConsumerState#UNKNOWN}
     */
    HandleQueuedMessage(QueuedMessage message, QueuedMessageHandler messageHandler, BooleanSupplier consumerStarted) {
        this.message = requireNonNull(message, "No message was provided");
        this.messageHandler = requireNonNull(messageHandler, "No message handler was provided");
        this.consumerStarted = consumerStarted;
    }

    /**
     * The {@link QueuedMessage} ready for being handled by the {@link #messageHandler}
     *
     * @return The {@link QueuedMessage} ready for being handled by the {@link #messageHandler}
     */
    public QueuedMessage getMessage() {
        return message;
    }

    /**
     * The {@link QueuedMessageHandler} configured in the {@link DurableQueues#consumeFromQueue(ConsumeFromQueue)} operation
     *
     * @return The {@link QueuedMessageHandler} configured in the {@link DurableQueues#consumeFromQueue(ConsumeFromQueue)} operation
     */
    public QueuedMessageHandler getMessageHandler() {
        return messageHandler;
    }

    /**
     * The state of the consumer delivering the message, right now, from a single read of its live state. A consumer's
     * {@code stop()} marks it as no longer started before it interrupts its delivery threads, so a handler that
     * returns with the thread's interrupt flag set was not interrupted by a stop when this is
     * {@link ConsumerState#STARTED}, and may not have finished its work when this is {@link ConsumerState#STOPPING}.
     * <p>
     * Ask this once and branch on the answer rather than calling {@link #isConsumerKnownToBeStarted()} and
     * {@link #isConsumerKnownToBeStopping()} in turn: each of those reads the state again, and a consumer can stop -
     * or, once stopped, be started again - between the two reads, so the pair can say "neither" for a consumer whose
     * state is known.
     *
     * @return {@link ConsumerState#STARTED} or {@link ConsumerState#STOPPING}; {@link ConsumerState#UNKNOWN} when the
     * operation was created without a consumer state
     */
    public ConsumerState consumerState() {
        if (consumerStarted == null) {
            return ConsumerState.UNKNOWN;
        }
        return consumerStarted.getAsBoolean() ? ConsumerState.STARTED : ConsumerState.STOPPING;
    }

    /**
     * Whether the consumer delivering the message is, right now, known to be started - shorthand for
     * {@code consumerState() == ConsumerState.STARTED}. A caller that needs to tell started from stopping should ask
     * {@link #consumerState()} once instead of combining this with {@link #isConsumerKnownToBeStopping()}.
     *
     * @return {@code true} when the consumer is known to be started; {@code false} when it is stopping, or when the
     * operation was created without a consumer state
     */
    public boolean isConsumerKnownToBeStarted() {
        return consumerState() == ConsumerState.STARTED;
    }

    /**
     * Whether the consumer delivering the message is, right now, known to be stopping (or stopped) - shorthand for
     * {@code consumerState() == ConsumerState.STOPPING}. A caller that needs to tell started from stopping should ask
     * {@link #consumerState()} once instead of combining this with {@link #isConsumerKnownToBeStarted()}.
     *
     * @return {@code true} when the consumer is known to be stopping; {@code false} when it is started, or when the
     * operation was created without a consumer state
     */
    public boolean isConsumerKnownToBeStopping() {
        return consumerState() == ConsumerState.STOPPING;
    }

    /**
     * Record that a {@link DurableQueuesInterceptor} acknowledged the message inside the transaction the handler ran
     * in, so the handler's writes and the acknowledgement commit together. The consumer then skips its own,
     * separate acknowledgement.
     *
     * @param acknowledged what {@link DurableQueues#acknowledgeMessageAsHandled(QueueEntryId)} returned
     */
    public void acknowledgedWithHandler(boolean acknowledged) {
        this.acknowledgedWithHandler = acknowledged;
    }

    /**
     * @return the result of acknowledging the message inside the handler's transaction, or {@code null} when the
     * consumer still has to acknowledge it - see {@link #acknowledgedWithHandler(boolean)}
     */
    public Boolean getAcknowledgedWithHandler() {
        return acknowledgedWithHandler;
    }

    @Override
    public String toString() {
        return "HandleQueuedMessage{" +
                "message=" + message +
                ", messageHandler=" + messageHandler +
                '}';
    }

    /**
     * The state of the consumer delivering a message, as {@link #consumerState()} reads it
     */
    public enum ConsumerState {
        /**
         * The consumer is started: it has not begun stopping
         */
        STARTED,
        /**
         * The consumer is stopping, or has stopped: its {@code stop()} has begun, and may interrupt its delivery threads
         */
        STOPPING,
        /**
         * The operation was created without a consumer state, so the consumer is neither known to be started nor known
         * to be stopping
         */
        UNKNOWN
    }
}
