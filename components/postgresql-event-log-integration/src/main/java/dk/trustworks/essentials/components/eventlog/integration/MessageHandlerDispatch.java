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
package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.shared.reflection.invocation.*;

import java.lang.reflect.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@link MessageHandler} dispatch of {@link EventLogViewProcessor} and {@link EventLogReactor}: a handler method
 * takes the payload, optionally a {@link DeliveredEvent} second parameter; the most specific payload type wins; a
 * payload with no handler is ignored (counted handled).
 * <p>
 * Built on the {@link PatternMatchingMethodInvoker} that foundation's {@code PatternMatchingMessageHandler} uses, with
 * this module's method pattern, because {@code PatternMatchingMessageHandler} itself fits neither half of the contract:
 * it accepts only a {@code Message} as the second parameter, and wraps a handler's exception in a
 * {@code ReflectionException}, which would hide a {@code ParkEventException} and the failure's class from the engine.
 * Here the handler's own exception reaches the engine unchanged. Interceptors and
 * {@link MessageHandler#unitOfWork()} are not honoured.
 */
final class MessageHandlerDispatch {
    private final PatternMatchingMethodInvoker<Object> invoker;

    MessageHandlerDispatch(Object handlers) {
        requireNonNull(handlers, "No handlers provided");
        this.invoker = new PatternMatchingMethodInvoker<>(handlers, new HandlerMethodPattern(), InvocationStrategy.InvokeMostSpecificTypeMatched);
    }

    /** Invoke the handler for {@code event}'s payload; nothing if there is none. A handler's exception propagates as thrown */
    void dispatch(DeliveredEvent event) {
        invoker.invoke(requireNonNull(event, "No event provided"));
    }

    boolean handles(Class<?> payloadType) {
        return invoker.hasMatchingMethod(payloadType);
    }

    private static final class HandlerMethodPattern implements MethodPatternMatcher<Object> {
        @Override
        public boolean isInvokableMethod(Method method) {
            if (!method.isAnnotationPresent(MessageHandler.class) || Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            var parameters = method.getParameterTypes();
            var matches = parameters.length == 1 || (parameters.length == 2 && parameters[1] == DeliveredEvent.class);
            if (!matches) {
                throw new IllegalArgumentException("@MessageHandler method " + method.toGenericString() +
                                                           " must take the payload, optionally followed by a DeliveredEvent");
            }
            method.setAccessible(true);
            return true;
        }

        @Override
        public Class<?> resolveInvocationArgumentTypeFromMethodDefinition(Method method) {
            return method.getParameterTypes()[0];
        }

        @Override
        public Class<?> resolveInvocationArgumentTypeFromObject(Object argument) {
            return ((DeliveredEvent) argument).payload().getClass();
        }

        @Override
        public void invokeMethod(Method methodToInvoke, Object argument, Object invokeMethodOn, Class<?> resolvedInvokeMethodWithArgumentOfType) throws Exception {
            var event = (DeliveredEvent) argument;
            try {
                if (methodToInvoke.getParameterCount() == 1) {
                    methodToInvoke.invoke(invokeMethodOn, event.payload());
                } else {
                    methodToInvoke.invoke(invokeMethodOn, event.payload(), event);
                }
            } catch (InvocationTargetException e) {
                // The handler's own exception, unwrapped: the engine classifies it (ParkEventException parks at once)
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        }
    }
}
