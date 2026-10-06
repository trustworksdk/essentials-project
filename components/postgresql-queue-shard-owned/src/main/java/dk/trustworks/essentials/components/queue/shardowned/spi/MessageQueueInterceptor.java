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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import dk.trustworks.essentials.components.queue.shardowned.spi.operations.*;
import dk.trustworks.essentials.shared.interceptor.*;

import java.util.List;

/**
 * Sits in the call path of an operation and may change what it does.
 *
 * <h2>Interceptor or {@link QueueObserver}?</h2>
 * They are not variations of one idea and choosing wrongly is easy, so the line is worth stating:
 * <table>
 *     <caption>The difference that decides it</caption>
 *     <tr><th></th><th>{@link QueueObserver}</th><th>{@code MessageQueueInterceptor}</th></tr>
 *     <tr><td>Position</td><td>told what happened</td><td>in the call path</td></tr>
 *     <tr><td>Can change the outcome</td><td>no</td><td>yes — modify, or skip by not proceeding</td></tr>
 *     <tr><td>If it throws</td><td>its own bug; the message is unaffected</td><td>the operation fails</td></tr>
 *     <tr><td>Cost when none registered</td><td>an empty loop</td><td>nothing at all — the chain is not built</td></tr>
 * </table>
 * Metrics, tracing and logging are observers. Enrichment, filtering, multi-tenancy and kill switches
 * are interceptors. An observer that throws used to fail its message, which is exactly the confusion
 * this table exists to prevent.
 *
 * <h2>Why only two operations</h2>
 * {@code DurableQueues}' interceptor has a method per interface member. That shape follows from an
 * interface where any caller may act on any message. Here the operations an interceptor can
 * meaningfully change are the two that carry a message: enqueue and delivery. Interception of
 * {@code depth}, {@code purge}, {@code deadLetters} and {@code resurrect} would be surface without
 * capability — an observer already sees them, and nothing useful can be substituted for their
 * results. They are omitted deliberately rather than forgotten, and can be added if a use appears.
 *
 * <h2>Ordering</h2>
 * Annotate with {@link InterceptorOrder}; lower runs first. Unannotated interceptors default to 10.
 */
public interface MessageQueueInterceptor extends Interceptor {

    /**
     * Around a batch being enqueued, before anything is written.
     * <p>
     * {@link EnqueueMessages#setMessages(List)} replaces what gets written. Not proceeding means
     * nothing is enqueued; return the ids the caller should see, or an empty list.
     */
    default List<MessageId> intercept(EnqueueMessages operation,
                                      InterceptorChain<EnqueueMessages, List<MessageId>, MessageQueueInterceptor> interceptorChain) {
        return interceptorChain.proceed();
    }

    /**
     * Around one message being handed to its handler.
     * <p>
     * On the hot path, once per delivered message. Not proceeding skips the handler and the message
     * is acknowledged as handled — see {@link HandleMessage}.
     */
    default Void intercept(HandleMessage operation,
                           InterceptorChain<HandleMessage, Void, MessageQueueInterceptor> interceptorChain) {
        return interceptorChain.proceed();
    }
}
