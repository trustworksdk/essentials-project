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

package dk.trustworks.essentials.components.eventsourced.aggregates.projection;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.shared.reflection.*;
import dk.trustworks.essentials.shared.reflection.invocation.*;
import dk.trustworks.essentials.shared.types.GenericType;

import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * An {@link InMemoryProjector} that uses annotation-based event handlers to project events
 * onto a plain Java object (POJO) projection class.<br>
 * <br>
 * This projector allows you to define projection classes that don't have to extend any base class.
 * Instead, you annotate projection class methods with {@link EventHandler} to handle specific event types.
 * <p>
 * <b>Projection class requirements:</b>
 * <ul>
 *   <li>Must have a public no-argument constructor</li>
 *   <li>Must have at least one method annotated with {@link EventHandler}</li>
 *   <li>Each {@link EventHandler} method must take exactly one parameter (the event)</li>
 * </ul>
 * <p>
 * <b>Example projection class:</b>
 * <pre>{@code
 * public class OrderSummary {
 *     private OrderId orderId;
 *     private CustomerId customerId;
 *     private List<ProductId> products = new ArrayList<>();
 *     private boolean accepted;
 *
 *     public OrderSummary() {
 *         // Required no-argument constructor
 *     }
 *
 *     @EventHandler
 *     private void on(OrderAdded event) {
 *         this.orderId = event.orderId();
 *         this.customerId = event.customerId();
 *     }
 *
 *     @EventHandler
 *     private void on(ProductAddedToOrder event) {
 *         products.add(event.productId());
 *     }
 *
 *     @EventHandler
 *     private void on(OrderAccepted event) {
 *         this.accepted = true;
 *     }
 *
 *     // Getters...
 *     public OrderId getOrderId() { return orderId; }
 *     public CustomerId getCustomerId() { return customerId; }
 *     public List<ProductId> getProducts() { return products; }
 *     public boolean isAccepted() { return accepted; }
 * }
 * }</pre>
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * // Register the projector with the event store
 * eventStore.addGenericInMemoryProjector(new AnnotationBasedInMemoryProjector());
 *
 * // Perform an in-memory projection
 * Optional<OrderSummary> summary = eventStore.inMemoryProjection(
 *     AggregateType.of("Orders"),
 *     orderId,
 *     OrderSummary.class
 * );
 *
 * summary.ifPresent(s -> {
 *     System.out.println("Order: " + s.getOrderId());
 *     System.out.println("Products: " + s.getProducts().size());
 *     System.out.println("Accepted: " + s.isAccepted());
 * });
 * }</pre>
 * <p>
 * <b>Event matching:</b><br>
 * The projector uses {@link InvocationStrategy#InvokeMostSpecificTypeMatched}, which means
 * that if you have handlers for both a base event type and a specific event type,
 * only the most specific matching handler will be invoked.<br>
 * Events without a matching handler are silently ignored.
 * <p>
 * <b>Note:</b> An in-memory projection is not automatically associated with a {@link UnitOfWork}
 * and any changes to the projection won't automatically be persisted.
 *
 * @see InMemoryProjector
 * @see EventHandler
 */
public class AnnotationBasedInMemoryProjector implements InMemoryProjector {
    /**
     * Cache for projection type support check.
     * Key: projection class
     * Value: true if the class has at least one @EventHandler method with single parameter
     */
    private final ConcurrentMap<Class<?>, Boolean> supportsCache = new ConcurrentHashMap<>();

    @Override
    public boolean supports(Class<?> projectionType) {
        requireNonNull(projectionType, "No projectionType provided");
        return supportsCache.computeIfAbsent(projectionType, this::hasEventHandlerMethods);
    }

    private boolean hasEventHandlerMethods(Class<?> projectionType) {
        return Methods.methods(projectionType).stream()
                      .anyMatch(method -> method.isAnnotationPresent(EventHandler.class) && method.getParameterCount() == 1);
    }

    @Override
    public <ID, PROJECTION> Optional<PROJECTION> projectEvents(AggregateType aggregateType,
                                                               ID aggregateId,
                                                               Class<PROJECTION> projectionType,
                                                               EventStore eventStore) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(aggregateId, "No aggregateId provided");
        requireNonNull(projectionType, "No projectionType provided");
        requireNonNull(eventStore, "No eventStore instance provided");

        if (!supports(projectionType)) {
            throw new IllegalArgumentException(
                    msg("The provided projectionType '{}' isn't supported. Ensure it has at least one @EventHandler annotated method with a single parameter.",
                        projectionType.getName()));
        }

        // Fetch the event stream for the aggregate
        var eventStream = eventStore.fetchStream(aggregateType, aggregateId);

        if (eventStream.isEmpty()) {
            return Optional.empty();
        }

        // Create projection instance via no-arg constructor
        PROJECTION projection = createProjectionInstance(projectionType);

        // Create a new invoker for this specific projection instance
        // Note: We cannot cache the invoker since it stores the target object internally
        var invoker = new PatternMatchingMethodInvoker<>(
                projection,
                new SingleArgumentAnnotatedMethodPatternMatcher<>(
                        EventHandler.class,
                        new GenericType<>() {
                        }
                ),
                InvocationStrategy.InvokeMostSpecificTypeMatched
        );

        // Apply each event to the projection
        eventStream.get().eventList().forEach(persistedEvent -> {
            Object event = persistedEvent.event().deserialize();
            invoker.invoke(event, unmatchedEvent -> {
                // Silently ignore events without matching handlers
            });
        });

        return Optional.of(projection);
    }

    /**
     * Creates a new instance of the projection type using its no-argument constructor.
     *
     * @param projectionType the projection class to instantiate
     * @param <PROJECTION>   the projection type
     * @return a new instance of the projection
     * @throws IllegalArgumentException if the projection type doesn't have a no-argument constructor
     * @throws RuntimeException         if instantiation fails
     */
    private <PROJECTION> PROJECTION createProjectionInstance(Class<PROJECTION> projectionType) {
        var reflector                = Reflector.reflectOn(projectionType);
        var defaultNoArgsConstructor = reflector.getDefaultConstructor();
        if (defaultNoArgsConstructor.isPresent() && Modifier.isPublic(defaultNoArgsConstructor.get().getModifiers())) {
            return reflector.newInstance();
        } else {
            throw new IllegalArgumentException(msg("Projection type '{}' must have a no-argument constructor", projectionType.getName()));
        }
    }
}
