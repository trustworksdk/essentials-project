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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.shared.interceptor.*;
import org.slf4j.*;

import java.util.*;

/**
 * Carries the {@link CausationContext} cause across a {@link DurableQueues} hand-off, so work done by the consuming side
 * records the event that caused the message to be queued.
 * <ul>
 *     <li><b>On queueing</b> ({@link QueueMessage}, {@link QueueMessages}): when a cause is bound, its id is written to the
 *     message's {@link MessageMetaData} under {@link MessageMetaData#CAUSED_BY_EVENT_ID} - unless the metadata already
 *     carries one, which is kept.</li>
 *     <li><b>On delivery</b> ({@link HandleQueuedMessage}): when the message carries a cause, the handler runs with it
 *     bound. A delivery site that binds a cause of its own inside the handler - such as an {@code EventProcessor}
 *     resolving the event it delivers - overrides it, since the innermost binding wins.</li>
 * </ul>
 * This is what makes {@link Inbox#addMessageReceived(Object)}, {@link Outbox#sendMessage(Object)} and
 * {@code DurableLocalCommandBus.sendAndDontWait(...)} carry their cause: all three queue through {@link DurableQueues}.
 * <p>
 * <b>It must run outermost</b>, hence {@link InterceptorOrder @InterceptorOrder(1)}. The binding has to enclose the commit
 * of the {@code UnitOfWork} the handler's work runs in, and on PostgreSQL that UnitOfWork is opened by an interceptor:
 * {@code PostgresqlDurableQueues} always registers a {@code SingleOperationTransactionDurableQueuesInterceptor} that wraps
 * {@code HandleQueuedMessage} in one. Inside it, this interceptor's binding would end before that commit, and everything
 * appended lazily at commit would lose its cause. (The handler still saw the cause, which is what makes the failure easy
 * to miss.) Interceptors without an order sort as 10.
 * <p>
 * On delivery it reads only {@link QueuedMessage#getMessage()}'s metadata - never the queue entry id or delivery
 * counts, which the shard-owned engine's delivery path does not have.
 */
@InterceptorOrder(1)
public final class CausationDurableQueuesInterceptor implements DurableQueuesInterceptor {
    private static final Logger log = LoggerFactory.getLogger(CausationDurableQueuesInterceptor.class);

    @Override
    public void setDurableQueues(DurableQueues durableQueues) {
        // Not needed
    }

    @Override
    public QueueEntryId intercept(QueueMessage operation, InterceptorChain<QueueMessage, QueueEntryId, DurableQueuesInterceptor> interceptorChain) {
        CausationContext.current().ifPresent(cause -> recordCause(operation.getMetaData(), cause));
        return interceptorChain.proceed();
    }

    @Override
    public List<QueueEntryId> intercept(QueueMessages operation, InterceptorChain<QueueMessages, List<QueueEntryId>, DurableQueuesInterceptor> interceptorChain) {
        CausationContext.current().ifPresent(cause -> operation.getMessages().forEach(message -> recordCause(message.getMetaData(), cause)));
        return interceptorChain.proceed();
    }

    @Override
    public Void intercept(HandleQueuedMessage operation, InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> interceptorChain) {
        var carriedCause = Optional.ofNullable(operation.getMessage().getMessage().getMetaData())
                                   .map(metaData -> metaData.get(MessageMetaData.CAUSED_BY_EVENT_ID))
                                   .flatMap(EventId::optionalFrom);
        if (carriedCause.isEmpty()) {
            return interceptorChain.proceed();
        }
        return CausationContext.where(carriedCause.get()).call(interceptorChain::proceed);
    }

    private static void recordCause(MessageMetaData metaData, EventId cause) {
        if (metaData == null || metaData.containsKey(MessageMetaData.CAUSED_BY_EVENT_ID)) {
            return;
        }
        try {
            metaData.put(MessageMetaData.CAUSED_BY_EVENT_ID, cause.toString());
        } catch (UnsupportedOperationException e) {
            // A MessageMetaData built around an immutable map cannot carry it; the message is queued without a cause
            log.debug("Could not record cause '{}' on message metadata backed by an immutable map", cause);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
