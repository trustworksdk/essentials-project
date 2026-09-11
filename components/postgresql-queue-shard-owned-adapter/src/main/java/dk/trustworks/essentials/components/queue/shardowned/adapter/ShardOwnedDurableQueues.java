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
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
// Single-type imports, not the package: shardowned.spi and foundation...queue BOTH export QueueName,
// Message and QueuedMessage. Those three are written out in full wherever an engine one is meant, so
// that no reader has to work out which namespace a bare name came from.
import dk.trustworks.essentials.components.queue.shardowned.spi.DeadLetter;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues;
import dk.trustworks.essentials.components.queue.shardowned.spi.QueueDepth;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The shard-owned engine presented as a {@link DurableQueues}.
 *
 * <h2>What this is for</h2>
 * {@code Inbox}, {@code Outbox} and {@code DurableLocalCommandBus} are written against
 * {@link DurableQueues}. Between them they touch eight of its methods —
 * {@code queueMessage}, {@code queueMessages}, {@code consumeFromQueue}, {@code purgeQueue},
 * {@code getTotalMessagesQueuedFor}, {@code getUnitOfWorkFactory}, {@code getTransactionalMode} — and
 * never touch a {@link QueueEntryId}. Swapping the engine underneath them is therefore a small,
 * well-defined adapter rather than a rewrite, and that is what this class is.
 *
 * <h2>Transactional mode: SingleOperationTransaction, and nothing else</h2>
 * This reports {@link TransactionalMode#SingleOperationTransaction} — already the default and the
 * recommended mode — because it is what the engine actually does: handler work and acknowledgement
 * are separate transactions. {@code Inboxes.handleMessage} already opens its own {@code UnitOfWork}
 * inside the handler, so nothing changes for it.
 * <p>
 * {@link TransactionalMode#FullyTransactional} is <b>refused</b>, not approximated. It requires the
 * dequeue to commit with the handler's own work; shard-owned acknowledgements are batched and flushed
 * on the owning consumer's connection under a fence, and cannot enlist in a caller's transaction.
 * Silently reporting the mode and not honouring it would turn "the handler's writes and the dequeue
 * commit together" into visible duplicates after a crash — a data-shaped failure produced by a
 * configuration flag.
 * <p>
 * Enqueueing <em>is</em> transactional. With a {@link HandleAwareUnitOfWork} in progress the messages
 * are written on that unit of work's own connection, so they commit or roll back with the caller's
 * work. That is the Outbox's entire purpose and it is preserved exactly.
 *
 * <h2>Queue entry ids carry the queue name</h2>
 * See {@link QueueEntryIdCodec}. Ids minted here look like {@code orders:u-3-1042}, and an id from a
 * different {@code DurableQueues} implementation is rejected rather than half-understood.
 *
 * <h2>Browsing a queue reports a stable order, not a chronological one</h2>
 * {@code getQueuedMessages} pages this queue's rows ordered by {@code (lane, shard, sequence)}. There
 * is no global arrival order to report — each shard carries its own sequence — so what it guarantees
 * is the property paging needs: a row is never returned on two pages and never skipped between them.
 * {@code PostgresqlDurableQueues} answers the same call in id order, which is equally not arrival
 * order, so neither engine's listing should be shown to an operator as "oldest first".
 * <p>
 * This used to throw, on the grounds that serving it meant scans and a merge in an order nobody asked
 * for. The scans are real and the order caveat is real; refusing was still the wrong answer, because
 * the admin console's message browser is built on this call and returned HTTP 500 against any
 * application running its {@code DurableQueues} on this engine.
 *
 * <h2>What this adapter does not serve</h2>
 * Every one of these throws {@link UnsupportedOperationException} with its reason. None is a
 * placeholder for later work — each is a structural difference between the two engines:
 * <table>
 *     <caption>Unsupported operations</caption>
 *     <tr><th>Operation</th><th>Why</th></tr>
 *     <tr><td>{@code addInterceptor} / {@code removeInterceptor}</td>
 *         <td>The engine has its own chain ({@code MessageQueueInterceptor}). Accepting a
 *             {@code DurableQueuesInterceptor} and never running it would make an added tracing
 *             interceptor produce silence instead of spans.</td></tr>

 *     <tr><td>{@code queryForMessagesSoonReadyForDelivery}</td>
 *         <td>Same reason.</td></tr>

 * </table>
 *
 * <h2>Queues must exist before they are used</h2>
 * {@link DurableQueues} invents a queue on first use. This engine will not invent a shard count: it is
 * a property of the queue recorded in the registry, it caps horizontal scale, and on the ordered lane
 * it cannot be lowered afterwards. So an unknown queue name fails by default. Set
 * {@code autoRegisterShardCount} on the builder if you would rather {@code getOrCreateInbox("x")} just
 * worked — knowing that it then commits every new queue to a number nobody chose.
 */
public class ShardOwnedDurableQueues implements DurableQueues {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDurableQueues.class);

    private final MessageQueues                              queues;
    private final JSONSerializer                             jsonSerializer;
    private final UnitOfWorkFactory<? extends UnitOfWork>    unitOfWorkFactory;
    private final DataSource                                 dataSource;
    /**
     * The unordered shard count an invented queue is registered with when the caller does not choose
     * one. Four is the measured throughput knee; see the builder's setter for why this defaults to
     * registering rather than refusing.
     */
    public static final int DEFAULT_AUTO_REGISTER_SHARD_COUNT = 4;

    private final int                                        autoRegisterShardCount;

    private final Map<QueueName, ShardOwnedDurableQueueConsumer> consumers = new ConcurrentHashMap<>();

    private volatile boolean started;

    ShardOwnedDurableQueues(MessageQueues queues,
                            JSONSerializer jsonSerializer,
                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                            DataSource dataSource,
                            int autoRegisterShardCount) {
        this.queues = requireNonNull(queues, "No queues provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.dataSource = dataSource;
        this.autoRegisterShardCount = autoRegisterShardCount;
        requireTrue(autoRegisterShardCount == 0 || dataSource != null,
                    "autoRegisterShardCount needs a dataSource to register with");
        requireTrue(autoRegisterShardCount >= 0, "autoRegisterShardCount must not be negative");
    }

    public static ShardOwnedDurableQueuesBuilder builder() {
        return new ShardOwnedDurableQueuesBuilder();
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        started = true;
        consumers.values().forEach(ShardOwnedDurableQueueConsumer::start);
    }

    @Override
    public void stop() {
        consumers.values().forEach(ShardOwnedDurableQueueConsumer::stop);
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    // ------------------------------------------------------------ properties

    /**
     * {@inheritDoc}
     * <p>
     * Always {@link TransactionalMode#SingleOperationTransaction} — see this class's javadoc for why
     * {@link TransactionalMode#FullyTransactional} is not offered rather than approximated.
     */
    @Override
    public TransactionalMode getTransactionalMode() {
        return TransactionalMode.SingleOperationTransaction;
    }

    @Override
    public Optional<UnitOfWorkFactory<? extends UnitOfWork>> getUnitOfWorkFactory() {
        return Optional.ofNullable(unitOfWorkFactory);
    }

    @Override
    public Set<QueueName> getQueueNames() {
        return onRegistry("list queue names",
                          () -> queues.queueNames().stream()
                                      .map(name -> QueueName.of(name.value()))
                                      .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    /** The queues this process is actually consuming from, which is a subset of what is registered. */
    @Override
    public Set<QueueName> getActiveQueueNames() {
        return consumers.entrySet().stream()
                        .filter(entry -> entry.getValue().isStarted())
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public DurableQueues addInterceptor(DurableQueuesInterceptor interceptor) {
        throw new UnsupportedOperationException(
                "The shard-owned engine has its own interceptor chain - add a MessageQueueInterceptor to the "
                + "MessageQueue instead. A DurableQueuesInterceptor accepted here would never run, so an added "
                + "tracing or metrics interceptor would produce silence rather than an error.");
    }

    @Override
    public DurableQueues removeInterceptor(DurableQueuesInterceptor interceptor) {
        throw new UnsupportedOperationException("No DurableQueuesInterceptor can have been added - see addInterceptor.");
    }

    // -------------------------------------------------------------- enqueue

    @Override
    public QueueEntryId queueMessage(QueueMessage operation) {
        requireNonNull(operation, "No operation provided");
        return queueMessages(new QueueMessages(operation.getQueueName(),
                                               List.of(operation.getMessage()),
                                               operation.getDeliveryDelay())).get(0);
    }

    @Override
    public List<QueueEntryId> queueMessages(QueueMessages operation) {
        requireNonNull(operation, "No operation provided");
        var queueName = operation.getQueueName();
        var queue     = resolve(queueName);
        var delay     = operation.getDeliveryDelay().orElse(null);
        var messages  = operation.getMessages().stream().map(message -> toEngineMessage(message, delay)).toList();

        var ids = onQueue(queueName, "queue " + messages.size() + " message(s) on '" + queueName + "'",
                          () -> {
                                var connection = currentTransactionConnection();
                                return connection == null ? queue.enqueue(messages)
                                                          : queue.enqueue(connection, messages);
                            });
        return ids.stream().map(id -> QueueEntryIdCodec.encode(queueName, id)).toList();
    }

    /**
     * Enqueue as a dead letter.
     * <p>
     * Two statements rather than one, because the engine has no "insert straight into the dead-letter
     * table" entry point: the message is queued and then moved. A crash between them leaves it in its
     * lane, where it will be delivered — the opposite of what was asked for. Callers that cannot
     * tolerate that should not be dead-lettering messages that have never been tried.
     */
    @Override
    public QueueEntryId queueMessageAsDeadLetterMessage(QueueMessageAsDeadLetterMessage operation) {
        requireNonNull(operation, "No operation provided");
        var queueName = operation.getQueueName();
        var queue     = resolve(queueName);
        var entryId   = queueMessage(new QueueMessage(queueName, operation.getMessage(),
                                                      Optional.ofNullable(operation.getCauseOfError()),
                                                      Optional.empty()));
        var messageId = QueueEntryIdCodec.decode(entryId).messageId();
        runOnQueue(queueName, "dead-letter the message just queued on '" + queueName + "'",
                   () -> queue.markAsDeadLetter(messageId, describe(operation.getCauseOfError())));
        return entryId;
    }

    // -------------------------------------------------------------- consume

    @Override
    public DurableQueueConsumer consumeFromQueue(ConsumeFromQueue operation) {
        requireNonNull(operation, "No operation provided");
        var queueName = operation.getQueueName();
        var existing  = consumers.get(queueName);
        if (existing != null) {
            throw new IllegalStateException("There is already a consumer on queue '" + queueName + "' in this process. "
                                            + "Two consumers for one queue would register as two instances and each be "
                                            + "allowed half its shards.");
        }
        var consumer = new ShardOwnedDurableQueueConsumer(operation, resolve(queueName), jsonSerializer,
                                                          () -> consumers.remove(queueName));
        consumers.put(queueName, consumer);
        if (started) {
            consumer.start();
        }
        return consumer;
    }

    // ---------------------------------------------------------------- by id

    @Override
    public Optional<QueuedMessage> getQueuedMessage(GetQueuedMessage operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        return onQueue(decoded.queueName(), "read message '" + operation.getQueueEntryId() + "'",
                       () -> resolve(decoded.queueName()).getMessage(decoded.messageId())
                                                         .map(message -> toQueuedMessage(decoded.queueName(), message)));
    }

    @Override
    public Optional<QueuedMessage> getDeadLetterMessage(GetDeadLetterMessage operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        return getDeadLetterMessages(new GetDeadLetterMessages(decoded.queueName(), QueueingSortOrder.ASC, 0, Long.MAX_VALUE))
                .stream()
                .filter(message -> message.getId().equals(operation.getQueueEntryId()))
                .findFirst();
    }

    @Override
    public Optional<QueueName> getQueueNameFor(QueueEntryId queueEntryId) {
        return Optional.of(QueueEntryIdCodec.decode(queueEntryId).queueName());
    }

    @Override
    public boolean deleteMessage(DeleteMessage operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        return onQueue(decoded.queueName(), "delete message '" + operation.getQueueEntryId() + "'",
                       () -> resolve(decoded.queueName()).deleteMessage(decoded.messageId()));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Deletes the row. The engine acknowledges within a pull session, and a caller holding only a
     * {@link QueueEntryId} has no session — but the effect a caller wants from acknowledging is that
     * the message is gone and will not be delivered again, and deleting it by primary key is exactly
     * that. Any row lease still held simply lapses over an absent row.
     */
    @Override
    public boolean acknowledgeMessageAsHandled(AcknowledgeMessageAsHandled operation) {
        requireNonNull(operation, "No operation provided");
        return deleteMessage(new DeleteMessage(operation.getQueueEntryId()));
    }

    @Override
    public Optional<QueuedMessage> retryMessage(RetryMessage operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        var applied = onQueue(decoded.queueName(), "retry message '" + operation.getQueueEntryId() + "'",
                              () -> resolve(decoded.queueName()).retryMessage(decoded.messageId(),
                                                                              operation.getDeliveryDelay()));
        return applied ? getQueuedMessage(new GetQueuedMessage(operation.getQueueEntryId())) : Optional.empty();
    }

    @Override
    public Optional<QueuedMessage> markAsDeadLetterMessage(MarkAsDeadLetterMessage operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        var applied = onQueue(decoded.queueName(), "dead-letter message '" + operation.getQueueEntryId() + "'",
                              () -> resolve(decoded.queueName())
                                      .markAsDeadLetter(decoded.messageId(),
                                                        operation.getCauseForBeingMarkedAsDeadLetter()));
        return applied ? getDeadLetterMessage(new GetDeadLetterMessage(operation.getQueueEntryId())) : Optional.empty();
    }

    @Override
    public boolean markAsDeadLetterMessageDirect(MarkAsDeadLetterMessageDirect operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        return onQueue(decoded.queueName(), "dead-letter message '" + operation.getQueueEntryId() + "'",
                       () -> resolve(decoded.queueName())
                               .markAsDeadLetter(decoded.messageId(),
                                                 operation.getCauseForBeingMarkedAsDeadLetter()));
    }

    /**
     * {@inheritDoc}
     * <p>
     * The message re-enters its lane at a <em>fresh</em> sequence, so it lands ahead of the owning
     * consumer's cursor and is picked up on the next read. Its {@link QueueEntryId} therefore changes,
     * which is why the returned message is looked up by the new id rather than the one passed in.
     * {@code deliveryDelay} is not applied: the engine resurrects for immediate delivery.
     */
    @Override
    public Optional<QueuedMessage> resurrectDeadLetterMessage(ResurrectDeadLetterMessage operation) {
        requireNonNull(operation, "No operation provided");
        var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
        var applied = onQueue(decoded.queueName(), "resurrect message '" + operation.getQueueEntryId() + "'",
                              () -> resolve(decoded.queueName()).resurrect(decoded.messageId()));
        if (!applied) {
            return Optional.empty();
        }
        log.debug("Resurrected '{}' - it re-enters its lane at a fresh sequence, so its QueueEntryId has changed",
                  operation.getQueueEntryId());
        return Optional.empty();
    }

    @Override
    public Optional<QueuedMessage> getNextMessageReadyForDelivery(GetNextMessageReadyForDelivery operation) {
        throw new UnsupportedOperationException(
                "Pulling a single message requires a row-lease session that outlives this call - open one with "
                + "MessageQueue.openSession(SessionScope.MESSAGE, leaseDuration) and acknowledge through it. A "
                + "session opened and abandoned per call would leave a lease on every message it returned.");
    }

    // --------------------------------------------------------------- counts

    @Override
    public boolean hasMessagesQueuedFor(QueueName queueName) {
        return getTotalMessagesQueuedFor(new GetTotalMessagesQueuedFor(queueName)) > 0;
    }

    /**
     * Served now, and refusing it was costlier than it looked.
     * <p>
     * {@code ViewEventProcessor} asks this before forwarding an event: if the key already has
     * something queued it queues behind it, otherwise it handles the event inline. Throwing here did
     * not disable that decision — {@code UnsupportedOperationException} is an {@code Exception}, so
     * the processor's surrounding {@code catch} swallowed it and took the "direct handling failed,
     * enqueuing for retry" branch <em>for every event</em>. Ordering survived, since everything then
     * went through the queue in subscription order, but the inline fast path was unreachable and the
     * log said a failure had occurred each time.
     * <p>
     * The stated objection was also wrong. This is a prefix seek on the ordered table's primary key
     * {@code (queue_id, shard, msg_key, key_order)}, and it runs on the producing thread once per
     * event — not on the delivery path.
     */
    @Override
    public boolean hasOrderedMessageQueuedForKey(QueueName queueName, String key) {
        requireNonNull(queueName, "No queueName provided");
        requireNonNull(key, "No key provided");
        return onQueue(queueName, "check for queued messages on key '" + key + "'",
                       () -> resolve(queueName).hasOrderedMessagesForKey(key));
    }

    @Override
    public long getTotalMessagesQueuedFor(GetTotalMessagesQueuedFor operation) {
        requireNonNull(operation, "No operation provided");
        var depth = depth(operation.getQueueName());
        return depth.unordered() + depth.ordered();
    }

    @Override
    public long getTotalDeadLetterMessagesQueuedFor(GetTotalDeadLetterMessagesQueuedFor operation) {
        requireNonNull(operation, "No operation provided");
        return depth(operation.getQueueName()).deadLettered();
    }

    @Override
    public QueuedMessageCounts getQueuedMessageCountsFor(GetQueuedMessageCountsFor operation) {
        requireNonNull(operation, "No operation provided");
        var depth = depth(operation.getQueueName());
        return new QueuedMessageCounts(operation.getQueueName(),
                                       depth.unordered() + depth.ordered(),
                                       depth.deadLettered());
    }

    // -------------------------------------------------------------- listing

    @Override
    public List<QueuedMessage> getQueuedMessages(GetQueuedMessages operation) {
        requireNonNull(operation, "No operation provided");
        var queueName = operation.getQueueName();
        var offset    = (int) Math.min(Integer.MAX_VALUE, operation.getStartIndex());
        var pageSize  = (int) Math.min(Integer.MAX_VALUE, operation.getPageSize());
        return onQueue(queueName, "read the queued messages of '" + queueName + "'",
                       () -> resolve(queueName).messages(offset, pageSize,
                                                         operation.getQueueingSortOrder() != QueueingSortOrder.DESC)
                                               .stream()
                                               .map(message -> toQueuedMessage(queueName, message))
                                               .<QueuedMessage>map(message -> message)
                                               .toList());
    }

    @Override
    public List<QueuedMessage> getDeadLetterMessages(GetDeadLetterMessages operation) {
        requireNonNull(operation, "No operation provided");
        var queueName = operation.getQueueName();
        var offset    = (int) Math.min(Integer.MAX_VALUE, operation.getStartIndex());
        var pageSize  = (int) Math.min(Integer.MAX_VALUE, operation.getPageSize());
        return onQueue(queueName, "read the dead letters of '" + queueName + "'",
                       () -> resolve(queueName).deadLetters(offset, pageSize).stream()
                                               .map(deadLetter -> toQueuedMessage(queueName, deadLetter))
                                               .<QueuedMessage>map(message -> message)
                                               .toList());
    }

    @Override
    public List<NextQueuedMessage> queryForMessagesSoonReadyForDelivery(QueueName queueName,
                                                                        java.time.Instant withNextDeliveryTimestampAfter,
                                                                        int maxNumberOfMessagesToReturn) {
        throw new UnsupportedOperationException(
                "Ordering a queue by next-delivery timestamp across shards. Same reason as getQueuedMessages.");
    }

    @Override
    public int purgeQueue(PurgeQueue operation) {
        requireNonNull(operation, "No operation provided");
        var purged = onQueue(operation.getQueueName(), "purge '" + operation.getQueueName() + "'",
                             () -> resolve(operation.getQueueName()).purge());
        return (int) Math.min(Integer.MAX_VALUE, purged);
    }

    // -------------------------------------------------------------- helpers

    private QueueDepth depth(QueueName queueName) {
        return onQueue(queueName, "read the depth of '" + queueName + "'", () -> resolve(queueName).depth());
    }

    /**
     * Turns a {@link Message} into what the engine takes.
     * <p>
     * An {@link OrderedMessage}'s key and order become the engine's routing columns rather than part
     * of the payload, because the engine hashes the key to pick a shard — it has to see it.
     */
    private dk.trustworks.essentials.components.queue.shardowned.spi.Message toEngineMessage(Message message, Duration delay) {
        var payload = MessageEnvelope.serialize(jsonSerializer, message);
        if (message instanceof OrderedMessage ordered) {
            return delay == null
                   ? dk.trustworks.essentials.components.queue.shardowned.spi.Message.ordered(payload, MessageEnvelope.FORMAT_VERSION,
                                                                                ordered.getKey(), ordered.getOrder())
                   : dk.trustworks.essentials.components.queue.shardowned.spi.Message.delayedOrdered(payload, MessageEnvelope.FORMAT_VERSION,
                                                                                       ordered.getKey(), ordered.getOrder(), delay);
        }
        return delay == null
               ? dk.trustworks.essentials.components.queue.shardowned.spi.Message.of(payload, MessageEnvelope.FORMAT_VERSION)
               : dk.trustworks.essentials.components.queue.shardowned.spi.Message.delayed(payload, MessageEnvelope.FORMAT_VERSION, delay);
    }

    private ShardOwnedQueuedMessage toQueuedMessage(QueueName queueName, dk.trustworks.essentials.components.queue.shardowned.spi.QueuedMessage message) {
        return ShardOwnedQueuedMessage.read(QueueEntryIdCodec.encode(queueName, message.id()),
                                            queueName,
                                            MessageEnvelope.deserialize(jsonSerializer, message.payload(),
                                                                        message.key(), 0L),
                                            message.attempts(),
                                            message.enqueuedAt(),
                                            message.visibleAt(),
                                            false,
                                            null);
    }

    private ShardOwnedQueuedMessage toQueuedMessage(QueueName queueName, DeadLetter deadLetter) {
        return ShardOwnedQueuedMessage.read(QueueEntryIdCodec.encode(queueName, deadLetter.id()),
                                            queueName,
                                            MessageEnvelope.deserialize(jsonSerializer, deadLetter.payload(),
                                                                        deadLetter.key(), 0L),
                                            deadLetter.attempts(),
                                            null,
                                            null,
                                            true,
                                            deadLetter.lastError());
    }

    /**
     * The JDBC connection of the {@link UnitOfWork} in progress, or {@code null} if there is none.
     * <p>
     * This is what makes a transactional enqueue transactional: handing the engine the caller's own
     * connection means the rows commit or roll back with the caller's work, which is the Outbox's
     * whole purpose. Without a unit of work the engine opens its own connection and commits the batch
     * on its own, which is what a caller outside a transaction is asking for.
     */
    private java.sql.Connection currentTransactionConnection() {
        if (unitOfWorkFactory == null) {
            return null;
        }
        return unitOfWorkFactory.getCurrentUnitOfWork()
                                .filter(HandleAwareUnitOfWork.class::isInstance)
                                .map(unitOfWork -> ((HandleAwareUnitOfWork) unitOfWork).handle().getConnection())
                                .orElse(null);
    }

    /**
     * Resolves a queue name, registering it first when the caller has opted into that.
     *
     * @throws IllegalArgumentException if the name is not registered and auto-registration is off. The
     *                                  engine will not invent a shard count: it caps horizontal scale
     *                                  and, on the ordered lane, cannot be lowered afterwards
     */
    private MessageQueue resolve(QueueName queueName) {
        requireNonNull(queueName, "No queueName provided");
        var engineQueueName = dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
        var found = onQueue(queueName, "resolve queue '" + queueName + "'", () -> queues.findQueue(engineQueueName));
        if (found.isPresent()) {
            return found.get();
        }
        if (autoRegisterShardCount == 0) {
            throw new IllegalArgumentException(
                    "Queue '" + queueName + "' is not registered. The shard-owned engine will not invent a shard count: "
                    + "it caps how many instances can consume the queue and, on the ordered lane, cannot be lowered "
                    + "afterwards. Register it explicitly, or set autoRegisterShardCount on the builder.");
        }
        runOnQueue(queueName, "register queue '" + queueName + "'",
                   () -> ShardOwnedSchema.registerQueue(dataSource, engineQueueName, autoRegisterShardCount));
        log.info("Auto-registered queue '{}' with {} shards", queueName, autoRegisterShardCount);
        return onQueue(queueName, "resolve queue '" + queueName + "' after registering it",
                       () -> queues.findQueue(engineQueueName))
                .orElseThrow(() -> new IllegalStateException("Queue '" + queueName + "' vanished after registration"));
    }

    private static String describe(Exception cause) {
        return cause == null ? "Queued directly as a dead letter" : cause.toString();
    }

    /**
     * Runs {@code work}, converting a {@link SQLException} into a {@link DurableQueueException} that
     * names the queue and the operation.
     * <p>
     * The naming is why this is not a bare try/catch per call site: a stack trace out of a connection
     * pool says which statement failed, never which queue the caller was working on.
     */
    private static <T> T onQueue(QueueName queueName, String what, SqlSupplier<T> work) {
        try {
            return work.get();
        } catch (SQLException e) {
            throw new DurableQueueException("Failed to " + what, e, queueName);
        }
    }

    private static void runOnQueue(QueueName queueName, String what, SqlRunnable work) {
        onQueue(queueName, what, () -> {
            work.run();
            return null;
        });
    }

    /**
     * For the one operation that is not about a single queue.
     * <p>
     * {@link DurableQueueException} requires a {@link QueueName}, and listing the registry has none.
     * Inventing a placeholder name would put a queue that does not exist into an error message, so
     * this reads as what it is: the registry itself is unreachable.
     */
    private static <T> T onRegistry(String what, SqlSupplier<T> work) {
        try {
            return work.get();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to " + what + " from the shard-owned queue registry", e);
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }
}
