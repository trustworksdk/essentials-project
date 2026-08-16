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

package dk.trustworks.essentials.shared.reflection.invocation;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link PatternMatchingMethodInvoker}, obtained from {@link PatternMatchingMethodInvoker#builder()}.
 * <p>
 * The two optional collaborators start out at their neutral defaults — {@link NoMatchingMethodsHandler#ignore()} and
 * {@link InvocationTracker#noOp()} — so a caller only sets what it actually wants to change:
 * <pre>{@code
 * var invoker = PatternMatchingMethodInvoker.<OrderEvent>builder()
 *                                           .setInvokeMethodsOn(new OrderEventsHandler())
 *                                           .setMethodPatternMatcher(new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class,
 *                                                                                                                      new GenericType<OrderEvent>() {}))
 *                                           .setInvocationStrategy(InvocationStrategy.InvokeMostSpecificTypeMatched)
 *                                           .setInvocationTracker(new MeasurementInvocationTracker(measurementTaker))
 *                                           .build();
 * }</pre>
 *
 * @param <ARGUMENT_COMMON_ROOT_TYPE> The method argument common root type — see {@link PatternMatchingMethodInvoker}
 */
public final class PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> {
    private Object                                          invokeMethodsOn;
    private MethodPatternMatcher<ARGUMENT_COMMON_ROOT_TYPE> methodPatternMatcher;
    private InvocationStrategy                              invocationStrategy;
    private NoMatchingMethodsHandler                        defaultNoMatchingMethodsHandler = NoMatchingMethodsHandler.ignore();
    private InvocationTracker                               invocationTracker               = InvocationTracker.noOp();

    /**
     * @param invokeMethodsOn The object that contains the methods that we will perform pattern matching and invoke methods on. Required
     * @return this builder instance for fluent chaining
     */
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setInvokeMethodsOn(Object invokeMethodsOn) {
        this.invokeMethodsOn = invokeMethodsOn;
        return this;
    }

    /**
     * @param methodPatternMatcher The strategy that determines the methods that can be invoked, which type of argument each
     *                             supports, and how the method is later invoked. Required
     * @return this builder instance for fluent chaining
     */
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setMethodPatternMatcher(MethodPatternMatcher<ARGUMENT_COMMON_ROOT_TYPE> methodPatternMatcher) {
        this.methodPatternMatcher = methodPatternMatcher;
        return this;
    }

    /**
     * @param invocationStrategy Determines which of the methods matching an argument are invoked. Required
     * @return this builder instance for fluent chaining
     */
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setInvocationStrategy(InvocationStrategy invocationStrategy) {
        this.invocationStrategy = invocationStrategy;
        return this;
    }

    /**
     * @param defaultNoMatchingMethodsHandler Called when {@link PatternMatchingMethodInvoker#invoke(Object)} is handed an argument
     *                                        matching no method. Defaults to {@link NoMatchingMethodsHandler#ignore()}
     * @return this builder instance for fluent chaining
     */
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setDefaultNoMatchingMethodsHandler(NoMatchingMethodsHandler defaultNoMatchingMethodsHandler) {
        this.defaultNoMatchingMethodsHandler = requireNonNull(defaultNoMatchingMethodsHandler, "No defaultNoMatchingMethodsHandler provided - use NoMatchingMethodsHandler.ignore() to ignore unmatched arguments");
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setDefaultNoMatchingMethodsHandler(NoMatchingMethodsHandler)}, for callers
     * that already hold an {@code Optional}. An empty {@code Optional} restores the
     * {@link NoMatchingMethodsHandler#ignore()} default.
     *
     * @param defaultNoMatchingMethodsHandler the handler, or empty for the default
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setDefaultNoMatchingMethodsHandler(Optional<NoMatchingMethodsHandler> defaultNoMatchingMethodsHandler) {
        requireNonNull(defaultNoMatchingMethodsHandler, "No defaultNoMatchingMethodsHandler provided");
        return setDefaultNoMatchingMethodsHandler(defaultNoMatchingMethodsHandler.orElseGet(NoMatchingMethodsHandler::ignore));
    }

    /**
     * @param invocationTracker Notified of every dispatched method invocation. Defaults to {@link InvocationTracker#noOp()}.
     *                          A tracker that also implements {@link LoggerAwareInvocationTracker} has its logger set during
     *                          {@link #build()}
     * @return this builder instance for fluent chaining
     */
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setInvocationTracker(InvocationTracker invocationTracker) {
        this.invocationTracker = requireNonNull(invocationTracker, "No invocationTracker provided - use InvocationTracker.noOp() to track nothing");
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setInvocationTracker(InvocationTracker)}, for callers that already hold an
     * {@code Optional}. An empty {@code Optional} restores the {@link InvocationTracker#noOp()} default.
     *
     * @param invocationTracker the tracker, or empty for the default
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public PatternMatchingMethodInvokerBuilder<ARGUMENT_COMMON_ROOT_TYPE> setInvocationTracker(Optional<InvocationTracker> invocationTracker) {
        requireNonNull(invocationTracker, "No invocationTracker provided");
        return setInvocationTracker(invocationTracker.orElseGet(InvocationTracker::noOp));
    }

    /**
     * Builds the {@link PatternMatchingMethodInvoker}, which scans {@code invokeMethodsOn} for invokable methods
     * immediately.
     *
     * @return the new invoker
     */
    public PatternMatchingMethodInvoker<ARGUMENT_COMMON_ROOT_TYPE> build() {
        return new PatternMatchingMethodInvoker<>(invokeMethodsOn,
                                                  methodPatternMatcher,
                                                  invocationStrategy,
                                                  defaultNoMatchingMethodsHandler,
                                                  invocationTracker);
    }
}
