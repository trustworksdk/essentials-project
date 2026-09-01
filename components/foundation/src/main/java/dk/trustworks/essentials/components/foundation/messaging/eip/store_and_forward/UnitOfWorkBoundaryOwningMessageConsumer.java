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
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;

import java.util.function.Consumer;

/**
 * Marker for a message consumer that opens and commits its own {@link UnitOfWork}(s) per message, instead of being
 * handed one by the dispatcher that delivers the message.
 * <p>
 * {@link Inboxes.DurableQueueBasedInboxes} normally wraps every delivery in a {@link UnitOfWork} whenever the
 * underlying {@link DurableQueues} is associated with a {@link UnitOfWorkFactory}. A consumer implementing this
 * interface takes that responsibility over, which is what allows it to run part of the message handling - typically a
 * blocking call to an external system - with no {@link UnitOfWork} and therefore no database connection held. See
 * {@link UnitOfWorkMode#NONE}.
 * <p>
 * A consumer that implements this interface MUST ensure that every transactional operation it performs happens inside
 * a {@link UnitOfWork} it opens itself. Plain {@literal Consumer<Message>} consumers are unaffected and keep being
 * wrapped by the dispatcher.
 */
public interface UnitOfWorkBoundaryOwningMessageConsumer extends Consumer<Message> {
    /**
     * Does this consumer dispatch to at least one {@link MessageHandler} annotated method declared with
     * {@link UnitOfWorkMode#NONE}, i.e. one that must run without an ambient {@link UnitOfWork}?
     * <p>
     * Used by dispatchers to fail fast during start-up in setups where no {@link UnitOfWork}-free window can be
     * provided - e.g. {@link TransactionalMode#FullyTransactional}, where the queue consumer wraps message fetching,
     * handling and acknowledgement in one shared {@link UnitOfWork}.
     * <p>
     * The default implementation introspects this consumer's own {@link MessageHandler} annotated methods, which is
     * the right answer for a consumer that carries its handler methods itself. <b>Override it only when the handler
     * methods live on another object</b> - a consumer that delegates to a {@link PatternMatchingMessageHandler}, for
     * instance, must return {@link PatternMatchingMessageHandler#hasNonTransactionalMessageHandlers()} on behalf of
     * its delegate, since introspecting the wrapper itself finds no handler methods at all.
     *
     * @return true if at least one handler requires the absence of an ambient {@link UnitOfWork}
     */
    default boolean hasNonTransactionalMessageHandlers() {
        // Deliberately the introspecting variant and not MessageHandlerMethods#hasNonTransactionalMessageHandlers,
        // which would dispatch straight back to this method
        return MessageHandlerMethods.declaresNonTransactionalMessageHandlers(this);
    }
}
