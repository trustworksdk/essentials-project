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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

import java.lang.annotation.*;

/**
 * Declares the event types a {@link ViewEventProcessor} deliberately has no {@link MessageHandler} for (CB-4, 0.60.1).
 * <p>
 * A {@link ViewEventProcessor} that is delivered an event none of its {@link MessageHandler} methods accepts logs a
 * WARN (at most once an hour per event type), because a view that silently skips an event type does not see every
 * {@code EventOrder} of the aggregate: a projection that checks the order is contiguous breaks, and the view is
 * incomplete without anyone noticing. At start it also WARNs about the types a sealed event type permits that no
 * {@link MessageHandler} accepts. An event type listed here is still counted
 * ({@code EventStoreSubscriptionObserver#eventTypeNotHandledByProcessor}, the
 * {@code essentials.eventstore.processor.unhandled_event_type} counter) but neither WARN mentions it.
 * <pre>{@code
 * @IgnoresEventTypes({OrderShipped.class, OrderLineEvent.class})
 * public class OrderSummaryView extends ViewEventProcessor { ... }
 * }</pre>
 * A listed type covers its subtypes, so listing a sealed (or any other) supertype ignores every event that implements
 * it. {@link #eventNames()} matches events persisted by event name only, without a Java type.
 * <p>
 * {@link EventProcessor} and {@link InTransactionEventProcessor} log an ignored event type at DEBUG only - skipping
 * events is normal there - so they need no annotation; it is read from them too, but changes nothing they log.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface IgnoresEventTypes {
    /**
     * @return the event types (and, through them, their subtypes) the processor deliberately does not handle
     */
    Class<?>[] value() default {};

    /**
     * @return the event names, for events persisted by name only (no Java type), the processor deliberately does not handle
     */
    String[] eventNames() default {};
}
