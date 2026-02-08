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

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLock;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Represents a message that must be delivered and processed in a specific order relative to other messages
 * sharing the same {@link #key}.
 * <p>
 * {@link OrderedMessage}'s are designed for scenarios where maintaining the chronological sequence of messages
 * per entity is critical, such as:
 * <ul>
 *   <li><b>Event Sourcing:</b> Processing domain events in the order they occurred per aggregate</li>
 *   <li><b>CQRS Projections:</b> Building read models that reflect the correct state</li>
 *   <li><b>Change Data Capture:</b> Applying database changes in sequence</li>
 *   <li><b>Workflow Processing:</b> Executing workflow steps in the correct order</li>
 * </ul>
 * <p>
 * <b>Key Concepts:</b>
 * <ul>
 *   <li><b>{@link #key}:</b> Identifies the entity/aggregate the message relates to (e.g., {@code "Order-123"},
 *       {@code "Customer-456"}). All messages with the same key are delivered in {@link #order} sequence.</li>
 *   <li><b>{@link #order}:</b> The sequential position of this message relative to others with the same key
 *       (e.g., event order 0, 1, 2, 3...). Typically corresponds to an event sequence number.</li>
 * </ul>
 * <p>
 * <b>Example - Event Sourcing:</b>
 * <pre>{@code
 * // Queue events for Order-123 in sequence
 * durableQueues.queueMessage(queueName, OrderedMessage.of(
 *     orderCreatedEvent,    // payload
 *     "Order-123",          // key = aggregateId
 *     0L                    // order = eventOrder
 * ));
 *
 * durableQueues.queueMessage(queueName, OrderedMessage.of(
 *     orderItemAddedEvent,
 *     "Order-123",
 *     1L
 * ));
 *
 * durableQueues.queueMessage(queueName, OrderedMessage.of(
 *     orderShippedEvent,
 *     "Order-123",
 *     2L
 * ));
 * }</pre>
 * <p>
 * <b>Ordering Guarantees:</b>
 * <p>
 * <table border="1">
 *   <tr>
 *     <th>Deployment</th>
 *     <th>Configuration</th>
 *     <th>Ordering Guarantee</th>
 *     <th>Notes</th>
 *   </tr>
 *   <tr>
 *     <td>Single Node</td>
 *     <td>Any</td>
 *     <td>✅ Yes (per key)</td>
 *     <td>Coordinated within the node by message fetcher</td>
 *   </tr>
 *   <tr>
 *     <td>Multi-Node</td>
 *     <td>{@link DurableQueues} directly</td>
 *     <td>❌ No</td>
 *     <td>Competing consumers have no cross-node coordination</td>
 *   </tr>
 *   <tr>
 *     <td>Multi-Node</td>
 *     <td>{@link Inbox}/{@link Outbox} with {@link MessageConsumptionMode#SingleGlobalConsumer}</td>
 *     <td>✅ Yes (per key, cluster-wide)</td>
 *     <td>Uses {@link FencedLock} for single active consumer with failover</td>
 *   </tr>
 * </table>
 * <p>
 * <b>Single-Node Processing:</b><br>
 * When running {@link DurableQueues} on a single node, even with multiple parallel processing threads
 * (configured via {@code parallelConsumers}), ordering is guaranteed. Both message fetching approaches
 * support this:
 * <ul>
 *   <li><b>Centralized Message Fetcher</b> (PostgreSQL with {@code useCentralizedMessageFetcher=true}):
 *       Uses a single polling thread that tracks in-process keys via {@code inProcessOrderedKeys}</li>
 *   <li><b>Traditional Message Fetcher</b> (MongoDB, or PostgreSQL with {@code useCentralizedMessageFetcher=false}):
 *       Each consumer thread tracks in-process keys via {@code orderedMessageDeliveryThreads} in
 *       {@link DefaultDurableQueueConsumer} and excludes those keys when fetching the next message</li>
 * </ul>
 * In both cases, messages with <b>different keys</b> can be processed in parallel across all worker threads,
 * while messages with the <b>same key</b> are processed sequentially.
 * <p>
 * <b>Multi-Node Processing - The Challenge:</b><br>
 * When running {@link DurableQueues} across multiple nodes (all sharing the same database):
 * <ul>
 *   <li>Each node's {@link DurableQueues} instance acts as a <b>competing consumer</b></li>
 *   <li>The {@code inProcessOrderedKeys} tracking only works within a single node</li>
 *   <li>Database locking prevents the <b>same message</b> from being consumed twice, but does NOT prevent
 *       <b>different messages with the same key</b> from being consumed by different nodes simultaneously</li>
 *   <li><b>Example:</b> Node 1 might process {@code Order-123:event-5} while Node 2 simultaneously
 *       processes {@code Order-123:event-3}, violating the ordering guarantee</li>
 * </ul>
 * <p>
 * <b>Solution for Multi-Node Ordered Processing:</b><br>
 * Use {@link Inbox} or {@link Outbox} configured with {@link MessageConsumptionMode#SingleGlobalConsumer}:
 * <ul>
 *   <li>A {@link FencedLock} ensures only ONE node in the cluster actively consumes at a time</li>
 *   <li>The active node can still use multiple parallel worker threads</li>
 *   <li>Other nodes remain on standby for automatic failover</li>
 *   <li>All ordering coordination happens in a single place via {@link CentralizedMessageFetcher}</li>
 * </ul>
 * <p>
 * <b>Important:</b><br>
 * Always design message handlers to be <b>idempotent</b> to handle potential duplicates or redeliveries,
 * especially in distributed systems where network partitions or node failures can cause edge cases.
 *
 * @see DurableQueues
 * @see CentralizedMessageFetcher
 * @see DefaultDurableQueueConsumer
 * @see Inbox
 * @see Outbox
 * @see MessageConsumptionMode#SingleGlobalConsumer
 * @see FencedLock
 */
public class OrderedMessage extends Message {
    /**
     * All messages sharing the same key will be delivered according to their {@link #order}<br>
     * An example of a message key is the id of the entity/aggregate the message relates to
     */
    public final String key;
    /**
     * Represent the order of the message relative to the {@link #key}.<br>
     * All messages sharing the same key, will be delivered according to their {@link #order}
     */
    public final long   order;

    /**
     * @param payload the message payload
     * @param key     the message key. All messages sharing the same key will be delivered according to their {@link #getOrder()}.
     * @param order   the order of the message relative to the {@link #getKey()}.
     */
    public OrderedMessage(Object payload, String key, long order) {
        super(payload);
        this.key = requireNonNull(key, "No message key provided");
        requireTrue(order >= 0, "order must be >= 0");
        this.order = order;
    }

    /**
     * @param payload  the message payload
     * @param key      the message key. All messages sharing the same key will be delivered according to their {@link #getOrder()}.
     * @param order    the order of the message relative to the {@link #getKey()}.
     * @param metaData the {@link MessageMetaData} associated with the message
     */
    public OrderedMessage(Object payload, String key, long order, MessageMetaData metaData) {
        super(payload, metaData);
        this.key = requireNonNull(key, "No message key provided");
        requireTrue(order >= 0, "order must be >= 0");
        this.order = order;
    }

    /**
     * Create a new {@link Message} and an empty {@link MessageMetaData}
     *
     * @param payload the message payload
     * @param key     the message key. All messages sharing the same key will be delivered according to their {@link #getOrder()}.
     * @param order   the order of the message relative to the {@link #getKey()}.
     * @return the new {@link Message}
     */
    public static OrderedMessage of(Object payload, String key, long order) {
        return new OrderedMessage(payload, key, order);
    }

    /**
     * Create a new {@link Message}
     *
     * @param payload  the message payload
     * @param key      the message key. All messages sharing the same key will be delivered according to their {@link #getOrder()}.
     * @param order    the order of the message relative to the {@link #getKey()}.
     * @param metaData the {@link MessageMetaData} associated with the message
     * @return the new {@link Message}
     */
    public static OrderedMessage of(Object payload, String key, long order, MessageMetaData metaData) {
        return new OrderedMessage(payload, key, order, metaData);
    }

    /**
     * All messages sharing the same key will be delivered according to their {@link #getOrder()}<br>
     * An example of a message key is the id of the entity the message relates to
     *
     * @return The message key
     */
    public String getKey() {
        return key;
    }

    /**
     * Represent the order of a message relative to the {@link #getKey()}.<br>
     * All messages sharing the same key will be delivered according to their {@link #getOrder()}
     *
     * @return the order of a message relative to the {@link #getKey()}
     */
    public long getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return "OrderedMessage{" +
                "payload-type=" + _1.getClass().getName() +
                ", key='" + key + '\'' +
                ", order=" + order +
                ", metaData=" + _2 +
                '}';
    }
}
