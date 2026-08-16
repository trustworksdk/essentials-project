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

import dk.trustworks.essentials.shared.reflection.invocation.test_subjects.*;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link PatternMatchingMethodInvoker#builder()} and the neutral defaults that replaced the two
 * {@code Optional} constructor parameters — including that the deprecated {@code Optional}-taking constructor still
 * behaves identically to the replacement it delegates to.
 */
class PatternMatchingMethodInvokerBuilderTest {

    @Test
    void the_builder_defaults_to_ignoring_unmatched_arguments_and_tracking_nothing() {
        var testSubject = new OrderEventHandlerWithoutFallback();

        var invoker = PatternMatchingMethodInvoker.<OrderEvent>builder()
                                                  .setInvokeMethodsOn(testSubject)
                                                  .setMethodPatternMatcher(new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class,
                                                                                                                             OrderEvent.class))
                                                  .setInvocationStrategy(InvocationStrategy.InvokeMostSpecificTypeMatched)
                                                  .build();

        // OrderAccepted has no handler and there is no fallback — the default handler swallows it silently
        invoker.invoke(new OrderAccepted("1"));

        assertThat(testSubject.methodCalledWithArgument).isEmpty();
    }

    @Test
    void a_configured_no_matching_methods_handler_is_called() {
        var testSubject              = new OrderEventHandlerWithoutFallback();
        var noMatchingMethodsHandler = new TestNoMatchingMethodsHandler();
        var orderAccepted            = new OrderAccepted("1");

        var invoker = PatternMatchingMethodInvoker.<OrderEvent>builder()
                                                  .setInvokeMethodsOn(testSubject)
                                                  .setMethodPatternMatcher(new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class,
                                                                                                                             OrderEvent.class))
                                                  .setInvocationStrategy(InvocationStrategy.InvokeMostSpecificTypeMatched)
                                                  .setDefaultNoMatchingMethodsHandler(noMatchingMethodsHandler)
                                                  .build();

        invoker.invoke(orderAccepted);

        assertThat(noMatchingMethodsHandler.calledWithArgument).isSameAs(orderAccepted);
    }

    @Test
    void an_empty_optional_setter_restores_the_neutral_default() {
        var testSubject              = new OrderEventHandlerWithoutFallback();
        var noMatchingMethodsHandler = new TestNoMatchingMethodsHandler();

        var invoker = PatternMatchingMethodInvoker.<OrderEvent>builder()
                                                  .setInvokeMethodsOn(testSubject)
                                                  .setMethodPatternMatcher(new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class,
                                                                                                                             OrderEvent.class))
                                                  .setInvocationStrategy(InvocationStrategy.InvokeMostSpecificTypeMatched)
                                                  .setDefaultNoMatchingMethodsHandler(noMatchingMethodsHandler)
                                                  .setDefaultNoMatchingMethodsHandler(Optional.empty())
                                                  .build();

        invoker.invoke(new OrderAccepted("1"));

        assertThat(noMatchingMethodsHandler.calledWithArgument).isNull();
    }

    @Test
    void the_deprecated_optional_constructor_delegates_to_the_replacement() {
        var testSubject              = new OrderEventHandlerWithoutFallback();
        var noMatchingMethodsHandler = new TestNoMatchingMethodsHandler();
        var orderAccepted            = new OrderAccepted("1");

        @SuppressWarnings("removal")
        var deprecated = new PatternMatchingMethodInvoker<>(testSubject,
                                                            new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class,
                                                                                                              OrderEvent.class),
                                                            InvocationStrategy.InvokeMostSpecificTypeMatched,
                                                            Optional.of(noMatchingMethodsHandler),
                                                            Optional.empty());

        deprecated.invoke(orderAccepted);

        assertThat(noMatchingMethodsHandler.calledWithArgument).isSameAs(orderAccepted);
    }

    @Test
    void a_configured_invocation_tracker_sees_every_dispatch() {
        var testSubject = new OrderEventHandlerWithoutFallback();
        var tracker     = new RecordingInvocationTracker();

        var invoker = PatternMatchingMethodInvoker.<OrderEvent>builder()
                                                  .setInvokeMethodsOn(testSubject)
                                                  .setMethodPatternMatcher(new SingleArgumentAnnotatedMethodPatternMatcher<>(EventHandler.class,
                                                                                                                             OrderEvent.class))
                                                  .setInvocationStrategy(InvocationStrategy.InvokeMostSpecificTypeMatched)
                                                  .setInvocationTracker(tracker)
                                                  .build();

        invoker.invoke(new OrderCreated("1"));

        assertThat(tracker.invocations).isEqualTo(1);
    }

    private static final class RecordingInvocationTracker implements InvocationTracker {
        private int invocations;

        @Override
        public void trackMethodInvoked(java.lang.reflect.Method method, Object invokeMethodsOn, java.time.Duration duration, Object argument) {
            invocations++;
        }
    }

    private static final class TestNoMatchingMethodsHandler implements NoMatchingMethodsHandler {
        private Object calledWithArgument;

        @Override
        public void noMatchesFor(Object argument) {
            calledWithArgument = argument;
        }
    }
}
