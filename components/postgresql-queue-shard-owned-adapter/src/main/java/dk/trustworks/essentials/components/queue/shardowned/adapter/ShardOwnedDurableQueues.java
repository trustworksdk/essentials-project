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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport;
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
import dk.trustworks.essentials.shared.Exceptions;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.interceptor.DefaultInterceptorChain.sortInterceptorsByOrder;
import static dk.trustworks.essentials.shared.interceptor.InterceptorChain.newInterceptorChainForOperation;

/**
 * The shard-owned engine presented as a {@link DurableQueues}.
 *
 * <h2>What this is for</h2>
 * {@code Inbox}, {@code Outbox} and {@code DurableLocalCommandBus} are written against
 * {@link DurableQueues}. Between them they touch a handful of its methods —
 * {@code queueMessage}, {@code queueMessages}, {@code consumeFromQueue}, {@code purgeQueue},
 * {@code getTotalMessagesQueuedFor}, {@code getUnitOfWorkFactory} — and
 * never touch a {@link QueueEntryId}. Swapping the engine underneath them is therefore a small,
 * well-defined adapter rather than a rewrite, and that is what this class is.
 *
 * <h2>Transactions</h2>
 * Handler work and acknowledgement are separate transactions — the only model {@link DurableQueues}
 * has had since 0.60 retired {@code TransactionalMode}. Shard-owned acknowledgements are batched and
 * flushed on the owning consumer's connection under a fence, and never enlist in a caller's
 * transaction. {@code Inboxes.handleMessage} opens its own {@code UnitOfWork} inside the handler, so
 * nothing changes for it.
 * <p>
 * Enqueueing <em>is</em> transactional. The enqueue runs inside
 * {@link UnitOfWorkFactory#withUnitOfWork(dk.trustworks.essentials.shared.functional.CheckedFunction)}, as
 * {@code PostgresqlDurableQueues} does, and the messages are written on that {@link HandleAwareUnitOfWork}'s own
 * connection, so they commit or roll back with the caller's work. That joins an Essentials {@code UnitOfWork} in
 * progress <em>and</em> a plain Spring transaction ({@code @Transactional}, {@code TransactionTemplate}) when the
 * factory is Spring-aware. Until 0.60.1 only an already-bound {@code UnitOfWork} was joined, so under a plain Spring
 * transaction the enqueue committed on its own and survived a rollback. Outside any transaction the factory starts a
 * unit of work and commits it. Built without a {@link UnitOfWorkFactory}, every enqueue commits on its own, and the
 * builder says so once at WARN.
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
 * <h2>Interceptors run here, not on the engine's chain</h2>
 * A {@link DurableQueuesInterceptor} added to this adapter is run by this adapter, around its own
 * methods — the same {@code InterceptorChain} machinery {@code PostgresqlDurableQueues} uses, so an
 * interceptor written against either engine behaves the same way on both.
 * <p>
 * This does <em>not</em> bridge onto the engine's own {@code MessageQueueInterceptor} chain, and
 * bridging was the wrong shape: that chain carries two operations, {@code EnqueueMessages} and
 * {@code HandleMessage}, against this interface's twenty-one, so a bridge would have carried two of
 * them and silently dropped the rest. The two chains are independent and an application may use both;
 * an interceptor registered on the {@code MessageQueue} sees engine operations, one registered here
 * sees {@code DurableQueues} operations.
 * <p>
 * Two consequences worth knowing before writing one:
 * <ul>
 *     <li><b>{@link GetNextMessageReadyForDelivery} is never intercepted</b>, because it throws
 *         (below). Together with {@code ReleaseUndeliveredMessage} (next item) it is an operation an interceptor cannot observe here.</li>
 *     <li><b>{@code ReleaseUndeliveredMessage} is not intercepted as such</b>: this class inherits the default of {@link DurableQueues},
 *         which falls back to {@code retryMessage}, so an interceptor sees a {@code RetryMessage} instead.</li>
 *     <li><b>A {@code HandleQueuedMessage} interceptor receives the partial message.</b> On the
 *         delivery path the engine hands over {@code (key, payload, payloadType)} only, so
 *         {@link ShardOwnedQueuedMessage#getId()} and {@code getTotalDeliveryAttempts()} throw rather
 *         than report a made-up value. {@code getQueueName()}, {@code getMessage()} and the payload
 *         are all present — which is what the framework's own
 *         {@code RecordExecutionTimeDurableQueueInterceptor} tags with. An interceptor that reaches
 *         for the id fails the delivery, and the stack trace will look like the framework breaking
 *         rather than like a partial message.</li>
 * </ul>
 *
 * <h2>What this adapter does not serve</h2>
 * Every one of these throws {@link UnsupportedOperationException} with its reason. None is a
 * placeholder for later work — each is a structural difference between the two engines:
 * <table>
 *     <caption>Unsupported operations</caption>
 *     <tr><th>Operation</th><th>Why</th></tr>
 *     <tr><td>{@code queryForMessagesSoonReadyForDelivery}</td>
 *         <td>Orders a queue by next-delivery timestamp across every shard of both lanes. That is a
 *             different question from paging by id, which {@code getQueuedMessages} answers: there is
 *             no index that produces it and no single sequence to merge on.</td></tr>
 *     <tr><td>{@code getNextMessageReadyForDelivery}</td>
 *         <td>Pulling one message requires a row-lease session that outlives the call. The engine has
 *             one — {@code MessageQueue.openSession(SessionScope.MESSAGE, leaseDuration)} — but a
 *             session opened and abandoned per call would leave a lease on every message it
 *             returned.</td></tr>
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

    private final       MessageQueues                           queues;
    private final       JSONSerializer                          jsonSerializer;
    private final       UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;
    private final       DataSource                              dataSource;
    /**
     * The unordered shard count an invented queue is registered with when the caller does not choose
     * one. Four is the measured throughput knee; see the builder's setter for why this defaults to
     * registering rather than refusing.
     */
    public static final int                                     DEFAULT_AUTO_REGISTER_SHARD_COUNT = 4;

    private final int autoRegisterShardCount;

    private final Map<QueueName, ShardOwnedDurableQueueConsumer> consumers = new ConcurrentHashMap<>();

    private final List<DurableQueuesInterceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * Whether a consumer may start: once this reads false, neither {@link #start()}'s loop nor {@link #doConsumeFromQueue}
     * starts another consumer, and one whose start was already under way when it went false is stopped once that start
     * returns. Set by {@link #start()} before it starts the consumers and cleared by {@link #stop()} and a failed start
     * before they stop them. {@link #doConsumeFromQueue} registers a consumer before it reads this; it and {@link #start()}'s
     * loop read it before each consumer's start, and again once that start has returned - the second time, when it reads
     * false, under the consumer's lifecycle lock ({@link #stopConsumerStartedPastAStop}), so a {@link #start()} that set it
     * again meanwhile is not undone.
     * <p>
     * It does not say that no consumer is running. While {@link #stop()} or a failed start is stopping the consumers, the
     * ones it has not reached yet are still running although this already reads false. A consumer whose start was still
     * subscribing when the stop gave up waiting for it ({@link ShardOwnedDurableQueueConsumer#stop()}) is not started, but
     * the lanes its queue's {@code consume()} has already started may deliver until that start has stopped them. And a
     * critical error escaping {@link #start()} leaves this reading true over whatever that start had started.
     */
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * What a test does as a consumer is about to be started ({@link StartHook}); {@link StartHook#NONE} unless a test passed
     * one to the constructor. Fixed at construction, so nothing can swap it while consumers start.
     */
    private final StartHook startHook;

    private final AtomicBoolean warnedAboutUnitOfWorkWithoutConnection = new AtomicBoolean();

    private final EnqueueOutsideUnitOfWorkGuard enqueueOutsideUnitOfWorkGuard = new EnqueueOutsideUnitOfWorkGuard(ShardOwnedDurableQueues.class);

    ShardOwnedDurableQueues(MessageQueues queues,
                            JSONSerializer jsonSerializer,
                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                            DataSource dataSource,
                            int autoRegisterShardCount) {
        this(queues, jsonSerializer, unitOfWorkFactory, dataSource, autoRegisterShardCount, StartHook.NONE);
    }

    /**
     * @param startHook what a test does as a consumer is about to be started ({@link StartHook}); {@link StartHook#NONE}
     *                  outside tests
     */
    ShardOwnedDurableQueues(MessageQueues queues,
                            JSONSerializer jsonSerializer,
                            UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                            DataSource dataSource,
                            int autoRegisterShardCount,
                            StartHook startHook) {
        this.startHook = requireNonNull(startHook, "No startHook provided");
        this.queues = requireNonNull(queues, "No queues provided");
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.dataSource = dataSource;
        this.autoRegisterShardCount = autoRegisterShardCount;
        requireTrue(autoRegisterShardCount == 0 || dataSource != null,
                    "autoRegisterShardCount needs a dataSource to register with");
        requireTrue(autoRegisterShardCount >= 0, "autoRegisterShardCount must not be negative");
        if (unitOfWorkFactory == null) {
            log.warn("ShardOwnedDurableQueues was built without a UnitOfWorkFactory: every enqueue commits on its own connection "
                     + "and is NOT rolled back with the caller's transaction, so an Outbox on this engine is not transactional. "
                     + "Call setUnitOfWorkFactory(...) on the builder - from 0.61 the builder will require one.");
            EssentialsRunReport.addToCurrent(EssentialsRunReport.Finding.warn(
                    "ShardOwnedDurableQueues",
                    "built without a UnitOfWorkFactory: every enqueue commits on its own connection and is NOT rolled back with the caller's transaction, "
                            + "so an Outbox on this engine is not transactional",
                    "Call setUnitOfWorkFactory(...) on ShardOwnedDurableQueuesBuilder - from 0.61 the builder requires one"));
        }
    }

    public static ShardOwnedDurableQueuesBuilder builder() {
        return new ShardOwnedDurableQueuesBuilder();
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * {@inheritDoc}
     * <p>
     * Starts every consumer or none. A consumer's start that fails with anything but a critical error
     * ({@link Exceptions#isCriticalError}) - a {@link DurableQueueException} for a failed subscription, or a
     * {@code QueueObserver}'s non-critical {@code Error} for an acquisition, say - stops every consumer, each one even when
     * an earlier one's stop throws, and escapes as it was thrown, with every stop's failure added to it as suppressed
     * ({@link Exceptions#forEachCollectingFailures}). This then reports itself not started, so a later {@code start()}
     * runs again. It used to start them one after the other and stop at the first throw, which left the consumers after
     * it unstarted while this reported itself started. A critical error - a start's, escaping untouched with nothing
     * stopped, or a stop's, ending the stopping at once with the start's failure added to it as suppressed - leaves this
     * started, so a {@link #stop()} still stops what it left running.
     * <p>
     * Does nothing when this is already started, as {@code PostgresqlDurableQueues.start()} does: starting the consumers
     * again would, on a failure, stop the ones that had been running all along. A consumer stopped through its own
     * {@link DurableQueueConsumer#stop()} is started again by its own {@link DurableQueueConsumer#start()}, or by this
     * after a {@link #stop()}.
     * <p>
     * This reports itself started from the moment the start begins, so a consumer {@code consumeFromQueue} adds
     * meanwhile starts itself rather than being missed by the loop below. A failed start reports itself not started
     * before it stops the consumers, so one added while it stops them does not start.
     * <p>
     * A {@link #stop()} called while this is still starting the consumers ends the start: this starts no consumer once it
     * reads itself stopped, and stops the one whose start returned after the stop, as {@code consumeFromQueue} does
     * ({@link #stopConsumerStartedPastAStop}). The start used to go on through every consumer, so a stop that came while
     * one consumer's start was held up - in its queue's {@code consume()}, by a network partition, say - and that gave up
     * waiting for it, returned before the start reached the consumers after it; the start then started them, consuming
     * while this reported itself not started, with no stop to come if that stop was the application's shutdown.
     */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            for (var consumer : consumers.values()) {
                if (!started.get()) {
                    break;
                }
                startHook.beforeStartingConsumer(consumer.queueName());
                consumer.start();
                if (stopConsumerStartedPastAStop(consumer)) {
                    break;
                }
            }
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            started.set(false);
            Exceptions.forEachCollectingFailures(consumers.values(), ShardOwnedDurableQueueConsumer::stop, e);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Every consumer is stopped even when an earlier one's stop throws - a {@code QueueObserver} failing for a release,
     * say, with a {@code RuntimeException} or a non-critical {@code Error} such as a test double's {@code AssertionError} -
     * and the first such failure is thrown, as it was thrown, once all of them have stopped, the others added to it as
     * suppressed ({@link Exceptions#forEachThenThrowFirst}). Stopping at the first throw left the consumers after it
     * running, and this reporting itself started. Only a critical error ({@link Exceptions#isCriticalError}) ends the
     * stopping at once; this reports itself stopped then too.
     * <p>
     * This reports itself stopped from the moment the stop begins, so a consumer {@code consumeFromQueue} adds while the
     * consumers are being stopped does not start.
     */
    @Override
    public void stop() {
        started.set(false);
        Exceptions.forEachThenThrowFirst(consumers.values(), ShardOwnedDurableQueueConsumer::stop);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    // ------------------------------------------------------------ properties

    @Override
    public Optional<UnitOfWorkFactory<? extends UnitOfWork>> getUnitOfWorkFactory() {
        return Optional.ofNullable(unitOfWorkFactory);
    }

    /**
     * Should {@code queueMessage}/{@code queueMessages} fail when called outside a {@link UnitOfWork} or Spring transaction?
     * Default {@code false}: such an enqueue commits on its own, and each call site's first one is logged at WARN. In Spring Boot:
     * {@value EnqueueOutsideUnitOfWorkGuard#REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY}. See {@link EnqueueOutsideUnitOfWorkGuard}.
     * Without a {@link UnitOfWorkFactory} every enqueue is outside one, so requiring it then fails every enqueue - the
     * builder refuses that combination.
     *
     * @param requireActiveUnitOfWorkForEnqueue {@code true} to throw {@link NoActiveUnitOfWorkException} instead
     */
    public void setRequireActiveUnitOfWorkForEnqueue(boolean requireActiveUnitOfWorkForEnqueue) {
        enqueueOutsideUnitOfWorkGuard.setRequireActiveUnitOfWork(requireActiveUnitOfWorkForEnqueue);
    }

    /**
     * @return whether an enqueue outside a {@link UnitOfWork} or Spring transaction fails - see
     * {@link #setRequireActiveUnitOfWorkForEnqueue(boolean)}
     */
    public boolean isRequireActiveUnitOfWorkForEnqueue() {
        return enqueueOutsideUnitOfWorkGuard.isRequireActiveUnitOfWork();
    }

    @Override
    public Set<QueueName> getQueueNames() {
        return onRegistry("list queue names",
                          () -> queues.queueNames().stream()
                                      .map(name -> QueueName.of(name.value()))
                                      .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    /**
     * The queues this process is actually consuming from, which is a subset of what is registered.
     */
    @Override
    public Set<QueueName> getActiveQueueNames() {
        return consumers.entrySet().stream()
                        .filter(entry -> entry.getValue().isStarted())
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * {@inheritDoc}
     * <p>
     * The interceptor is run by this adapter around its own operations — see this class's javadoc for
     * what that does and does not reach, in particular what a {@code HandleQueuedMessage} interceptor
     * may read off the message it is given.
     */
    @Override
    public DurableQueues addInterceptor(DurableQueuesInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        log.info("Adding interceptor: {}", interceptor);
        interceptor.setDurableQueues(this);
        interceptors.add(interceptor);
        sortInterceptorsByOrder(interceptors);
        return this;
    }

    @Override
    public DurableQueues removeInterceptor(DurableQueuesInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        log.info("Removing interceptor: {}", interceptor);
        interceptors.remove(interceptor);
        sortInterceptorsByOrder(interceptors);
        return this;
    }

    /**
     * Access to the configured {@link DurableQueuesInterceptor}'s
     *
     * @return read-only list of the configured {@link DurableQueuesInterceptor}'s, in the order they
     * run
     */
    public List<DurableQueuesInterceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }

    // -------------------------------------------------------------- enqueue

    @Override
    public QueueEntryId queueMessage(QueueMessage operation) {
        requireNonNull(operation, "No operation provided");
        enqueueOutsideUnitOfWorkGuard.checkEnqueue(operation.getQueueName(), unitOfWorkFactory);
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doQueueMessages(operation.getQueueName(),
                                                                     List.of(operation.getMessage()),
                                                                     operation.getDeliveryDelay().orElse(null)).get(0))
                .proceed();
    }

    @Override
    public List<QueueEntryId> queueMessages(QueueMessages operation) {
        requireNonNull(operation, "No operation provided");
        enqueueOutsideUnitOfWorkGuard.checkEnqueue(operation.getQueueName(), unitOfWorkFactory);
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doQueueMessages(operation.getQueueName(),
                                                                     operation.getMessages(),
                                                                     operation.getDeliveryDelay().orElse(null)))
                .proceed();
    }

    /**
     * The enqueue itself, with no interception of its own.
     * <p>
     * Each public entry point runs the chain for the operation the caller actually named and then
     * lands here. Routing {@code queueMessage} through {@code queueMessages} instead would show an
     * interceptor two operations for one call — and a metrics interceptor would then count every
     * enqueue twice.
     */
    private List<QueueEntryId> doQueueMessages(QueueName queueName, List<? extends Message> messagesToQueue, Duration delay) {
        var queue    = resolve(queueName);
        var messages = messagesToQueue.stream().map(message -> toEngineMessage(message, delay)).toList();

        var what     = "queue " + messages.size() + " message(s) on '" + queueName + "'";

        List<MessageId> ids;
        if (unitOfWorkFactory == null) {
            ids = onQueue(queueName, what, () -> queue.enqueue(messages));
        } else {
            try {
                // withUnitOfWork, not getCurrentUnitOfWork(): it joins an Essentials UnitOfWork already bound AND a plain
                // Spring transaction (@Transactional / TransactionTemplate) nothing has bound one to yet - the Spring-aware
                // factories create one for that transaction. Outside any transaction it starts a UnitOfWork and commits it.
                ids = unitOfWorkFactory.withUnitOfWork(unitOfWork -> unitOfWork instanceof HandleAwareUnitOfWork handleAwareUnitOfWork
                                                                     ? onQueue(queueName, what, () -> queue.enqueue(handleAwareUnitOfWork.handle().getConnection(), messages))
                                                                     : enqueueOnOwnConnection(queue, queueName, what, messages, unitOfWork));
            } catch (UnitOfWorkException e) {
                // withUnitOfWork wraps whatever the enqueue threw. The queue-naming DurableQueueException is what this
                // adapter has always thrown for a failed enqueue, so callers keep getting it.
                if (e.getCause() instanceof DurableQueueException durableQueueException) {
                    throw durableQueueException;
                }
                throw e;
            }
        }
        return ids.stream().map(id -> QueueEntryIdCodec.encode(queueName, id)).toList();
    }

    /**
     * The unit of work in progress carries no JDBC connection (it is not a {@link HandleAwareUnitOfWork} - a MongoDB
     * one, say), so the enqueue cannot join it and commits on the engine's own connection. Said once, at WARN: it is a
     * wiring mistake the application can fix, and a rollback will not undo these rows.
     */
    private List<MessageId> enqueueOnOwnConnection(MessageQueue queue,
                                                   QueueName queueName,
                                                   String what,
                                                   List<dk.trustworks.essentials.components.queue.shardowned.spi.Message> messages,
                                                   UnitOfWork unitOfWork) {
        if (warnedAboutUnitOfWorkWithoutConnection.compareAndSet(false, true)) {
            log.warn("[{}] The UnitOfWork in progress ({}) is not a HandleAwareUnitOfWork, so the shard-owned engine cannot write "
                     + "on its connection: this and every later such enqueue commits on its own and is NOT rolled back with the "
                     + "caller's work. Configure a HandleAwareUnitOfWorkFactory (JDBI) over the engine's DataSource. Logged once.",
                     queueName, unitOfWork.getClass().getName());
        }
        return onQueue(queueName, what, () -> queue.enqueue(messages));
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
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var queueName = operation.getQueueName();
                                                   var queue     = resolve(queueName);
                                                   var entryId = doQueueMessages(queueName,
                                                                                 List.of(operation.getMessage()),
                                                                                 null).get(0);
                                                   var messageId = QueueEntryIdCodec.decode(entryId).messageId();
                                                   runOnQueue(queueName, "dead-letter the message just queued on '" + queueName + "'",
                                                              () -> queue.markAsDeadLetter(messageId, describe(operation.getCauseOfError())));
                                                   return entryId;
                                               })
                .proceed();
    }

    // -------------------------------------------------------------- consume

    @Override
    public DurableQueueConsumer consumeFromQueue(ConsumeFromQueue operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doConsumeFromQueue(operation))
                .proceed();
    }

    /**
     * Registers the consumer, and starts it when this is started.
     * <p>
     * A start that fails with anything but a critical error ({@link Exceptions#isCriticalError}) retires the consumer
     * ({@link ShardOwnedDurableQueueConsumer#retire()}) and deregisters it before its failure escapes, so
     * {@code consumeFromQueue} for the same queue can be called again; left registered, it refused that until the whole
     * {@code DurableQueues} was stopped. Retired, because the consumer was registered - and so visible to a concurrent
     * {@link #start()}'s loop - from before its start: that loop may have taken it already, and its start of the consumer,
     * after this one failed, used to subscribe a consumer nothing reached any more - no {@link #stop()}, no caller, since
     * {@code consumeFromQueue} threw - while a retried {@code consumeFromQueue} registered a second consumer on the queue.
     * Retiring stops a subscription such a start has already made, and keeps one from starting it later. What that stop
     * throws is added to the start's failure as suppressed. A critical error escapes untouched, as from {@link #start()}.
     * <p>
     * The consumer is registered before {@link #started} is read, and {@link #start()} and {@link #stop()} set it before
     * they go over the consumers. So a start or stop either finds the consumer when it goes over them, or has set the flag
     * before it is read here. A stop that clears it after it was read here finds the consumer, and stops it; but that stop
     * may run before the consumer's start has begun subscribing, find nothing to stop, and the start then subscribe. Which
     * is why the flag is read again once the start has returned, and the consumer stopped when it has gone false
     * ({@link #stopConsumerStartedPastAStop}).
     */
    private DurableQueueConsumer doConsumeFromQueue(ConsumeFromQueue operation) {
        var queueName = operation.getQueueName();
        if (consumers.containsKey(queueName)) {
            throw alreadyConsuming(queueName);
        }
        var consumer = new ShardOwnedDurableQueueConsumer(operation, resolve(queueName), jsonSerializer,
                                                          this::stopConsuming, this::handleWithInterceptors);
        if (consumers.putIfAbsent(queueName, consumer) != null) {
            throw alreadyConsuming(queueName);
        }
        if (started.get()) {
            try {
                startHook.beforeStartingAddedConsumer(queueName);
                consumer.start();
            } catch (RuntimeException | Error e) {
                Exceptions.rethrowIfCriticalError(e);
                try {
                    Exceptions.forEachCollectingFailures(List.of(consumer), failed -> {
                        startHook.beforeRetiringAddedConsumer(queueName);
                        failed.retire();
                    }, e);
                } finally {
                    consumers.remove(queueName, consumer);
                }
                throw e;
            }
            stopConsumerStartedPastAStop(consumer);
        }
        return consumer;
    }

    private static IllegalStateException alreadyConsuming(QueueName queueName) {
        return new IllegalStateException("There is already a consumer on queue '" + queueName + "' in this process. "
                                                 + "Two consumers for one queue would register as two instances and each be "
                                                 + "allowed half its shards.");
    }

    /**
     * Called once a consumer's start - {@code consumeFromQueue}'s or {@link #start()}'s - has returned: stop the consumer
     * if this was stopped meanwhile, or a failed {@link #start()} marked it not started, and say whether it was. It stays
     * registered, so the next {@link #start()} starts it.
     * <p>
     * A {@link #started} that reads true needs nothing more: a stop that clears it later finds the consumer started, and
     * stops it. One that reads false is read again under the consumer's lifecycle lock, and the consumer stopped only when
     * it still reads false there ({@link ShardOwnedDurableQueueConsumer#stopIf}): a {@link #start()} that set it again
     * meanwhile has either started the consumer already, or starts it again once this stop has let go of the lock. A plain
     * stop after the read could undo such a start, leaving this reporting itself started with the consumer stopped, and the
     * next {@code start()} doing nothing.
     * <p>
     * Whatever the consumer's stop throws is logged at WARN rather than thrown: the consumer is registered and stopped
     * either way (a consumer's stop throws only once its subscription has stopped), and the caller did not ask for the stop.
     *
     * @return whether this read itself not started, the consumer then stopped
     */
    private boolean stopConsumerStartedPastAStop(ShardOwnedDurableQueueConsumer consumer) {
        if (started.get()) {
            return false;
        }
        try {
            return consumer.stopIf(() -> !started.get());
        } catch (RuntimeException | Error e) {
            Exceptions.rethrowIfCriticalError(e);
            log.warn("Consumer '{}' on queue '{}' finished starting after ShardOwnedDurableQueues was stopped, or after a failed "
                             + "start() had marked it not started, and then failed to stop. "
                             + "It stays registered and is started by the next start()",
                     consumer.consumerName(), consumer.queueName(), e);
            return true;
        }
    }

    /**
     * What a test does as a consumer is about to be started, or retired after its start failed, to hold the thread at a
     * point no real start can be made to stop at: between reading {@code started} and starting the consumer, or between
     * the failed start and retiring the consumer. Passed to the package-private constructor and fixed for this object's
     * life; every one built otherwise has {@link #NONE}, which does nothing. What {@link #beforeStartingAddedConsumer} or
     * {@link #beforeStartingConsumer} throws escapes as the consumer's start would.
     */
    interface StartHook {
        StartHook NONE = new StartHook() { };

        /**
         * Told the queue of a consumer {@code consumeFromQueue} has registered, and read {@code started} true for, just
         * before it starts the consumer.
         */
        default void beforeStartingAddedConsumer(QueueName queueName) {
        }

        /**
         * Told the queue of a consumer {@code start()}'s loop has read {@code started} true for, just before it starts
         * the consumer.
         */
        default void beforeStartingConsumer(QueueName queueName) {
        }

        /**
         * Told the queue of a consumer whose start {@code consumeFromQueue} has seen fail, just before it retires the
         * consumer ({@link ShardOwnedDurableQueueConsumer#retire()}) - after the failed start has let go of the consumer's
         * lifecycle lock, so a concurrent {@code start()}'s loop may take that lock first. What it throws is added to the
         * start's failure as suppressed, and the consumer is then neither retired nor registered.
         */
        default void beforeRetiringAddedConsumer(QueueName queueName) {
        }
    }

    /**
     * The cancelling half of {@link #consumeFromQueue(ConsumeFromQueue)}, which is where the
     * {@link StopConsumingFromQueue} operation exists.
     * <p>
     * {@code DurableQueues} has no method for it — a consumer is stopped through the consumer — so the
     * chain runs here, on the callback the consumer invokes when it is cancelled. A failing interceptor
     * is logged rather than propagated, matching {@code PostgresqlDurableQueues}: the consumer is
     * already stopped by the time this runs, and throwing would leave it stopped but still registered.
     */
    private void stopConsuming(DurableQueueConsumer consumer) {
        var operation = new StopConsumingFromQueue(consumer);
        try {
            newInterceptorChainForOperation(operation,
                                            interceptors,
                                            (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                            () -> (DurableQueueConsumer) consumers.remove(consumer.queueName()))
                    .proceed();
        } catch (Exception e) {
            log.error("Failed to perform " + operation, e);
        }
    }

    /**
     * One delivery, through the interceptor chain.
     * <p>
     * Called by {@link ShardOwnedDurableQueueConsumer} rather than the handler being invoked directly,
     * so that a {@link HandleQueuedMessage} interceptor sits where it does on the other engine. The
     * message is the partial shape — see this class's javadoc for what it will and will not answer.
     */
    private void handleWithInterceptors(QueuedMessage message, QueuedMessageHandler messageHandler) {
        var operation = new HandleQueuedMessage(message, messageHandler);
        newInterceptorChainForOperation(operation,
                                        interceptors,
                                        (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                        () -> {
                                            messageHandler.handle(message);
                                            return (Void) null;
                                        })
                .proceed();
    }

    // ---------------------------------------------------------------- by id

    @Override
    public Optional<QueuedMessage> getQueuedMessage(GetQueuedMessage operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doGetQueuedMessage(operation.getQueueEntryId()))
                .proceed();
    }

    private Optional<QueuedMessage> doGetQueuedMessage(QueueEntryId queueEntryId) {
        var decoded = QueueEntryIdCodec.decode(queueEntryId);
        return onQueue(decoded.queueName(), "read message '" + queueEntryId + "'",
                       () -> resolve(decoded.queueName()).getMessage(decoded.messageId())
                                                         .map(message -> toQueuedMessage(decoded.queueName(), message)));
    }

    @Override
    public Optional<QueuedMessage> getDeadLetterMessage(GetDeadLetterMessage operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doGetDeadLetterMessage(operation.getQueueEntryId()))
                .proceed();
    }

    private Optional<QueuedMessage> doGetDeadLetterMessage(QueueEntryId queueEntryId) {
        var decoded = QueueEntryIdCodec.decode(queueEntryId);
        return doGetDeadLetterMessages(decoded.queueName(), 0, Integer.MAX_VALUE)
                .stream()
                .filter(message -> message.getId().equals(queueEntryId))
                .findFirst();
    }

    @Override
    public Optional<QueueName> getQueueNameFor(QueueEntryId queueEntryId) {
        return Optional.of(QueueEntryIdCodec.decode(queueEntryId).queueName());
    }

    @Override
    public boolean deleteMessage(DeleteMessage operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doDeleteMessage(operation.getQueueEntryId()))
                .proceed();
    }

    private boolean doDeleteMessage(QueueEntryId queueEntryId) {
        var decoded = QueueEntryIdCodec.decode(queueEntryId);
        return onQueue(decoded.queueName(), "delete message '" + queueEntryId + "'",
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
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doDeleteMessage(operation.getQueueEntryId()))
                .proceed();
    }

    @Override
    public Optional<QueuedMessage> retryMessage(RetryMessage operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
                                                   var applied = onQueue(decoded.queueName(), "retry message '" + operation.getQueueEntryId() + "'",
                                                                         () -> resolve(decoded.queueName()).retryMessage(decoded.messageId(),
                                                                                                                         operation.getDeliveryDelay()));
                                                   return applied ? doGetQueuedMessage(operation.getQueueEntryId()) : Optional.<QueuedMessage>empty();
                                               })
                .proceed();
    }

    @Override
    public Optional<QueuedMessage> markAsDeadLetterMessage(MarkAsDeadLetterMessage operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var applied = doMarkAsDeadLetter(operation.getQueueEntryId(),
                                                                                    operation.getCauseForBeingMarkedAsDeadLetter());
                                                   return applied ? doGetDeadLetterMessage(operation.getQueueEntryId()) : Optional.<QueuedMessage>empty();
                                               })
                .proceed();
    }

    @Override
    public boolean markAsDeadLetterMessageDirect(MarkAsDeadLetterMessageDirect operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doMarkAsDeadLetter(operation.getQueueEntryId(),
                                                                        operation.getCauseForBeingMarkedAsDeadLetter()))
                .proceed();
    }

    private boolean doMarkAsDeadLetter(QueueEntryId queueEntryId, String cause) {
        var decoded = QueueEntryIdCodec.decode(queueEntryId);
        return onQueue(decoded.queueName(), "dead-letter message '" + queueEntryId + "'",
                       () -> resolve(decoded.queueName()).markAsDeadLetter(decoded.messageId(), cause));
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
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var decoded = QueueEntryIdCodec.decode(operation.getQueueEntryId());
                                                   var applied = onQueue(decoded.queueName(), "resurrect message '" + operation.getQueueEntryId() + "'",
                                                                         () -> resolve(decoded.queueName()).resurrect(decoded.messageId()));
                                                   if (applied) {
                                                       log.debug("Resurrected '{}' - it re-enters its lane at a fresh sequence, so its QueueEntryId has changed",
                                                                 operation.getQueueEntryId());
                                                   }
                                                   return Optional.<QueuedMessage>empty();
                                               })
                .proceed();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Not served — see this class's javadoc. It is also the one operation a
     * {@link DurableQueuesInterceptor} added here never observes, because there is no call to
     * intercept.
     */
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
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var depth = depth(operation.getQueueName());
                                                   return depth.unordered() + depth.ordered();
                                               })
                .proceed();
    }

    @Override
    public long getTotalDeadLetterMessagesQueuedFor(GetTotalDeadLetterMessagesQueuedFor operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> depth(operation.getQueueName()).deadLettered())
                .proceed();
    }

    /**
     * {@inheritDoc}
     * <p>
     * <b>{@code numberOfMessagesBeingDelivered} is always {@code null} - unknown, not zero.</b> A shard's
     * owner hands messages to handlers from memory and writes nothing per delivery, so the queue storage
     * cannot tell a message being handled from one waiting. Reporting 0 would make every healthy, busy
     * queue look stalled.
     * <p>
     * {@code oldestReadyMessageTimestamp} is real, and cluster-wide: the oldest {@code visible_at} of any
     * message that has become ready and is not held by a live pull session. Because deliveries are not
     * recorded it covers <em>unacknowledged</em> messages - one an owner is handling right now counts, as
     * does, in the ordered lane, a message waiting behind its key's head. A key stopped behind a dead
     * letter does not: the engine dead-letters the messages behind it as well, so it shows in
     * {@code numberOfQueuedDeadLetterMessages}. See {@link QueueDepth#oldestReadyAt()}.
     */
    @Override
    public QueuedMessageCounts getQueuedMessageCountsFor(GetQueuedMessageCountsFor operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var depth = depth(operation.getQueueName());
                                                   return new QueuedMessageCounts(operation.getQueueName(),
                                                                                  depth.unordered() + depth.ordered(),
                                                                                  depth.deadLettered(),
                                                                                  null,
                                                                                  depth.oldestReadyAt());
                                               })
                .proceed();
    }

    // -------------------------------------------------------------- listing

    @Override
    public List<QueuedMessage> getQueuedMessages(GetQueuedMessages operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
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
                                               })
                .proceed();
    }

    @Override
    public List<QueuedMessage> getDeadLetterMessages(GetDeadLetterMessages operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> doGetDeadLetterMessages(operation.getQueueName(),
                                                                             (int) Math.min(Integer.MAX_VALUE, operation.getStartIndex()),
                                                                             (int) Math.min(Integer.MAX_VALUE, operation.getPageSize())))
                .proceed();
    }

    private List<QueuedMessage> doGetDeadLetterMessages(QueueName queueName, int offset, int pageSize) {
        return onQueue(queueName, "read the dead letters of '" + queueName + "'",
                       () -> resolve(queueName).deadLetters(offset, pageSize).stream()
                                               .map(deadLetter -> toQueuedMessage(queueName, deadLetter))
                                               .<QueuedMessage>map(message -> message)
                                               .toList());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Not served — see this class's javadoc. {@code getQueuedMessages} pages by id, which this engine
     * can do because the id <em>is</em> {@code (lane, shard, seq)}; ordering by next-delivery timestamp
     * across every shard of both lanes is a different question, with no index behind it and no single
     * sequence to merge on.
     */
    @Override
    public List<NextQueuedMessage> queryForMessagesSoonReadyForDelivery(QueueName queueName,
                                                                        java.time.Instant withNextDeliveryTimestampAfter,
                                                                        int maxNumberOfMessagesToReturn) {
        throw new UnsupportedOperationException(
                "Ordering a queue by next-delivery timestamp across every shard of both lanes. There is no index that "
                        + "produces that order and no single sequence to merge on - which is a different question from "
                        + "getQueuedMessages, which pages by id and is served.");
    }

    @Override
    public int purgeQueue(PurgeQueue operation) {
        requireNonNull(operation, "No operation provided");
        return newInterceptorChainForOperation(operation,
                                               interceptors,
                                               (interceptor, interceptorChain) -> interceptor.intercept(operation, interceptorChain),
                                               () -> {
                                                   var purged = onQueue(operation.getQueueName(), "purge '" + operation.getQueueName() + "'",
                                                                        () -> resolve(operation.getQueueName()).purge());
                                                   return (int) Math.min(Integer.MAX_VALUE, purged);
                                               })
                .proceed();
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
                                                                        message.key(), message.keyOrder()),
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
                                                                        deadLetter.key(), deadLetter.keyOrder()),
                                            deadLetter.attempts(),
                                            null,
                                            null,
                                            true,
                                            deadLetter.lastError());
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
        var found           = onQueue(queueName, "resolve queue '" + queueName + "'", () -> queues.findQueue(engineQueueName));
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
