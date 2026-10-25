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

import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
 *     <li>Not required (the default): the enqueue goes ahead and commits on its own, and is logged at WARN with its
 *         stack trace, so the application can find such sites - a deliberate standalone enqueue logged first does not
 *         hide a hazardous one elsewhere, within the budget below. The call site is the first
 *         {@value #CALL_SITE_FRAMES} stack frames outside the framework and the JDK, so callers that reach the enqueue
 *         through one application wrapper (a gateway around {@code Outbox.sendMessage}, say) are still told apart (with
 *         no such frame, it is the first frame outside the {@link DurableQueues} implementation). The logging is capped
 *         per innermost application frame, not per call site, so one wrapper reached from many callers cannot use up
 *         the cap and hide every other call site: up to {@value #MAX_WARNED_INNERMOST_FRAMES} innermost frames per
 *         {@link DurableQueues} instance are logged, each with up to {@value #MAX_WARNED_CALLERS_PER_INNERMOST_FRAME}
 *         of its call sites (its callers' variants); later call sites through an innermost frame that has used its
 *         variants are not logged. The first call site of the last innermost frame is followed by one line saying
 *         further call sites are not logged. Once that frame is charged, the guard still walks the stack, so a frame
 *         charged earlier keeps logging its remaining caller variants, but it stops once every charged frame has used
 *         its variants, or after {@value #MAX_FRUITLESS_STACK_WALKS_AFTER_FRAME_CAP} walks past the cap that logged
 *         nothing, whichever comes first. The second bound is what normally applies: a frame with fewer callers than
 *         its variants would otherwise keep every later enqueue walking the stack. A caller variant of a charged frame
 *         first reached after that is not logged. Before the cap is reached every enqueue outside a unit of work is
 *         walked, since a new innermost frame can still be logged.</li>
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
     * How many distinct innermost application frames one guard (one {@link DurableQueues} instance) logs call sites for
     * at WARN before it stops. Each innermost frame logs up to {@value #MAX_WARNED_CALLERS_PER_INNERMOST_FRAME} call
     * sites, so this bounds the WARNs at {@value #MAX_WARNED_INNERMOST_FRAMES} times that.
     */
    public static final int MAX_WARNED_INNERMOST_FRAMES = 32;

    /**
     * How many call sites (callers' variants) sharing one innermost application frame are logged - only the first of
     * them counts against {@link #MAX_WARNED_INNERMOST_FRAMES}
     */
    static final int MAX_WARNED_CALLERS_PER_INNERMOST_FRAME = 4;

    /**
     * How many stack walks that log nothing the guard makes once every one of the {@link #MAX_WARNED_INNERMOST_FRAMES}
     * slots is charged, before it stops walking the stack. Without it the walking only stops once every charged frame
     * has logged all its {@link #MAX_WARNED_CALLERS_PER_INNERMOST_FRAME} caller variants, which a frame with fewer
     * callers never does - so in practice every later enqueue outside a unit of work would keep paying one walk.
     */
    static final int MAX_FRUITLESS_STACK_WALKS_AFTER_FRAME_CAP = 10_000;

    /**
     * How many application frames, innermost first, make up one call site
     */
    static final int CALL_SITE_FRAMES = 3;

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

    /**
     * Whether Spring's {@code spring-tx} is on the classpath - looked up by name, without initialising it
     */
    private static final boolean SPRING_TX_PRESENT = isClassPresent("org.springframework.transaction.support.TransactionSynchronizationManager");

    /**
     * The innermost frame of a call site with no frames - "an unknown call site"
     */
    private static final CallSiteFrame UNKNOWN_FRAME = new CallSiteFrame("", "", -1);

    private final    Logger                                     log;
    private final    Predicate<String>                          isFrameworkClass;
    /**
     * Call sites logged, or about to be. Bounded by the caps: a key is only added once its innermost frame holds a slot
     * and still has caller variants left.
     */
    private final    Set<List<CallSiteFrame>>                   warnedCallSites                = ConcurrentHashMap.newKeySet();
    /**
     * Each innermost frame seen, with the slot it was charged. Grows past {@link #MAX_WARNED_INNERMOST_FRAMES} only by the
     * threads racing in with a new innermost frame as the cap is reached.
     */
    private final    ConcurrentHashMap<CallSiteFrame, Charge>   chargedInnermostFrames         = new ConcurrentHashMap<>();
    /**
     * Innermost frames charged so far - incremented only inside {@link ConcurrentHashMap#computeIfAbsent}, so each
     * innermost frame takes exactly one slot, and the slot alone decides whether its call sites are logged: the cap
     * holds however many threads race past the check before the stack walk
     */
    private final    AtomicInteger                              chargedInnermostFrameCount     = new AtomicInteger();
    /**
     * Innermost frames that have logged all their {@link #MAX_WARNED_CALLERS_PER_INNERMOST_FRAME} caller variants. With
     * every slot charged and exhausted, nothing more can be logged, so the guard stops walking the stack.
     */
    private final    AtomicInteger                              exhaustedInnermostFrameCount   = new AtomicInteger();
    /**
     * Stack walks made with every slot already charged that logged nothing. At
     * {@link #MAX_FRUITLESS_STACK_WALKS_AFTER_FRAME_CAP} the guard stops walking the stack.
     */
    private final    AtomicInteger                              fruitlessWalksAfterFrameCap    = new AtomicInteger();
    private volatile boolean                                    requireActiveUnitOfWork;

    /**
     * @param durableQueuesType the {@link DurableQueues} implementation the guard belongs to; its logger is used, so the
     *                          WARN appears under the engine that let the enqueue through
     */
    public EnqueueOutsideUnitOfWorkGuard(Class<?> durableQueuesType) {
        this(durableQueuesType, EnqueueOutsideUnitOfWorkGuard::isFrameworkClassByDefault);
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
     *                                and is logged at WARN (once per call site, within the per-innermost-frame budget)
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
        if (exhaustedInnermostFrameCount.get() >= MAX_WARNED_INNERMOST_FRAMES
                || fruitlessWalksAfterFrameCap.get() >= MAX_FRUITLESS_STACK_WALKS_AFTER_FRAME_CAP) {
            // Every slot is charged and has used its caller variants, or the walks past the cap have stopped finding
            // anything new: no stack walk at all
            return;
        }
        var frameCapReached = chargedInnermostFrameCount.get() >= MAX_WARNED_INNERMOST_FRAMES;
        if (!warnIfNotYetLogged(queueName, frameCapReached) && frameCapReached) {
            fruitlessWalksAfterFrameCap.incrementAndGet();
        }
    }

    /**
     * Walk the stack and log the call site, unless it was logged before or the budget is used up.
     *
     * @param frameCapReached whether every slot was charged before the walk - a new innermost frame is then never charged
     * @return whether the call site was logged
     */
    private boolean warnIfNotYetLogged(QueueName queueName, boolean frameCapReached) {
        var callSite       = callSite();
        var key            = callSite.key();
        var innermostFrame = key.isEmpty() ? UNKNOWN_FRAME : key.getFirst();
        var charge = frameCapReached
                     ? chargedInnermostFrames.get(innermostFrame)
                     : chargedInnermostFrames.computeIfAbsent(innermostFrame, frame -> new Charge(chargedInnermostFrameCount.incrementAndGet()));
        if (charge == null || charge.slot() > MAX_WARNED_INNERMOST_FRAMES) {
            // A new innermost frame past the cap, or lost the race for the last slot
            return false;
        }
        if (charge.callersWarned().get() >= MAX_WARNED_CALLERS_PER_INNERMOST_FRAME) {
            // This innermost frame has used its caller variants
            return false;
        }
        if (!warnedCallSites.add(key)) {
            return false;
        }
        var caller = charge.callersWarned().incrementAndGet();
        if (caller > MAX_WARNED_CALLERS_PER_INNERMOST_FRAME) {
            // Lost the race for this innermost frame's last caller variant
            return false;
        }
        if (caller == MAX_WARNED_CALLERS_PER_INNERMOST_FRAME) {
            exhaustedInnermostFrameCount.incrementAndGet();
        }
        warn(queueName, callSite.describe());
        if (caller == 1 && charge.slot() == MAX_WARNED_INNERMOST_FRAMES) {
            log.warn("[{}] Messages have been enqueued outside a UnitOfWork/transaction from {} innermost application frames - further call sites are not logged (frames already listed may still log up to {} of their callers)",
                     queueName, MAX_WARNED_INNERMOST_FRAMES, MAX_WARNED_CALLERS_PER_INNERMOST_FRAME);
        }
        return true;
    }

    /**
     * @param slot          the innermost frame's place against {@link #MAX_WARNED_INNERMOST_FRAMES}, from 1
     * @param callersWarned call sites through the innermost frame logged so far, up to
     *                      {@link #MAX_WARNED_CALLERS_PER_INNERMOST_FRAME}
     */
    private record Charge(int slot, AtomicInteger callersWarned) {
        Charge(int slot) {
            this(slot, new AtomicInteger());
        }
    }

    private void warn(QueueName queueName, String callSite) {
        log.warn("[{}] A message was enqueued outside a UnitOfWork/transaction from '{}', so it committed on its own and is NOT rolled back with " +
                 "any surrounding business work. If the enqueue should commit together with that work, enqueue inside a UnitOfWork or a " +
                 "Spring transaction; set '{}=true' to make such an enqueue fail instead. Each call site is logged once, within a per-innermost-frame budget (see " +
                 "EnqueueOutsideUnitOfWorkGuard); the stack trace shows the full call path.",
                 queueName, callSite, REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY, new EnqueueCallSite());
        // During start-up this reaches the report of the context being refreshed; at runtime the run report - when exactly
        // one is registered in the JVM (see EssentialsRunReport), otherwise only the WARN above
        EssentialsRunReport.addToCurrent(EssentialsRunReport.Finding.warn(
                "DurableQueues enqueue on queue '" + queueName + "' from " + callSite,
                "a message was enqueued outside a UnitOfWork/transaction, so it committed on its own",
                "Enqueue inside a UnitOfWork or a Spring transaction if it should commit with surrounding work; '" + REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY
                        + "=true' makes such an enqueue fail instead"));
    }

    /**
     * Package-private for tests
     *
     * @param className a stack frame's class name
     * @return whether the default call-site resolution treats the class as framework - never the call site
     */
    static boolean isFrameworkClassByDefault(String className) {
        for (var prefix : FRAMEWORK_CLASS_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One pass over the stack that stops at the {@value #CALL_SITE_FRAMES}th application frame.
     *
     * @return the first {@value #CALL_SITE_FRAMES} frames outside the framework and the JDK (fewer when the stack has
     * fewer); with none (an enqueue the framework makes on its own, or a framework test), the first frame outside this
     * guard and the {@link DurableQueues} implementation
     */
    private ResolvedCallSite callSite() {
        return STACK_WALKER.walk(stream -> {
            var frames = stream.filter(frame -> frame.getDeclaringClass() != EnqueueOutsideUnitOfWorkGuard.class
                                                && !DurableQueues.class.isAssignableFrom(frame.getDeclaringClass()))
                               .limit(MAX_FRAMES_INSPECTED)
                               .iterator();
            StackWalker.StackFrame       firstOutsideTheQueues = null;
            List<StackWalker.StackFrame> applicationFrames     = new ArrayList<>(CALL_SITE_FRAMES);
            while (frames.hasNext() && applicationFrames.size() < CALL_SITE_FRAMES) {
                var frame = frames.next();
                if (firstOutsideTheQueues == null) {
                    firstOutsideTheQueues = frame;
                }
                if (!isFrameworkClass.test(frame.getClassName())) {
                    applicationFrames.add(frame);
                }
            }
            if (!applicationFrames.isEmpty()) {
                return new ResolvedCallSite(applicationFrames);
            }
            return new ResolvedCallSite(firstOutsideTheQueues != null ? List.of(firstOutsideTheQueues) : List.of());
        });
    }

    /**
     * One frame of a call-site key. The bytecode index tells apart two calls on different lines of one method without
     * the line-number lookup, which is left to a call site that is actually logged.
     */
    private record CallSiteFrame(String className, String methodName, int byteCodeIndex) {
    }

    /**
     * @param frames the call site's frames, innermost first; empty when none was found
     */
    private record ResolvedCallSite(List<StackWalker.StackFrame> frames) {
        List<CallSiteFrame> key() {
            return frames.stream()
                         .map(frame -> new CallSiteFrame(frame.getClassName(), frame.getMethodName(), frame.getByteCodeIndex()))
                         .toList();
        }

        /**
         * @return {@code Class.method(File:line)} per frame, innermost first, joined by {@code " <- "}
         */
        String describe() {
            if (frames.isEmpty()) {
                return "an unknown call site";
            }
            return frames.stream()
                         .map(frame -> frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                         .collect(Collectors.joining(" <- "));
        }
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

    /**
     * Is a Spring transaction active on the calling thread ({@code TransactionSynchronizationManager.isActualTransactionActive()})?
     * <p>
     * Unlike {@link #isUnitOfWorkActive(UnitOfWorkFactory)} this asks no factory whether it is Spring-aware: it is for
     * a caller that cannot tell whether its connections join a Spring transaction (a plain {@code Jdbi} built on a
     * {@code TransactionAwareDataSourceProxy} does, one built on the raw pool does not).
     *
     * @return whether a Spring transaction is active; {@code false} when {@code spring-tx} is not on the classpath
     */
    public static boolean isSpringTransactionActive() {
        // SpringTransactions is only resolved once spring-tx is known to be present
        return SPRING_TX_PRESENT && SpringTransactions.isActualTransactionActive();
    }

    // This class's loader is the one that resolves SpringTransactions' reference, so asking the context loader could
    // say 'present' when that reference fails
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, EnqueueOutsideUnitOfWorkGuard.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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
     * Holder so {@code TransactionSynchronizationManager} is only resolved when a Spring-aware factory is in use, or
     * {@code spring-tx} is known to be on the classpath.
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
