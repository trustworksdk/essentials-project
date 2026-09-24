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

package dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward;

import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.operation.InvokeMessageHandlerMethod;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import dk.trustworks.essentials.shared.reflection.*;
import dk.trustworks.essentials.shared.reflection.invocation.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;
import static dk.trustworks.essentials.shared.interceptor.InterceptorChain.newInterceptorChainForOperation;

/**
 * Pattern matching {@literal Consumer<Message>} for use with {@link Inboxes}/{@link Inbox} or {@link Outboxes}/{@link Outbox}<br>
 * The {@link PatternMatchingMessageHandler} will automatically call methods annotated with the {@literal @MessageHandler} annotation and
 * where the 1st argument matches the actual Message payload type (contained in the {@link Message#getPayload()} provided to the provided {@link java.util.function.Consumer})
 * <p>
 * Each method may also include a 2nd argument that of type {@link Message} in which case the event that's being matched is included as the 2nd argument in the call to the method.<br>
 * The methods can have any accessibility (private, public, etc.), they just have to be instance methods.
 * <p>
 * Example:
 * <pre>{@code
 * public class MyMessageHandler extends PatternMatchingMessageHandler {
 *
 *         @MessageHandler
 *         public void handle(OrderEvent.OrderAdded orderAdded) {
 *             ...
 *         }
 *
 *         @MessageHandler
 *         private void handle(OrderEvent.ProductRemovedFromOrder productRemovedFromOrder, Message message) {
 *           ...
 *         }
 * }
 * }</pre>
 */
public class PatternMatchingMessageHandler implements Consumer<Message> {
    private final PatternMatchingMethodInvoker<Object> invoker;
    private final Object                               invokeMessageHandlerMethodsOn;
    private final List<MessageHandlerInterceptor>      interceptors;
    private       boolean                              allowUnmatchedMessages = false;
    /**
     * Optional - when set, this {@link PatternMatchingMessageHandler} takes over the {@link UnitOfWork} boundary and
     * opens a {@link UnitOfWork} per {@link MessageHandler} annotated method invocation, according to that method's
     * {@link MessageHandler#unitOfWork()}.<br>
     * When left null the historic behaviour applies: methods are invoked as-is, and whoever dispatches the
     * {@link Message} is responsible for the {@link UnitOfWork}.
     *
     * @see #setUnitOfWorkFactory(UnitOfWorkFactory)
     */
    private       UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;

    /**
     * Create an {@link PatternMatchingMessageHandler} that can resolve and invoke message handler methods, i.e. methods
     * annotated with {@literal @MessageHandler}, on another object
     *
     * @param invokeMessageHandlerMethodsOn the object that contains the {@literal @MessageHandler} annotated methods
     */
    public PatternMatchingMessageHandler(Object invokeMessageHandlerMethodsOn) {
        this(invokeMessageHandlerMethodsOn, List.of());
    }

    /**
     * Create an {@link PatternMatchingMessageHandler} that can resolve and invoke message handler methods, i.e. methods
     * annotated with {@literal @MessageHandler}, on another object
     *
     * @param invokeMessageHandlerMethodsOn the object that contains the {@literal @MessageHandler} annotated methods
     * @param interceptors                  message handler interceptors
     */
    public PatternMatchingMessageHandler(Object invokeMessageHandlerMethodsOn, List<MessageHandlerInterceptor> interceptors) {
        this.invokeMessageHandlerMethodsOn = requireNonNull(invokeMessageHandlerMethodsOn, "No invokeMessageHandlerMethodsOn provided");
        this.interceptors = new CopyOnWriteArrayList<>(requireNonNull(interceptors, "No interceptors provided"));
        invoker = createMethodInvoker();
    }

    /**
     * Create an {@link PatternMatchingMessageHandler} that can resolve and invoke message handler methods, i.e. methods
     * annotated with {@literal @MessageHandler}, on this concrete subclass of {@link PatternMatchingMessageHandler}
     */
    public PatternMatchingMessageHandler() {
        this(List.of());
    }

    /**
     * Create an {@link PatternMatchingMessageHandler} that can resolve and invoke message handler methods, i.e. methods
     * annotated with {@literal @MessageHandler}, on this concrete subclass of {@link PatternMatchingMessageHandler}
     */
    public PatternMatchingMessageHandler(List<MessageHandlerInterceptor> interceptors) {
        this.invokeMessageHandlerMethodsOn = this;
        this.interceptors = new CopyOnWriteArrayList<>(requireNonNull(interceptors, "No interceptors provided"));
        invoker = createMethodInvoker();
    }

    private PatternMatchingMethodInvoker<Object> createMethodInvoker() {
        return new PatternMatchingMethodInvoker<>(invokeMessageHandlerMethodsOn,
                                                  new MessageHandlerMethodPatternMatcher(),
                                                  InvocationStrategy.InvokeMostSpecificTypeMatched);
    }

    /**
     * Hand this {@link PatternMatchingMessageHandler} the {@link UnitOfWork} boundary, so that it opens a
     * {@link UnitOfWork} per {@link MessageHandler} annotated method invocation, honouring that method's
     * {@link MessageHandler#unitOfWork()} mode:
     * <ul>
     *   <li>{@link UnitOfWorkMode#REQUIRED} (the default): the method is invoked inside a {@link UnitOfWork}, which
     *       joins an already active {@link UnitOfWork} if there is one</li>
     *   <li>{@link UnitOfWorkMode#NONE}: the method is invoked as-is, with no {@link UnitOfWork} opened for it</li>
     * </ul>
     * The dispatcher delivering the {@link Message} must not have opened a {@link UnitOfWork} itself, otherwise
     * {@link UnitOfWorkMode#NONE} cannot take effect - see {@link UnitOfWorkBoundaryOwningMessageConsumer}.
     * <p>
     * Leaving the {@link UnitOfWorkFactory} unset keeps the historic behaviour, where the dispatcher owns the
     * {@link UnitOfWork} and {@link MessageHandler#unitOfWork()} is not honoured.
     *
     * @param unitOfWorkFactory the {@link UnitOfWorkFactory} used to open a {@link UnitOfWork} per handler invocation
     * @return this instance
     */
    public PatternMatchingMessageHandler setUnitOfWorkFactory(UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        return this;
    }

    /**
     * Does this handler contain at least one {@link MessageHandler} annotated method declared with
     * {@link UnitOfWorkMode#NONE}?
     *
     * @return see description above
     * @see UnitOfWorkBoundaryOwningMessageConsumer#hasNonTransactionalMessageHandlers()
     */
    public boolean hasNonTransactionalMessageHandlers() {
        return MessageHandlerMethods.declaresNonTransactionalMessageHandlers(invokeMessageHandlerMethodsOn);
    }

    public PatternMatchingMessageHandler addInterceptor(MessageHandlerInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        interceptors.add(interceptor);
        return this;
    }

    public PatternMatchingMessageHandler removeInterceptor(MessageHandlerInterceptor interceptor) {
        requireNonNull(interceptor, "No interceptor provided");
        interceptors.remove(interceptor);
        return this;
    }

    /**
     * Should the event handler allow unmatched {@link Message#getPayload()}?
     * If true then an unmatched {@link Message#getPayload()} is ignored, if false (the default value)
     * then an unmatched event
     * will cause {@link #handleUnmatchedMessage(Message)} will throw
     * an {@link IllegalArgumentException}
     *
     * @return should the event handler allow unmatched events
     */
    public boolean isAllowUnmatchedMessages() {
        return allowUnmatchedMessages;
    }

    /**
     * Should the event handler allow unmatched {@link Message#getPayload()}?
     * If true then an unmatched {@link Message#getPayload()} is ignored, if false (the default value)
     * then an unmatched event
     * will cause {@link #handleUnmatchedMessage(Message)} will throw
     * an {@link IllegalArgumentException}
     *
     * @param allowUnmatchedMessages should the event handler allow unmatched {@link Message#getPayload()}
     */
    public void setAllowUnmatchedMessages(boolean allowUnmatchedMessages) {
        this.allowUnmatchedMessages = allowUnmatchedMessages;
    }

    /**
     * Should the event handler allow unmatched {@link Message#getPayload()}?
     * If true then an unmatched {@link Message#getPayload()} is ignored, if false (the default value)
     * then an unmatched event
     * will cause {@link #handleUnmatchedMessage(Message)} will throw
     * an {@link IllegalArgumentException}
     *
     * @see #setAllowUnmatchedMessages(boolean)
     */
    public void allowUnmatchedMessages() {
        setAllowUnmatchedMessages(true);
    }

    @Override
    public void accept(Message message) {
        invoker.invoke(message, unmatchedMessage -> {
            handleUnmatchedMessage(message);
        });
    }

    /**
     * Override this method to provide custom handling for {@link Message}'s who's {@link Message#getPayload()} aren't matched<br>
     * Default behaviour is to throw an {@link IllegalArgumentException} unless {@link #isAllowUnmatchedMessages()}
     * is set to true (default value is false)
     *
     * @param message the unmatched message
     */
    protected void handleUnmatchedMessage(Message message) {
        if (!allowUnmatchedMessages) {
            throw new IllegalArgumentException(msg("Unmatched Message with payload-type: '{}'",
                                                   message.getPayload().getClass().getName()));
        }
    }

    /**
     * Check amongst all the {@link MessageHandler} annotated methods and check if there's a method that matches (i.e. is type compatible) with
     * the <code>payloadType</code>.
     *
     * @param payloadType the {@link Message#getPayload()}'s concrete type
     * @return true if there's a {@link MessageHandler} annotated method that accepts a {@link Message} with a {@link Message#getPayload()} of the
     * given <code>payloadType</code>, otherwise false
     */
    public boolean handlesMessageWithPayload(Class<?> payloadType) {
        return invoker.hasMatchingMethod(payloadType);
    }

    private class MessageHandlerMethodPatternMatcher implements MethodPatternMatcher<Object> {

        @Override
        public boolean isInvokableMethod(Method method) {
            requireNonNull(method, "No candidate method supplied");
            var isCandidate = method.isAnnotationPresent(MessageHandler.class) &&
                    method.getParameterCount() >= 1 && method.getParameterCount() <= 2;
            if (isCandidate && method.getParameterCount() == 2) {
                // Check that the 2nd parameter is a PersistedEvent, otherwise it's not supported
                return Message.class.isAssignableFrom(method.getParameterTypes()[1]);
            }
            return isCandidate;

        }

        @Override
        public Class<?> resolveInvocationArgumentTypeFromMethodDefinition(Method method) {
            requireNonNull(method, "No method supplied");
            return method.getParameterTypes()[0];
        }

        @Override
        public Class<?> resolveInvocationArgumentTypeFromObject(Object argument) {
            requireNonNull(argument, "No argument supplied");
            requireMustBeInstanceOf(argument, Message.class);
            var message = (Message) argument;

            return message.getPayload().getClass();
        }

        public void invokeMethod(Method methodToInvoke,
                                 Object argument,
                                 Object invokeMethodOn,
                                 Class<?> resolvedInvokeMethodWithArgumentOfType) throws Exception {
            requireNonNull(methodToInvoke, "No methodToInvoke supplied");
            requireNonNull(argument, "No argument supplied");
            requireMustBeInstanceOf(argument, Message.class);
            requireNonNull(invokeMethodOn, "No invokeMethodOn supplied");
            requireNonNull(resolvedInvokeMethodWithArgumentOfType, "No resolvedInvokeMethodWithArgumentOfType supplied");

            var message = (Message) argument;
            var payload = message.getPayload();

            var operation = new InvokeMessageHandlerMethod(methodToInvoke,
                                                           message,
                                                           payload,
                                                           invokeMethodOn,
                                                           resolvedInvokeMethodWithArgumentOfType);

            InterceptorChain<InvokeMessageHandlerMethod, Void, MessageHandlerInterceptor> operationresultinterceptorTypeInterceptorChain
                    = newInterceptorChainForOperation(operation,
                                                      interceptors,
                                                      (interceptor, interceptorChain) -> {
                                                          interceptor.intercept(operation, interceptorChain);
                                                          return null;
                                                      },
                                                      () -> {
                                                          try {
                                                              if (methodToInvoke.getParameterCount() == 1) {
                                                                  methodToInvoke.invoke(invokeMethodOn, payload);
                                                              } else {
                                                                  methodToInvoke.invoke(invokeMethodOn, payload, message);
                                                              }
                                                              return null;
                                                          } catch (Exception e) {
                                                              throw new ReflectionException(msg("Failed to invoke method - {}",
                                                                                                operation), e);
                                                          }
                                                      });

            if (unitOfWorkFactory != null && MessageHandlerMethods.resolveUnitOfWorkMode(methodToInvoke) == UnitOfWorkMode.REQUIRED) {
                // This handler owns the UnitOfWork boundary - open one around the interceptor chain, matching the
                // scope the dispatcher used to provide (i.e. interceptors run inside the UnitOfWork)
                unitOfWorkFactory.usingUnitOfWork(operationresultinterceptorTypeInterceptorChain::proceed);
            } else {
                // Either the dispatcher owns the UnitOfWork boundary (historic behaviour), or the method is declared
                // with UnitOfWorkMode.NONE and must run without one, so it can perform blocking I/O
                operationresultinterceptorTypeInterceptorChain.proceed();
            }
        }
    }
}