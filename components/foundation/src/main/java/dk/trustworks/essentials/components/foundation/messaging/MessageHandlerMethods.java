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

package dk.trustworks.essentials.components.foundation.messaging;

import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.shared.reflection.Methods;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Introspection of {@link MessageHandler} annotated methods - the single place where the {@link UnitOfWorkMode} a
 * handler method was declared with is resolved.
 * <p>
 * Dispatchers use {@link #hasNonTransactionalMessageHandlers(Object)} to determine whether a message handler needs a
 * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}-free window, so that a dispatcher that
 * cannot provide one can reject the handler at start-up instead of silently running its blocking call inside a
 * database transaction.
 *
 * @see UnitOfWorkMode
 * @see UnitOfWorkBoundaryOwningMessageConsumer#hasNonTransactionalMessageHandlers()
 */
public final class MessageHandlerMethods {
    private MessageHandlerMethods() {
    }

    /**
     * Does the given {@literal Consumer<Message>} dispatch to at least one {@link MessageHandler} annotated method
     * declared with {@link UnitOfWorkMode#NONE}, i.e. one that must run without an ambient
     * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}?
     * <p>
     * This is the variant message dispatchers want: consumers that know the answer are asked, because they may be
     * wrappers around the object that actually carries the handler methods, and anything else is introspected via
     * {@link #declaresNonTransactionalMessageHandlers(Object)}.
     *
     * @param messageConsumer the message consumer to resolve the answer for
     * @return true if at least one handler method requires the absence of an ambient
     * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
     */
    public static boolean hasNonTransactionalMessageHandlers(Consumer<Message> messageConsumer) {
        requireNonNull(messageConsumer, "No messageConsumer provided");
        if (messageConsumer instanceof UnitOfWorkBoundaryOwningMessageConsumer boundaryOwningConsumer) {
            return boundaryOwningConsumer.hasNonTransactionalMessageHandlers();
        }
        if (messageConsumer instanceof PatternMatchingMessageHandler patternMatchingMessageHandler) {
            return patternMatchingMessageHandler.hasNonTransactionalMessageHandlers();
        }
        return declaresNonTransactionalMessageHandlers(messageConsumer);
    }

    /**
     * Does the given object declare at least one {@link MessageHandler} annotated method with
     * {@link UnitOfWorkMode#NONE}, i.e. one that must run without an ambient
     * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}?
     * <p>
     * This inspects the methods declared by the given object's own class hierarchy and nothing else. An object that
     * <i>delegates</i> to another object carrying the handler methods must ask on behalf of its delegate instead -
     * see {@link UnitOfWorkBoundaryOwningMessageConsumer#hasNonTransactionalMessageHandlers()}. Message dispatchers
     * should call {@link #hasNonTransactionalMessageHandlers(Consumer)}, which handles both cases.
     *
     * @param messageHandlerObject the object that carries the {@literal @MessageHandler} annotated methods
     * @return true if at least one handler method requires the absence of an ambient
     * {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
     */
    public static boolean declaresNonTransactionalMessageHandlers(Object messageHandlerObject) {
        requireNonNull(messageHandlerObject, "No messageHandlerObject provided");
        return Methods.methods(messageHandlerObject.getClass())
                      .stream()
                      .filter(method -> method.getDeclaringClass() != Object.class)
                      .anyMatch(method -> resolveUnitOfWorkMode(method) == UnitOfWorkMode.NONE);
    }

    /**
     * Resolve the {@link UnitOfWorkMode} declared by the given method's {@link MessageHandler} annotation
     *
     * @param method the method to resolve the {@link UnitOfWorkMode} for
     * @return the declared {@link UnitOfWorkMode}, or {@link UnitOfWorkMode#REQUIRED} if the method isn't a
     * {@link MessageHandler} annotated method
     */
    public static UnitOfWorkMode resolveUnitOfWorkMode(Method method) {
        requireNonNull(method, "No method provided");
        var messageHandlerAnnotation = method.getAnnotation(MessageHandler.class);
        return messageHandlerAnnotation != null ? messageHandlerAnnotation.unitOfWork() : UnitOfWorkMode.REQUIRED;
    }
}
