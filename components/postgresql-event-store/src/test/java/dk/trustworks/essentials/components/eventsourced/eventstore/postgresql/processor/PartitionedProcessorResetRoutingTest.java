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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that every {@code reset*}/{@code doReset*} method the partitioned processors inherit or declare is declared
 * {@code final} in the processor itself, so a subclass cannot override one of the processor's reset methods.
 * <p>
 * A new {@code reset*}/{@code doReset*} method on {@link AbstractEventProcessor} or {@link EventProcessor} fails this
 * test until it is overridden {@code final} in each partitioned processor that inherits it; that override must route onto
 * {@link StoppedEverywhereReset}, which this test does not check. Subclasses customise a reset through
 * {@link AbstractEventProcessor#onSubscriptionsReset}, which the shared reset calls per aggregate type.
 */
class PartitionedProcessorResetRoutingTest {

    @ParameterizedTest
    @ValueSource(classes = {PartitionedEventProcessor.class, PartitionedViewEventProcessor.class})
    void every_reset_method_is_final_in_the_partitioned_processor_itself(Class<?> processorClass) {
        var resetMethods = resetMethodsVisibleIn(processorClass);
        assertThat(resetMethods).as("reset methods found on %s", processorClass.getSimpleName())
                                .isNotEmpty();

        var notFinalHere = resetMethods.stream()
                                       .filter(signature -> !isDeclaredFinalIn(processorClass, signature))
                                       .map(Signature::toString)
                                       .sorted()
                                       .toList();
        assertThat(notFinalHere).as("reset methods %s must override as final and route onto StoppedEverywhereReset",
                                    processorClass.getSimpleName())
                                .isEmpty();
    }

    /**
     * Every non-private, non-static {@code reset*}/{@code doReset*} method declared in the class or any superclass.
     */
    private static Set<Signature> resetMethodsVisibleIn(Class<?> processorClass) {
        var signatures = new HashSet<Signature>();
        for (Class<?> type = processorClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                var modifiers = method.getModifiers();
                if (method.isSynthetic() || Modifier.isPrivate(modifiers) || Modifier.isStatic(modifiers)) {
                    continue;
                }
                if (method.getName().startsWith("reset") || method.getName().startsWith("doReset")) {
                    signatures.add(new Signature(method.getName(), List.of(method.getParameterTypes())));
                }
            }
        }
        return signatures;
    }

    private static boolean isDeclaredFinalIn(Class<?> processorClass, Signature signature) {
        try {
            var method = processorClass.getDeclaredMethod(signature.name(), signature.parameterTypes().toArray(Class<?>[]::new));
            return Modifier.isFinal(method.getModifiers());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private record Signature(String name, List<Class<?>> parameterTypes) {
        @Override
        public String toString() {
            return name + parameterTypes.stream().map(Class::getSimpleName).toList();
        }
    }
}
