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

package dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.operation;

import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;

import java.lang.reflect.Method;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The operation a {@link MessageHandlerInterceptor} intercepts: the invocation of the {@link MessageHandler}
 * annotated method that {@link PatternMatchingMessageHandler} matched for a {@link Message}.
 * <p>
 * An interceptor that changes what the handler method receives (for example decrypting the payload) calls
 * {@link #setMessage(Message)} before {@link InterceptorChain#proceed()}. Later interceptors and the handler method
 * then see the replacement.
 * <p>
 * The handler method ({@link #getMethodToInvoke()}) is resolved from the payload type <b>before</b> the interceptor
 * chain runs, and it is never re-resolved. Interceptors may replace the payload with a value of any type, and later
 * interceptors may replace it again; only the payload that reaches the handler method has to be an instance of the
 * method's first parameter type (and the {@link Message} an instance of its optional second parameter type).
 * If it is not, the invocation fails with a {@link ClassCastException} naming the type that arrived, the type that
 * was expected and the method - see {@link #setMessage(Message)}.
 */
public final class InvokeMessageHandlerMethod {
    /**
     * The matched {@link MessageHandler} annotated method.<br>
     * Public for backwards compatibility - use {@link #getMethodToInvoke()}
     */
    public final Method   methodToInvoke;
    /**
     * The {@link Message} the handler method will receive - read it with {@link #getMessage()}, replace it with
     * {@link #setMessage(Message)}, which keeps {@link #messagPayload} in step
     */
    private      Message  message;
    /**
     * The payload the handler method will receive as its first argument (the payload of {@link #message}) -
     * read it with {@link #getPayload()}
     */
    private      Object   messagPayload;
    /**
     * The object the handler method is invoked on.<br>
     * Public for backwards compatibility - use {@link #getInvokeMethodOn()}
     */
    public final Object   invokeMethodOn;
    /**
     * The payload type the handler method was resolved for, before the interceptor chain ran.<br>
     * Public for backwards compatibility - use {@link #getResolvedInvokeMethodWithArgumentOfType()}
     */
    public final Class<?> resolvedInvokeMethodWithArgumentOfType;

    /**
     * Create the operation
     *
     * @param methodToInvoke                         the matched handler method
     * @param message                                the message the handler method will receive
     * @param messagPayload                          the message's payload
     * @param invokeMethodOn                         the object the handler method is invoked on
     * @param resolvedInvokeMethodWithArgumentOfType the payload type the handler method was resolved for
     */
    public InvokeMessageHandlerMethod(Method methodToInvoke, Message message, Object messagPayload, Object invokeMethodOn, Class<?> resolvedInvokeMethodWithArgumentOfType) {
        this.methodToInvoke = methodToInvoke;
        this.message = message;
        this.messagPayload = messagPayload;
        this.invokeMethodOn = invokeMethodOn;
        this.resolvedInvokeMethodWithArgumentOfType = resolvedInvokeMethodWithArgumentOfType;
    }

    /**
     * The matched {@link MessageHandler} annotated method. It was resolved from the payload type before the
     * interceptor chain ran, and is not re-resolved when an interceptor calls {@link #setMessage(Message)}
     *
     * @return the matched handler method
     */
    public Method getMethodToInvoke() {
        return methodToInvoke;
    }

    /**
     * The {@link Message} the handler method will receive: the latest one set with {@link #setMessage(Message)}, or
     * the dispatched message if no interceptor replaced it
     *
     * @return the current message
     */
    public Message getMessage() {
        return message;
    }

    /**
     * The payload the handler method will receive as its first argument, i.e. the payload of {@link #getMessage()}
     *
     * @return the current payload
     */
    public Object getPayload() {
        return messagPayload;
    }

    /**
     * Replace the {@link Message}, and with it the payload, that later interceptors and the handler method receive.
     * Sets {@link #getMessage()} and {@link #getPayload()} together, so the two cannot drift apart.
     * Build the replacement with {@link Message#withPayload(Object)} (which an {@link OrderedMessage} overrides to keep
     * its key and order) - never mutate the payload in place.
     * <p>
     * A payload of <b>any</b> type is accepted here, so one interceptor may turn an encrypted payload into a
     * {@code byte[]} that a later interceptor turns into the type the handler expects. Only what reaches the handler
     * method has to fit it. The handler method is not re-resolved, so the payload must be an instance of
     * {@link #getMethodToInvoke()}'s first parameter type, and the message an instance of its second parameter type
     * if it declares one (e.g. {@link OrderedMessage}). Otherwise the invocation fails with a
     * {@link ClassCastException} naming the type that arrived, the type that was expected and the method.
     * <p>
     * In a {@link DurableQueues} consumer that {@link ClassCastException} is on the built-in permanent-error list, so
     * the message is dead-lettered on the first attempt instead of being redelivered - the same interceptors would
     * produce the same type on every redelivery. A {@link RedeliveryPolicy} whose
     * {@link MessageDeliveryErrorHandler} was built with
     * {@link MessageDeliveryErrorHandlerBuilder#alwaysRetryOn(Class[]) alwaysRetryOn(ClassCastException.class)}
     * retries it instead, as it does any other {@link ClassCastException}.
     *
     * @param message the replacement message (not null). Its payload may be of any type
     */
    public void setMessage(Message message) {
        requireNonNull(message, "No message provided");
        this.message = message;
        this.messagPayload = message.getPayload();
    }

    /**
     * The object the handler method is invoked on
     *
     * @return the object the handler method is invoked on
     */
    public Object getInvokeMethodOn() {
        return invokeMethodOn;
    }

    /**
     * The payload type the handler method was resolved for, before the interceptor chain ran
     *
     * @return the payload type the handler method was resolved for
     */
    public Class<?> getResolvedInvokeMethodWithArgumentOfType() {
        return resolvedInvokeMethodWithArgumentOfType;
    }

    @Override
    public String toString() {
        return "InvokeMessageHandlerMethod{" +
                "methodToInvoke=" + methodToInvoke +
                ", message= {" + message + "}" +
                ", resolvedInvokeMethodWithArgumentOfType=" + resolvedInvokeMethodWithArgumentOfType +
                ", invokeMethodOn=" + invokeMethodOn +
                '}';
    }
}
