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

package dk.trustworks.essentials.components.foundation.causation;

import dk.trustworks.essentials.components.foundation.types.EventId;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Carries "which event caused the work currently being done" from the place that knows it - typically the
 * delivery of a persisted event to a handler - to the place that writes new events, so each new event can record
 * the {@link EventId} of the event that caused it.
 * <p>
 * The cause is bound for the <em>dynamic extent of a call</em>, using a {@link ScopedValue}:
 * <pre>{@code
 * CausationContext.where(persistedEvent.eventId())
 *                 .run(() -> handler.handle(event));
 * }</pre>
 * Everything that call does on the same thread - including committing a UnitOfWork it opens - sees the cause via
 * {@link #current()}. The binding ends when the call returns, so it can never leak into a pooled thread's next task.
 * <p>
 * <b>What a binding does not reach:</b> work handed to another thread (an executor, a Reactor scheduler) and work
 * handed to another process or a durable queue. Durable queues carry the cause in message metadata and re-bind it
 * on delivery; work handed to an executor must capture {@link #current()} and re-bind it with
 * {@link #where(Optional)}.
 * <p>
 * <b>Bindings nest, and the innermost one wins.</b> An explicit binding inside a handler overrides the cause the
 * framework bound around that handler. Binding {@link Optional#empty()} binds <em>"no cause"</em>, which also hides
 * any outer binding - so code that captured "there was no cause" and re-binds it later never inherits some
 * unrelated outer cause.
 * <p>
 * When nothing is bound, {@link #current()} is empty and events are written without a cause, which is always legal.
 * <p>
 * Deciders and other pure domain functions are never given the cause; it is read only by framework components and by
 * application code that chooses to.
 */
public final class CausationContext {
    private static final ScopedValue<Optional<EventId>> CAUSED_BY = ScopedValue.newInstance();

    private CausationContext() {
    }

    /**
     * The cause bound for the current call, if any
     *
     * @return the {@link EventId} of the event that caused the work currently being done, or {@link Optional#empty()}
     * if no cause is bound or "no cause" is bound explicitly
     */
    public static Optional<EventId> current() {
        return CAUSED_BY.orElse(Optional.empty());
    }

    /**
     * Prepare a binding of the given cause, to be applied with {@link Binding#run(Runnable)} or
     * {@link Binding#call(ScopedValue.CallableOp)}
     *
     * @param causedBy the {@link EventId} of the event that caused the work about to be done
     * @return the binding
     */
    public static Binding where(EventId causedBy) {
        return new Binding(Optional.of(requireNonNull(causedBy, "No causedBy provided")));
    }

    /**
     * Prepare a binding of a cause that may be absent - typically one captured earlier with {@link #current()}.
     * Binding {@link Optional#empty()} binds "no cause", hiding any outer binding.
     *
     * @param causedBy the {@link EventId} of the event that caused the work about to be done, or
     *                 {@link Optional#empty()} for "no cause"
     * @return the binding
     */
    public static Binding where(Optional<EventId> causedBy) {
        return new Binding(requireNonNull(causedBy, "No causedBy provided"));
    }

    /**
     * A cause ready to be bound around a call
     */
    public static final class Binding {
        private final Optional<EventId> causedBy;

        private Binding(Optional<EventId> causedBy) {
            this.causedBy = causedBy;
        }

        /**
         * Run the action with this cause bound
         *
         * @param action the action
         */
        public void run(Runnable action) {
            requireNonNull(action, "No action provided");
            ScopedValue.where(CAUSED_BY, causedBy).run(action);
        }

        /**
         * Call the operation with this cause bound and return its result. Any exception the operation throws,
         * checked or not, propagates unchanged.
         *
         * @param operation the operation
         * @param <R>       the result type
         * @param <X>       the exception type the operation may throw
         * @return the operation's result
         * @throws X if the operation throws
         */
        public <R, X extends Throwable> R call(ScopedValue.CallableOp<? extends R, X> operation) throws X {
            requireNonNull(operation, "No operation provided");
            return ScopedValue.where(CAUSED_BY, causedBy).call(operation);
        }

        /**
         * @return the cause this binding binds, or {@link Optional#empty()} for "no cause"
         */
        public Optional<EventId> causedBy() {
            return causedBy;
        }

        @Override
        public String toString() {
            return "CausationContext.Binding{causedBy=" + causedBy.map(EventId::toString).orElse("<none>") + '}';
        }
    }
}
