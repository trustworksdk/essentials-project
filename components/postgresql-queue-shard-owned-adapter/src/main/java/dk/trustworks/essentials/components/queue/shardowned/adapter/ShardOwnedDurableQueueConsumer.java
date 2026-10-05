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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
// Single-type imports, not the package: shardowned.spi and foundation...queue both export QueueName,
// Message and QueuedMessage, and this class deals in the foundation's.
import dk.trustworks.essentials.components.queue.shardowned.spi.ConsumerOptions;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageHandler;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.Subscription;
import org.slf4j.*;

import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link DurableQueueConsumer} over a shard-owned {@link Subscription}.
 *
 * <h2>Retry is the engine's, not the foundation's</h2>
 * {@code DefaultDurableQueueConsumer} implements redelivery itself: it counts attempts, consults the
 * {@link RedeliveryPolicy}, and calls back into {@code DurableQueues} to reschedule or dead-letter.
 * This consumer does none of that, because the engine already does all of it — per shard, under the
 * owner's fence, with the schedule held in memory as well as in the row. Running both would produce
 * two independent retry clocks over one message.
 * <p>
 * So the policy is translated once, at subscription time, into the engine's {@link ConsumerOptions},
 * and the engine owns the outcome from there. The attempt limit is copied; the wait before each retry is
 * not, it is the policy's own {@link RedeliveryPolicy#calculateNextRedeliveryDelay(int)}, so every
 * strategy produces the same waits here as on the other engines.
 */
class ShardOwnedDurableQueueConsumer implements DurableQueueConsumer {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDurableQueueConsumer.class);

    private final ConsumeFromQueue               operation;
    private final MessageQueue                   queue;
    private final JSONSerializer                 jsonSerializer;
    private final Consumer<DurableQueueConsumer> onCancel;
    private final DeliveryDispatch               delivery;

    private volatile Subscription subscription;

    ShardOwnedDurableQueueConsumer(ConsumeFromQueue operation,
                                   MessageQueue queue,
                                   JSONSerializer jsonSerializer,
                                   Consumer<DurableQueueConsumer> onCancel,
                                   DeliveryDispatch delivery) {
        this.operation = requireNonNull(operation, "No operation provided");
        this.queue = requireNonNull(queue, "No queue provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.onCancel = requireNonNull(onCancel, "No onCancel provided");
        this.delivery = requireNonNull(delivery, "No delivery provided");
    }

    /**
     * How a delivery reaches its handler.
     * <p>
     * {@link ShardOwnedDurableQueues} supplies this so that the handler is invoked inside the
     * {@code HandleQueuedMessage} interceptor chain, which is where an interceptor expects to sit on
     * the other engine too. Kept as a parameter rather than a reference back to the adapter so that
     * this class stays testable without one.
     */
    @FunctionalInterface
    interface DeliveryDispatch {
        void handle(QueuedMessage message, QueuedMessageHandler messageHandler);
    }

    @Override
    public QueueName queueName() {
        return operation.getQueueName();
    }

    @Override
    public String consumerName() {
        return operation.getConsumerName();
    }

    @Override
    public RedeliveryPolicy getRedeliveryPolicy() {
        return operation.getRedeliveryPolicy();
    }

    @Override
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        try {
            subscription = queue.consume(new MessageHandler() {
                @Override
                public void handle(MessageId messageId, String key, byte[] payload, int payloadType) {
                    throw new IllegalStateException("The engine delivers with the key order");
                }

                @Override
                public void handle(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
                    deliver(messageId, key, keyOrder, payload, payloadType);
                }
            }, toConsumerOptions(operation));
            log.info("Consumer '{}' started on queue '{}'", consumerName(), queueName());
        } catch (Exception e) {
            throw new DurableQueueException("Failed to start consumer '" + consumerName() + "'", e, queueName());
        }
    }

    @Override
    public synchronized void stop() {
        if (subscription == null) {
            return;
        }
        subscription.stop();
        subscription = null;
        log.info("Consumer '{}' stopped on queue '{}'", consumerName(), queueName());
    }

    @Override
    public boolean isStarted() {
        var current = subscription;
        return current != null && current.isStarted();
    }

    @Override
    public void cancel() {
        stop();
        onCancel.accept(this);
    }

    /**
     * One delivery.
     * <p>
     * Returning normally acknowledges the message; throwing hands it back to the engine's retry
     * schedule. There is no third outcome, which is why a handler asking for redelivery has to be
     * turned into a throw — see {@link ShardOwnedQueuedMessage#markForRedeliveryIn}.
     * <p>
     * The handler is reached through {@link DeliveryDispatch} rather than called here, so that a
     * {@code HandleQueuedMessage} interceptor wraps it. An interceptor that does not proceed therefore
     * returns normally, and the message is acknowledged as handled — the same outcome as a handler
     * that returns without doing anything.
     */
    private void deliver(MessageId messageId, String key, long keyOrder, byte[] payload, int payloadType) {
        if (payloadType != MessageEnvelope.FORMAT_VERSION) {
            throw new DurableQueueException(
                    "Message was written in envelope format " + payloadType + ", and this adapter reads format "
                            + MessageEnvelope.FORMAT_VERSION
                            + ". A message enqueued by something other than this adapter cannot be delivered through it.",
                    queueName());
        }
        // The order matters, not only the key: an EventProcessor's forwarded event is a reference whose order is the
        // event's position in its stream. Rebuilt as 0, every reference resolved to the aggregate's first event
        var message = MessageEnvelope.deserialize(jsonSerializer, payload, key, keyOrder);
        var queuedMessage = ShardOwnedQueuedMessage.beingDelivered(queueName(),
                                                                   QueueEntryIdCodec.encode(queueName(), messageId),
                                                                   message);

        delivery.handle(queuedMessage, operation.getQueueMessageHandler());

        if (queuedMessage.isManuallyMarkedForRedelivery()) {
            throw new ManualRedeliveryRequested(queuedMessage.getRedeliveryDelay());
        }
    }

    static ConsumerOptions toConsumerOptions(ConsumeFromQueue operation) {
        var policy   = operation.getRedeliveryPolicy();
        var defaults = ConsumerOptions.defaults();
        // A DurableQueues policy counts REdeliveries; the engine counts attempts. Off by one on both
        // counts below: missed in the limit it is one whole extra delivery of every failing message,
        // missed in the backoff every wait is shifted one step along the policy's sequence.
        return new ConsumerOptions(operation.getParallelConsumers(),
                                   defaults.maxShards(),
                                   policy.maximumNumberOfRedeliveries + 1,
                                   // Delegated rather than mapped onto the engine's own formula, which
                                   // has one delay where a policy has an initial and a follow-up, and
                                   // cannot express a linear policy at all. Mapping it used to run every
                                   // retry from initialRedeliveryDelay and ignore followupRedeliveryDelay.
                                   attemptsSoFar -> policy.calculateNextRedeliveryDelay(Math.max(0, attemptsSoFar - 1)));
    }

    /**
     * Thrown to turn {@code markForRedeliveryIn} into the only outcome the engine understands as
     * "deliver this again". The requested delay is carried for the log line only — the engine
     * schedules from its own policy, because a handler is never told which message it holds.
     */
    static final class ManualRedeliveryRequested extends RuntimeException {
        ManualRedeliveryRequested(java.time.Duration requestedDelay) {
            super("The handler asked for redelivery in " + requestedDelay
                          + "; the engine will reschedule at its own next backoff interval");
        }
    }
}
