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
package dk.trustworks.essentials.components.eventlog.integration;

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

    /** @return one projection per aggregate type, in {@link #aggregateTypes()} order (for keys, handles, rebuild); empty before the first start */
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
            if (!seams.has(IntegrationMutant.ANNOTATED_DISPATCH_SWALLOWS_FAILURE)) {
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
