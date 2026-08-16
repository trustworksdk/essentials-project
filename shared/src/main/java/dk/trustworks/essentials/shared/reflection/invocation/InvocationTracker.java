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

import java.lang.reflect.Method;
import java.time.Duration;

public interface InvocationTracker {

    /**
     * The neutral default: tracks nothing.
     * <p>
     * {@link PatternMatchingMethodInvoker} has always substituted a {@link NoOpInvocationTracker} for an absent
     * tracker; this exposes that fallback as a value, so a caller with nothing to track passes
     * {@code InvocationTracker.noOp()} rather than an empty {@code Optional}. The instance is shared — it is
     * stateless.
     *
     * @return a tracker that does nothing — never {@code null}
     */
    static InvocationTracker noOp() {
        return NoOpInvocationTracker.INSTANCE;
    }

    /**
     * Track that the method had been invocation
     *
     * @param method          the invoked method
     * @param invokeMethodsOn The object where the <code>method</code> was invoked on
     * @param duration        the duration of the invocation
     * @param argument        the argument passed to the method
     */
    void trackMethodInvoked(Method method, Object invokeMethodsOn, Duration duration, Object argument);

    class NoOpInvocationTracker implements InvocationTracker {
        /**
         * The shared instance handed out by {@link InvocationTracker#noOp()}. Safe to share — this tracker holds no
         * state.
         */
        static final NoOpInvocationTracker INSTANCE = new NoOpInvocationTracker();

        @Override
        public void trackMethodInvoked(Method method, Object invokeMethodsOn, Duration duration, Object argument) {
        }
    }
}
