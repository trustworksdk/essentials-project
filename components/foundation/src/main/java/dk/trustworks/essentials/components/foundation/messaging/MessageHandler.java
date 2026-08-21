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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.lang.annotation.*;

/**
 * Methods annotated with this Annotation will automatically be called when a {@link PatternMatchingQueuedMessageHandler} and {@link PatternMatchingMessageHandler}
 * receives respectively a {@link QueuedMessage} or a {@link Message} where the {@link Message#getPayload()} matches the type of the first argument/parameter on a method annotated with {@literal @MessageHandler}
 * <p>
 * If the class extends {@link PatternMatchingQueuedMessageHandler} (when used with a {@link DurableQueues} instance) then it allows a second optional argument of type {@link QueuedMessage}<br>
 * If the class extends {@link PatternMatchingMessageHandler} (when used with a {@link Inboxes}/{@link Inbox} or {@link Outboxes}/{@link Outbox}) then it allows a second optional argument of type {@link Message}/{@link OrderedMessage}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MessageHandler {
    /**
     * Should the annotated method be invoked inside a {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}?
     * <p>
     * Defaults to {@link UnitOfWorkMode#REQUIRED}, which is the historic behaviour. Use {@link UnitOfWorkMode#NONE}
     * for handlers that perform blocking I/O against an external system - read the {@link UnitOfWorkMode#NONE}
     * documentation first, as it shifts idempotency and timeout responsibilities onto the handler.
     * <p>
     * Only honoured by message dispatchers that own their {@link dk.trustworks.essentials.components.foundation.transaction.UnitOfWork}
     * boundary, see {@link UnitOfWorkBoundaryOwningMessageConsumer}. Dispatchers that do not own the boundary reject
     * {@link UnitOfWorkMode#NONE} at start-up rather than silently ignoring it.
     *
     * @return the {@link UnitOfWorkMode} the annotated method should be invoked with
     */
    UnitOfWorkMode unitOfWork() default UnitOfWorkMode.REQUIRED;
}