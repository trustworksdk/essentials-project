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

package dk.trustworks.essentials.reactive.command;

import java.util.concurrent.Callable;

/**
 * Carries context from the thread that sends a command to the thread that handles it, for the
 * {@link CommandBus} methods that hand the handler to another thread: {@link CommandBus#sendAsync(Object)} and
 * {@link CommandBus#sendAndDontWait(Object)} run the handler on a Reactor worker, where anything bound to the sending
 * thread - a {@code ScopedValue}, a {@code ThreadLocal} - is gone. {@link CommandBus#send(Object)} runs the handler on
 * the caller's thread and needs no propagation.
 * <p>
 * {@link #propagate(Callable)} is called <b>on the sending thread</b>, when the command is sent. An implementation
 * captures what it needs there and returns an invocation that restores it around the handler, on whichever thread that
 * invocation later runs:
 * <pre>{@code
 * public <R> Callable<R> propagate(Callable<R> handlerInvocation) {
 *     var captured = CONTEXT.get();
 *     return () -> runWith(captured, handlerInvocation);
 * }
 * }</pre>
 * Register one with {@link AbstractCommandBus#addContextPropagator(CommandContextPropagator)}. Several compose: the one
 * added first wraps outermost.
 */
public interface CommandContextPropagator {
    /**
     * Called on the sending thread. Capture the context to carry and return an invocation that restores it around
     * {@code handlerInvocation}.
     *
     * @param handlerInvocation the invocation of the command handler (including the {@link CommandBusInterceptor} chain)
     * @param <R>               the result type
     * @return the invocation to run on the handling thread
     */
    <R> Callable<R> propagate(Callable<R> handlerInvocation);
}
