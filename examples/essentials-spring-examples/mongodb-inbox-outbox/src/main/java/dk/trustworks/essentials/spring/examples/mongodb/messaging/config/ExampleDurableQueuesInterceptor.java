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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.config;

import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueuesInterceptor;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueuedMessage;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.GetNextMessageReadyForDelivery;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.QueueMessage;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.QueueMessages;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ExampleDurableQueuesInterceptor implements DurableQueuesInterceptor {
    private static final Logger        log = LoggerFactory.getLogger(ExampleDurableQueuesInterceptor.class);
    private              DurableQueues durableQueues;

    @Override
    public void setDurableQueues(DurableQueues durableQueues) {
        this.durableQueues = durableQueues;
    }

    @Override
    public QueueEntryId intercept(QueueMessage operation, InterceptorChain<QueueMessage, QueueEntryId, DurableQueuesInterceptor> interceptorChain) {
        log.trace("Intercepting: {}", operation);
        return interceptorChain.proceed();
    }

    @Override
    public List<QueueEntryId> intercept(QueueMessages operation, InterceptorChain<QueueMessages, List<QueueEntryId>, DurableQueuesInterceptor> interceptorChain) {
        log.trace("Intercepting: {}", operation);
        return interceptorChain.proceed();
    }

    @Override
    public Optional<QueuedMessage> intercept(GetNextMessageReadyForDelivery operation, InterceptorChain<GetNextMessageReadyForDelivery, Optional<QueuedMessage>, DurableQueuesInterceptor> interceptorChain) {
        log.trace("Intercepting: {}", operation);
        var nextMessageReadyForDelivery = interceptorChain.proceed();
        nextMessageReadyForDelivery.ifPresent(queuedMessage -> {
            log.trace("Intercepting GetNextMessageReadyForDelivery: {}", queuedMessage);
        });
        return nextMessageReadyForDelivery;
    }
}
