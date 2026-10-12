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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.AppendToStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Hands the {@link PersistedEvent}s appended during an action to the caller that ran it - so the caller has a
 * {@link GlobalEventOrder} to wait for, e.g. with an event processor's {@code awaitProcessed(...)}:
 * <pre>{@code
 * var recorded = AppendedEvents.recordDuring(() -> commandBus.send(new PlaceOrder(orderId)));
 * orderViewProcessor.awaitProcessed(recorded.lastAppendedEvent().orElseThrow(), Duration.ofSeconds(10));
 * }</pre>
 * Every event {@link PostgresqlEventStore#appendToStream(AppendToStream)} persists is recorded, once, whoever appends
 * it: a {@code StatefulAggregateRepository}, an {@code EventStreamDecider} or decider {@code CommandHandler}, a Kotlin
 * decider, or code calling {@code eventStore.appendToStream(...)} directly. An {@link EventStore} that decorates the
 * {@link PostgresqlEventStore} (e.g. the {@code CdcEventStore}) appends through it, so its appends are recorded once too.
 * <p>
 * The recording covers the dynamic extent of the action on the calling thread (a {@link ScopedValue}), so it sees the
 * appends of {@code CommandBus.send(...)}, which runs the handler on the caller's thread, and the appends of synchronous
 * subscribers that react on that thread during the action (e.g. an event-bus subscriber or in-transaction handler that
 * appends). It does not see a command sent with {@code sendAsync} / {@code sendAndDontWait}, whose handler runs on
 * another thread, nor any other append made on another thread. A repository that appends when its {@code UnitOfWork}
 * commits (e.g. {@code StatefulAggregateRepository}) is recorded only when that commit happens during the action - as it
 * does when the command bus's {@code UnitOfWorkControllingCommandBusInterceptor} created the {@code UnitOfWork}.
 * Recordings nest: an outer recording also sees what an inner one recorded.
 * <p>
 * An appended event's {@link PersistedEvent#globalEventOrder()} is assigned when it is appended, before its
 * {@code UnitOfWork} commits, and an event whose {@code UnitOfWork} rolls back stays recorded. Await it once that
 * {@code UnitOfWork} has committed - which, when the command bus's {@code UnitOfWorkControllingCommandBusInterceptor}
 * created it, is when {@code send} returns.
 */
public final class AppendedEvents {
    private static final ScopedValue<Recorder> RECORDER = ScopedValue.newInstance();

    private AppendedEvents() {
    }

    /**
     * Run the action and record every event appended to the {@link PostgresqlEventStore} during it on this thread
     *
     * @param action the action, e.g. {@code () -> commandBus.send(command)}
     * @param <R>    the action's result type
     * @return the action's result and the events appended during it, in the order they were appended
     */
    public static <R> Recorded<R> recordDuring(Supplier<R> action) {
        requireNonNull(action, "No action provided");
        var recorder = new Recorder(RECORDER.isBound() ? RECORDER.get() : null);
        var result   = ScopedValue.where(RECORDER, recorder).call(action::get);
        return new Recorded<>(result, List.copyOf(recorder.appended));
    }

    /**
     * Called by {@link PostgresqlEventStore#appendToStream(AppendToStream)} with the events it has just persisted - the
     * one place every append passes, whoever started it and whichever {@link EventStore} decorators it went through
     *
     * @param events the events persisted, in the order they were appended
     */
    static void appended(List<PersistedEvent> events) {
        if (RECORDER.isBound()) {
            var recorder = RECORDER.get();
            events.forEach(recorder::record);
        }
    }

    /**
     * The outcome of {@link #recordDuring(Supplier)}
     *
     * @param result         the action's result (for {@code commandBus.send}: what the handler returned - the event, for an {@code EventStreamDecider})
     * @param appendedEvents the events appended during the action, in the order they were appended
     * @param <R>            the action's result type
     */
    public record Recorded<R>(R result, List<PersistedEvent> appendedEvents) {
        /**
         * @return the last event appended during the action, if any
         */
        public Optional<PersistedEvent> lastAppendedEvent() {
            return appendedEvents.isEmpty() ? Optional.empty() : Optional.of(appendedEvents.getLast());
        }

        /**
         * @param aggregateType the aggregate type
         * @return the highest {@link GlobalEventOrder} appended for the aggregate type during the action, if any
         */
        public Optional<GlobalEventOrder> highestGlobalEventOrder(AggregateType aggregateType) {
            requireNonNull(aggregateType, "No aggregateType provided");
            return appendedEvents.stream()
                                 .filter(event -> event.aggregateType().equals(aggregateType))
                                 .map(PersistedEvent::globalEventOrder)
                                 .max(Comparator.comparingLong(GlobalEventOrder::longValue));
        }
    }

    private static final class Recorder {
        private final Recorder             parent;
        private final List<PersistedEvent> appended = new CopyOnWriteArrayList<>();

        private Recorder(Recorder parent) {
            this.parent = parent;
        }

        void record(PersistedEvent event) {
            appended.add(event);
            if (parent != null) {
                parent.record(event);
            }
        }
    }
}
