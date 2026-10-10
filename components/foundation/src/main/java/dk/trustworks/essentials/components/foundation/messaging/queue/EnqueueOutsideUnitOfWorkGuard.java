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

import dk.trustworks.essentials.components.foundation.startup.EssentialsStartupReport;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides what happens when a {@link DurableQueues} implementation is asked to enqueue a message
 * ({@code queueMessage} / {@code queueMessages}) while no unit of work or transaction is active.
 * <p>
 * Since 0.60 retired {@code TransactionalMode}, such an enqueue simply commits on its own. That is right for a caller
 * that wants exactly that, and silently wrong for code that relies on "my entity change and my enqueue commit
 * together" - an {@code Outbox.sendMessage} called outside the business transaction, for example, survives a rollback
 * of that business work and its message is delivered anyway.
 * <ul>
 *     <li>Not required (the default): the enqueue goes ahead and commits on its own, and is logged at WARN once per
 *         call site, with its stack trace, so the application can find every such site - a deliberate standalone
 *         enqueue logged first does not hide a hazardous one elsewhere. The call site is the first stack frame outside
 *         the framework and the JDK (or, with none, the first one outside the {@link DurableQueues} implementation);
 *         up to {@value #MAX_WARNED_CALL_SITES} call sites per {@link DurableQueues} instance are logged, then one line
 *         saying further ones are not.</li>
 *     <li>Required ({@link #setRequireActiveUnitOfWork(boolean)}, in Spring Boot the property
 *         {@value #REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY}): the enqueue fails with a
 *         {@link NoActiveUnitOfWorkException} and nothing is queued. Note that this includes every framework caller that
 *         enqueues without a transaction of its own, e.g. {@code DurableLocalCommandBus.sendAndDontWait} and
 *         {@code Outbox.sendMessage} called outside one. {@code Inbox.addMessageReceived} is unaffected: it starts a unit
 *         of work itself.</li>
 * </ul>
 * "Active" means {@link #isUnitOfWorkActive(UnitOfWorkFactory)}: the factory has a current {@link UnitOfWork}, or the
 * factory is Spring-aware and a Spring transaction is active (which the factory joins).
 * <p>
 * Dead-letter enqueues ({@code queueMessageAsDeadLetterMessage}) are not checked: they are an operator's repair action,
 * normally run outside any business transaction.
 */
public final class EnqueueOutsideUnitOfWorkGuard {
    /**
     * The Spring Boot property the starters bind to {@link #setRequireActiveUnitOfWork(boolean)}. Default {@code false}.
     */
    public static final String REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY = "essentials.durable-queues.require-active-unit-of-work-for-enqueue";

    /**
     * How many distinct call sites one guard (one {@link DurableQueues} instance) logs at WARN before it stops
     */
    public static final int MAX_WARNED_CALL_SITES = 32;

    /**
     * Class name prefixes of frames that are never "the call site": the framework itself, the JDK and the libraries an
     * enqueue is routinely called through
     */
    private static final List<String> FRAMEWORK_CLASS_PREFIXES = List.of("dk.trustworks.essentials.components.",
                                                                         "dk.trustworks.essentials.reactive.",
                                                                         "dk.trustworks.essentials.shared.",
                                                                         "java.", "javax.", "jdk.", "sun.", "com.sun.",
                                                                         "reactor.", "kotlin.", "kotlinx.", "org.springframework.");
    /**
     * Frames looked at to find the call site - an application frame deeper than this is reported as the first frame
     * outside the {@link DurableQueues} implementation instead
     */
    private static final int         MAX_FRAMES_INSPECTED = 128;
    private static final StackWalker STACK_WALKER         = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final String SPRING_TRANSACTION_AWARE_UNIT_OF_WORK_FACTORY =
            "dk.trustworks.essentials.components.foundation.transaction.spring.SpringTransactionAwareUnitOfWorkFactory";

    private final    Logger            log;
    private final    Predicate<String> isFrameworkClass;
    private final    Set<String>   warnedCallSites            = ConcurrentHashMap.newKeySet();
    private final    AtomicBoolean warnedCallSiteCapReached   = new AtomicBoolean();
    private volatile boolean       requireActiveUnitOfWork;

    /**
     * @param durableQueuesType the {@link DurableQueues} implementation the guard belongs to; its logger is used, so the
     *                          WARN appears under the engine that let the enqueue through
     */
    public EnqueueOutsideUnitOfWorkGuard(Class<?> durableQueuesType) {
        this(durableQueuesType, className -> FRAMEWORK_CLASS_PREFIXES.stream().anyMatch(className::startsWith));
    }

    /**
     * Package-private for tests, whose own frames are framework frames by package
     *
     * @param durableQueuesType see {@link #EnqueueOutsideUnitOfWorkGuard(Class)}
     * @param isFrameworkClass  tells, by class name, whether a frame can never be the call site
     */
    EnqueueOutsideUnitOfWorkGuard(Class<?> durableQueuesType, Predicate<String> isFrameworkClass) {
        this.log = LoggerFactory.getLogger(requireNonNull(durableQueuesType, "No durableQueuesType provided"));
        this.isFrameworkClass = requireNonNull(isFrameworkClass, "No isFrameworkClass provided");
    }

    /**
     * @param requireActiveUnitOfWork {@code true}: an enqueue outside a unit of work or transaction fails with a
     *                                {@link NoActiveUnitOfWorkException}. {@code false} (default): it commits on its own
     *                                and is logged at WARN once per call site
     */
    public void setRequireActiveUnitOfWork(boolean requireActiveUnitOfWork) {
        this.requireActiveUnitOfWork = requireActiveUnitOfWork;
    }

    /**
     * @return whether an enqueue outside a unit of work or transaction fails
     */
    public boolean isRequireActiveUnitOfWork() {
        return requireActiveUnitOfWork;
    }

    /**
     * Check an enqueue against {@code unitOfWorkFactory}; see {@link #isUnitOfWorkActive(UnitOfWorkFactory)}.
     *
     * @param queueName         the queue being enqueued on
     * @param unitOfWorkFactory the implementation's factory - {@code null} counts as "no unit of work active"
     * @throws NoActiveUnitOfWorkException if none is active and {@link #isRequireActiveUnitOfWork()}
     */
    public void checkEnqueue(QueueName queueName, UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        checkEnqueue(queueName, isUnitOfWorkActive(unitOfWorkFactory));
    }

    /**
     * Check an enqueue whose "is a unit of work or transaction active" answer the implementation worked out itself.
     *
     * @param queueName        the queue being enqueued on
     * @param unitOfWorkActive whether the enqueue will join a unit of work or transaction
     * @throws NoActiveUnitOfWorkException if {@code unitOfWorkActive} is false and {@link #isRequireActiveUnitOfWork()}
     */
    public void checkEnqueue(QueueName queueName, boolean unitOfWorkActive) {
        if (unitOfWorkActive) {
            return;
        }
        if (requireActiveUnitOfWork) {
            throw new NoActiveUnitOfWorkException("Refusing to enqueue on queue '" + queueName + "' outside a UnitOfWork/transaction: '"
                                                  + REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY + "' is enabled. "
                                                  + "Enqueue inside unitOfWorkFactory.usingUnitOfWork(...) or a Spring @Transactional/TransactionTemplate.");
        }
        if (!warnedCallSiteCapReached.get()) {
            warnOncePerCallSite(queueName, callSite());
        }
    }

    /**
     * Package-private for tests
     */
    void warnOncePerCallSite(QueueName queueName, String callSite) {
        if (warnedCallSites.contains(callSite)) {
            return;
        }
        if (warnedCallSites.size() >= MAX_WARNED_CALL_SITES) {
            if (warnedCallSiteCapReached.compareAndSet(false, true)) {
                log.warn("[{}] Messages are enqueued outside a UnitOfWork/transaction from more than {} call sites - further call sites are not logged",
                         queueName, MAX_WARNED_CALL_SITES);
            }
            return;
        }
        if (!warnedCallSites.add(callSite)) {
            return;
        }
        log.warn("[{}] A message was enqueued outside a UnitOfWork/transaction from '{}', so it committed on its own and is NOT rolled back with " +
                 "any surrounding business work. If the enqueue should commit together with that work, enqueue inside a UnitOfWork or a " +
                 "Spring transaction; set '{}=true' to make such an enqueue fail instead. Logged once per call site; the " +
                 "stack trace shows the full call path.",
                 queueName, callSite, REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY, new EnqueueCallSite());
        // Reaches the startup report only when the enqueue happens while the application context is starting
        EssentialsStartupReport.addToCurrent(EssentialsStartupReport.Finding.warn(
                "DurableQueues enqueue on queue '" + queueName + "' from " + callSite,
                "a message was enqueued outside a UnitOfWork/transaction while the application was starting, so it committed on its own",
                "Enqueue inside a UnitOfWork or a Spring transaction if it should commit with surrounding work; '" + REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY
                        + "=true' makes such an enqueue fail instead"));
    }

    /**
     * @return the first frame outside the framework and the JDK; with none (an enqueue the framework makes on its own,
     * or a framework test), the first frame outside this guard and the {@link DurableQueues} implementation
     */
    private String callSite() {
        return STACK_WALKER.walk(frames -> {
            var candidates = frames.filter(frame -> frame.getDeclaringClass() != EnqueueOutsideUnitOfWorkGuard.class
                                                    && !DurableQueues.class.isAssignableFrom(frame.getDeclaringClass()))
                                   .limit(MAX_FRAMES_INSPECTED)
                                   .toList();
            return candidates.stream()
                             .filter(frame -> !isFrameworkClass.test(frame.getClassName()))
                             .findFirst()
                             .or(() -> candidates.stream().findFirst())
                             .map(frame -> frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                             .orElse("an unknown call site");
        });
    }

    /**
     * Is a unit of work or transaction active that work started now through {@code unitOfWorkFactory} would join?
     * <p>
     * {@code true} when the factory has a {@link UnitOfWorkFactory#getCurrentUnitOfWork() current UnitOfWork}, or when it
     * is a Spring-aware factory ({@code SpringTransactionAwareUnitOfWorkFactory} and subclasses) and a Spring transaction is
     * active - such a factory creates the {@link UnitOfWork} for that transaction on first use, so none is bound yet
     * under a plain {@code @Transactional}/{@code TransactionTemplate}.
     *
     * @param unitOfWorkFactory the factory; {@code null} answers {@code false}
     * @return whether a unit of work or transaction is active
     */
    public static boolean isUnitOfWorkActive(UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        if (unitOfWorkFactory == null) {
            return false;
        }
        if (unitOfWorkFactory.getCurrentUnitOfWork().isPresent()) {
            return true;
        }
        // Matched by class name, so this class loads no Spring type when Spring is absent - Spring is an optional
        // dependency of foundation. A factory that IS Spring-aware proves Spring is on the classpath.
        return isSpringTransactionAware(unitOfWorkFactory.getClass()) && SpringTransactions.isActualTransactionActive();
    }

    private static boolean isSpringTransactionAware(Class<?> type) {
        for (var candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            if (SPRING_TRANSACTION_AWARE_UNIT_OF_WORK_FACTORY.equals(candidate.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Holder so {@code TransactionSynchronizationManager} is only resolved when a Spring-aware factory is in use.
     */
    private static final class SpringTransactions {
        static boolean isActualTransactionActive() {
            return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    /**
     * Carries the stack trace of a call site's first enqueue outside a unit of work in the WARN; never thrown.
     */
    private static final class EnqueueCallSite extends RuntimeException {
        EnqueueCallSite() {
            super("Enqueue call site");
        }
    }
}
