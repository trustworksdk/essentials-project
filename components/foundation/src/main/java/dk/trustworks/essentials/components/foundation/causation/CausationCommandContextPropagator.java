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

import dk.trustworks.essentials.reactive.command.*;

import java.util.concurrent.Callable;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Carries the {@link CausationContext} cause from the thread that sends a command to the Reactor worker that handles it,
 * for {@link CommandBus#sendAsync(Object)} and {@link CommandBus#sendAndDontWait(Object)} on a {@link LocalCommandBus}
 * (and {@code sendAsync} on a {@code DurableLocalCommandBus}, whose {@code sendAndDontWait} goes through a durable
 * queue and is carried by the {@code CausationDurableQueuesInterceptor} instead).
 * <p>
 * Register it with {@link AbstractCommandBus#addContextPropagator(CommandContextPropagator)}. The Spring Boot starter
 * does so for every command bus bean unless {@code essentials.eventstore.causation.enabled=false}.
 * <p>
 * The cause is captured when the command is <em>sent</em>, so a {@code sendAsync} {@code Mono} carries it even when it
 * is subscribed after the sender's binding has ended. "No cause" is carried as "no cause".
 */
public final class CausationCommandContextPropagator implements CommandContextPropagator {
    @Override
    public <R> Callable<R> propagate(Callable<R> handlerInvocation) {
        requireNonNull(handlerInvocation, "No handlerInvocation provided");
        var cause = CausationContext.current();
        return () -> CausationContext.where(cause).call(handlerInvocation::call);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
