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
 * Side effects (notifications, commands, forwarding to a queue) run by {@link MessageHandler} methods (design phase 10
 * §3.5): a thin shell over one {@link EventReactor} per aggregate type - {@code FRESH}, exclusive, periodic, at least
 * once - so every engine rule is the reactor's. Porting an old {@code EventProcessor} is a superclass swap.
 * <pre>{@code
 * public class OrderNotifier extends EventLogReactor {
 *     public OrderNotifier(EventLogProcessorDependencies dependencies, Mailer mailer) {
 *         super("order-notifier", List.of("Orders"), dependencies);
 *         this.mailer = mailer;
 *     }
 *
 *     @MessageHandler
 *     void on(OrderPlaced placed, DeliveredEvent event) {
 *         mailer.orderConfirmation(placed.orderId(), event.eventId());
 *     }
 * }
 * }</pre>
 * <ul>
 *     <li>A handler takes the payload, optionally a {@link DeliveredEvent} second parameter; the most specific payload type
 *     wins. A payload with no handler counts as handled.</li>
 *     <li>No transaction surrounds a handler, and an event may be delivered more than once (after a retry or a crash):
 *     make the side effect idempotent.</li>
 *     <li>A handler's exception is the engine's: retried, the page shrunk, the event parked ({@link ParkEventException}
 *     parks at once). Nothing is caught here.</li>
 *     <li>Not carried over from the old processors: {@code MessageHandlerInterceptor}s, {@link MessageHandler#unitOfWork()},
 *     the built-in permanent-error list, inbox redelivery, Reactor and the {@code FencedLock}.</li>
 *     <li>An aggregate type not yet registered with the {@link EventLog} is registered (default tables) at {@link #start()}.</li>
 * </ul>
 * Started and stopped as a foundation {@link Lifecycle} - by the Spring Boot starter's lifecycle manager, or by hand.
 */
public abstract class EventLogReactor implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(EventLogReactor.class);

    private final String                        name;
    private final List<String>                  aggregateTypes;
    private final EventLogProcessorDependencies dependencies;
    private final MessageHandlerDispatch        dispatch;
    private ProcessorSeams                      seams = ProcessorSeams.NONE;
    private final LifecycleGate                 gate = new LifecycleGate();
    private volatile List<EventReactor>         reactors;
    private volatile boolean                    started;

    /**
     * @param name           the processor (subscriber) name, the same for each aggregate type's subscription
     * @param aggregateTypes the aggregate types to follow: one subscription each, with the same handlers, started and stopped together
     * @param dependencies   the event log and serializer (its page transactions are not used)
     */
    protected EventLogReactor(String name, List<String> aggregateTypes, EventLogProcessorDependencies dependencies) {
        this.name = requireNonBlank(name, "No name provided");
        this.aggregateTypes = List.copyOf(requireNonEmpty(aggregateTypes, "No aggregateTypes provided"));
        this.dependencies = requireNonNull(dependencies, "No dependencies provided");
        this.dispatch = new MessageHandlerDispatch(this);
    }

    /** {@link ConsistencyMode#FRESH} unless overridden */
    protected ConsistencyMode consistency() {
        return ConsistencyMode.FRESH;
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
     * delivery threads, so a handler that reads this processor ({@link #reactors()}, {@link #isStarted()}) never waits behind a stop joining its own thread
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
            if (reactors == null) {
                reactors = build();
            }
            for (var aggregateType : aggregateTypes) {
                dependencies.eventLog().registerAggregateType(aggregateType);
            }
            for (var reactor : reactors) {
                reactor.start();
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

    /** @return one reactor per aggregate type, in {@link #aggregateTypes()} order (for keys, handles, reset); empty before the first start */
    public List<EventReactor> reactors() {
        return reactors == null ? List.of() : reactors;
    }

    /** Test-only: mutants and subscribe path, before the first {@link #start()} */
    void seams(ProcessorSeams seams) {
        try (var ignored = gate.enter()) {
            requireTrue(reactors == null, "Seams must be set before the first start");
            this.seams = requireNonNull(seams, "No seams provided");
        }
    }

    private List<EventReactor> build() {
        var built = new ArrayList<EventReactor>(aggregateTypes.size());
        for (var aggregateType : aggregateTypes) {
            var builder = EventReactor.builder(name, aggregateType)
                                        .on(Object.class, this::handle)
                                        .consistency(consistency())
                                        .startFrom(startFrom())
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
        if (reactors == null) {
            return;
        }
        for (var reactor : reactors) {
            try {
                reactor.stop();
            } catch (RuntimeException e) {
                log.warn("[{}] Failed to stop {}", name, reactor, e);
            }
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + " on " + aggregateTypes + "]";
    }
}
