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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.slf4j.*;

import java.sql.SQLException;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * A read model kept by {@link MessageHandler} methods (design phase 10 §3.5): a thin shell over one {@link ViewProjection}
 * per aggregate type - {@code CONTIGUOUS}, exclusive, each page in one transaction that also moves the resume point - so
 * every engine rule is the projection's. Porting an old {@code ViewEventProcessor} is a superclass swap.
 * <pre>{@code
 * public class OrderSummaryView extends EventLogViewProcessor {
 *     public OrderSummaryView(EventLogProcessorDependencies dependencies, EventStoreUnitOfWorkFactory<?> unitOfWorkFactory) {
 *         super("order-summary", List.of("Orders"), dependencies);
 *         this.unitOfWorkFactory = unitOfWorkFactory;
 *     }
 *
 *     @MessageHandler
 *     void on(OrderPlaced placed, DeliveredEvent event) {
 *         unitOfWorkFactory.getRequiredUnitOfWork().handle().execute("INSERT ...");
 *     }
 * }
 * }</pre>
 * <ul>
 *     <li>A handler takes the payload, optionally a {@link DeliveredEvent} second parameter; the most specific payload type
 *     wins. A payload with no handler counts as handled.</li>
 *     <li>A handler runs inside the page transaction from {@link EventLogProcessorDependencies#pageTransactions()}: with
 *     {@link UnitOfWorkPageTransactions} the page's unit of work is bound ({@code getRequiredUnitOfWork()}, its
 *     {@code Handle}), with {@link SpringPageTransactions} the Spring transaction. Its writes commit with the page.</li>
 *     <li>A handler's exception is the engine's: retried, the page shrunk, the event parked ({@link ParkEventException}
 *     parks at once). Nothing is caught here.</li>
 *     <li>Not carried over from the old processors: {@code MessageHandlerInterceptor}s, {@link MessageHandler#unitOfWork()},
 *     the built-in permanent-error list, inbox redelivery, Reactor and the {@code FencedLock}.</li>
 *     <li>An aggregate type not yet registered with the {@link EventLog} is registered (default tables) at {@link #start()}.</li>
 *     <li>{@link #rebuild()} replays the view from the start; override {@link #onRebuild(ProjectionContext)} to clear it,
 *     in the transaction that resets the subscriptions.</li>
 * </ul>
 * Started and stopped as a foundation {@link Lifecycle} - by the Spring Boot starter's lifecycle manager, or by hand.
 */
public abstract class EventLogViewProcessor implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(EventLogViewProcessor.class);

    private final String                        name;
    private final List<String>                  aggregateTypes;
    private final EventLogProcessorDependencies dependencies;
    private final MessageHandlerDispatch        dispatch;
    private ProcessorSeams                      seams = ProcessorSeams.NONE;
    private final LifecycleGate                 gate = new LifecycleGate();
    private volatile List<ViewProjection>       projections;
    private volatile boolean                    started;

    /**
     * @param name           the processor (subscriber) name, the same for each aggregate type's subscription
     * @param aggregateTypes the aggregate types to follow: one subscription each, with the same handlers, started and stopped together
     * @param dependencies   the event log, serializer and page transactions
     */
    protected EventLogViewProcessor(String name, List<String> aggregateTypes, EventLogProcessorDependencies dependencies) {
        this.name = requireNonBlank(name, "No name provided");
        this.aggregateTypes = List.copyOf(requireNonEmpty(aggregateTypes, "No aggregateTypes provided"));
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.dispatch = new MessageHandlerDispatch(this);
    }

    /** {@link ConsistencyMode#CONTIGUOUS} unless overridden */
    protected ConsistencyMode consistency() {
        return ConsistencyMode.CONTIGUOUS;
    }

    /** {@link StartFrom#beginning()} unless overridden */
    protected StartFrom startFrom() {
        return StartFrom.beginning();
    }

    /** Empty: the engine's defaults ({@link ErrorSettings#defaults(EventLogSettings)}) */
    protected Optional<ErrorSettings> errorSettings() {
        return Optional.empty();
    }

    /** Empty: every tenant */
    protected Optional<String> tenant() {
        return Optional.empty();
    }

    /**
     * Clear the read model at the start of a {@link #rebuild()}: delete or truncate what the handlers write, so the replay
     * starts from an empty view. Does nothing unless overridden - the new engine's counterpart of the old processors'
     * {@code onSubscriptionsReset(AggregateType, GlobalEventOrder)}, called once per rebuild rather than once per
     * aggregate type.
     * <p>
     * Runs on the caller's thread inside the rebuild's transaction, opened from the same page source the handlers write
     * in, so it writes the way a handler does: with {@link UnitOfWorkPageTransactions} the transaction's unit of work is
     * bound ({@code getRequiredUnitOfWork()}, {@link ProjectionContext#handle()}), with {@link SpringPageTransactions} the
     * Spring transaction. Its writes commit together with the reset of every aggregate type's subscription, or not at all.
     * It must not end the transaction; an exception rolls the whole rebuild back.
     *
     * @param context the rebuild transaction's resources
     */
    protected void onRebuild(ProjectionContext context) throws Exception {
    }

    /**
     * Register any unregistered aggregate type, then subscribe once per aggregate type. Idempotent. Start, stop and
     * {@code seams} run one at a time through a {@link LifecycleGate}, with no monitor held while a stop joins the
     * delivery threads, so a handler that reads this processor ({@link #projections()}, {@link #isStarted()}) never waits behind a stop joining its own thread
     */
    @Override
    public void start() {
        try (var ignored = gate.enter()) {
            if (started) {
                return;
            }
            startAll();
            started = true;
        }
        log.info("[{}] Started on aggregate types {}", name, aggregateTypes);
    }

    private void startAll() {
        try {
            if (projections == null) {
                projections = build();
            }
            for (var aggregateType : aggregateTypes) {
                dependencies.eventLog().registerAggregateType(aggregateType);
            }
            for (var projection : projections) {
                projection.start();
            }
        } catch (SQLException | RuntimeException e) {
            stopAll();
            throw new IllegalStateException("Failed to start " + this, e);
        }
    }

    @Override
    public void stop() {
        try (var ignored = gate.enter()) {
            if (!started) {
                return;
            }
            stopAll();
            started = false;
        }
        log.info("[{}] Stopped", name);
    }

    /**
     * Rebuild the read model from the start (design phase 10 §9): stop every aggregate type's subscription, then in one
     * transaction of {@link EventLogProcessorDependencies#pageTransactions()} call {@link #onRebuild(ProjectionContext)}
     * and reset every subscription to {@link #startFrom()}, commit, and start again if the processor was started. The
     * cleared view and the reset resume points commit together, so at every point the view matches its resume points;
     * the replay then refills it through the handlers. An instance elsewhere that holds a subscription's lease notices
     * the reset at its next page and repositions (the engine's reset epoch).
     * <p>
     * Call it outside a unit of work: {@link UnitOfWorkPageTransactions} refuses to open the rebuild's own when one is
     * already active on the thread. Runs as one lifecycle transition, so it waits for a start or stop in progress.
     *
     * @return {@code false} if no aggregate type's subscription has a progress row yet: nothing to rebuild, and
     * {@link #onRebuild(ProjectionContext)}'s writes are rolled back
     * @throws IllegalStateException if the page source is {@link PageTransactionSource#engine()}, which binds nothing a
     *                               handler or {@link #onRebuild(ProjectionContext)} could write through; or if the rebuild failed (rolled back)
     */
    public boolean rebuild() {
        var source = dependencies.pageTransactions();
        if (source == PageTransactionSource.engine()) {
            throw new IllegalStateException("Rebuilding " + this + " needs a UnitOfWorkPageTransactions or SpringPageTransactions page source, not PageTransactionSource.engine()");
        }
        boolean reset;
        try (var ignored = gate.enter()) {
            reset = rebuildStopped(source);
        }
        log.info("[{}] Rebuilt on aggregate types {}: {}", name, aggregateTypes, reset ? "replaying from the start" : "no progress yet, nothing reset");
        return reset;
    }

    private boolean rebuildStopped(PageTransactionSource source) {
        if (projections == null) {
            projections = build();
        }
        var wasStarted = started;
        stopAll();
        RuntimeException failure = null;
        var reset = false;
        try {
            for (var projection : projections) {
                if (projection.isRunning()) {
                    throw new IllegalStateException(projection + " did not stop");
                }
            }
            reset = clearAndReset(source);
        } catch (Exception e) {
            failure = e instanceof RuntimeException runtime ? runtime : new IllegalStateException("Rebuilding " + this + " failed", e);
        }
        if (wasStarted) {
            try {
                startAll();
            } catch (RuntimeException e) {
                started = false;
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return reset;
    }

    /** {@link #onRebuild(ProjectionContext)} and every subscription's reset, in one transaction of {@code source} */
    private boolean clearAndReset(PageTransactionSource source) throws Exception {
        var open = source.begin();
        var reset = false;
        try {
            onRebuild(new ProjectionContext(open));
            if (seams.has(ProcessorMutant.REBUILD_CLEARS_OUTSIDE_RESET_TRANSACTION)) {
                log.warn("[{}] MUTANT REBUILD_CLEARS_OUTSIDE_RESET_TRANSACTION: the clear commits before the reset", name);
                open.commit();
                open = source.begin();
            }
            for (var projection : projections) {
                reset |= projection.resetProgress(open.connection());
            }
        } catch (Throwable t) {
            open.rollback();
            throw t;
        }
        if (reset) {
            open.commit();
        } else {
            open.rollback();
        }
        return reset;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    public String name() {
        return name;
    }

    public List<String> aggregateTypes() {
        return aggregateTypes;
    }

    /**
     * @return one projection per aggregate type, in {@link #aggregateTypes()} order (for keys, handles, status); empty
     * before the first start or rebuild. Rebuild through {@link #rebuild()}, which clears the view, not a projection's own
     */
    public List<ViewProjection> projections() {
        return projections == null ? List.of() : projections;
    }

    /** Test-only: mutants and subscribe path, before the first {@link #start()} */
    void seams(ProcessorSeams seams) {
        try (var ignored = gate.enter()) {
            requireTrue(projections == null, "Seams must be set before the first start");
            this.seams = requireNonNull(seams, "No seams provided");
        }
    }

    private List<ViewProjection> build() {
        var built = new ArrayList<ViewProjection>(aggregateTypes.size());
        for (var aggregateType : aggregateTypes) {
            var builder = ViewProjection.builder(name, aggregateType)
                                        .on(Object.class, (event, context) -> handle(event))
                                        .consistency(consistency())
                                        .startFrom(startFrom())
                                        .transactions(dependencies.pageTransactions())
                                        .seams(seams);
            errorSettings().ifPresent(builder::errors);
            tenant().ifPresent(builder::tenant);
            built.add(builder.build(dependencies.eventLog(), dependencies.serializer()));
        }
        return List.copyOf(built);
    }

    private void handle(DeliveredEvent event) {
        try {
            dispatch.dispatch(event);
        } catch (Exception e) {
            // A handler's checked exception arrives here too (thrown sneakily); precise rethrow keeps it unchanged
            if (!seams.has(ProcessorMutant.ANNOTATED_DISPATCH_SWALLOWS_FAILURE)) {
                throw e;
            }
            log.warn("[{}] MUTANT ANNOTATED_DISPATCH_SWALLOWS_FAILURE: failure of {} swallowed", name, event.globalOrder(), e);
        }
    }

    private void stopAll() {
        if (projections == null) {
            return;
        }
        for (var projection : projections) {
            try {
                projection.stop();
            } catch (RuntimeException e) {
                log.warn("[{}] Failed to stop {}", name, projection, e);
            }
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " on " + aggregateTypes + "]";
    }
}
