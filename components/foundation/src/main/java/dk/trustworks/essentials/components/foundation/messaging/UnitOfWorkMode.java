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
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;

/**
 * Controls whether the body of a {@link MessageHandler} annotated method is invoked inside a {@link UnitOfWork}.
 * <p>
 * Only message dispatchers that own their {@link UnitOfWork} boundary honour this setting - see
 * {@link UnitOfWorkBoundaryOwningMessageConsumer}. A dispatcher that does not own the boundary (i.e. one that is
 * handed a plain {@literal Consumer<Message>}) keeps the historic behaviour of running every handler inside the
 * {@link UnitOfWork} that was opened before dispatch, and {@link #NONE} is then rejected at start-up rather than
 * being silently ignored.
 */
public enum UnitOfWorkMode {
    /**
     * The default and the historic behaviour: the {@link MessageHandler} annotated method is invoked inside a
     * {@link UnitOfWork}, which is committed when the method returns normally and rolled back if it throws.
     * <p>
     * If a {@link UnitOfWork} is already active then the handler joins it, and committing is left to whoever
     * created it.
     */
    REQUIRED,

    /**
     * The {@link MessageHandler} annotated method is invoked with NO {@link UnitOfWork} active, which means no
     * database connection and no open database transaction are held while it runs. This is what makes it safe for
     * the handler to perform blocking I/O against an external system.
     * <p>
     * Any database work that follows the blocking call must be wrapped explicitly by the handler - event-processor
     * subclasses have {@code usingUnitOfWork(...)} / {@code withUnitOfWork(...)} helpers for exactly that. Touching
     * a transactional resource outside such a wrapper fails fast, because there is no active transaction to join.
     * <p>
     * Consequences the handler must be written for:
     * <ul>
     *   <li><b>Idempotency is mandatory.</b> Message delivery is at-least-once and the blocking call is no longer
     *       part of the transaction that acknowledges the message. A failure after the blocking call has completed,
     *       but before the transactional tail was committed, redelivers the message and repeats the blocking call.</li>
     *   <li><b>The blocking call must time out well before the {@link DurableQueues} message-handling timeout.</b>
     *       While the handler runs, the message is marked as being delivered. Once that timeout elapses the message
     *       is reset as a stuck message and can be delivered again, concurrently with the still-running first
     *       attempt.</li>
     *   <li><b>Ordering degrades on timeout.</b> For {@link OrderedMessage}s a stuck-message reset can hand the same
     *       {@link OrderedMessage#getKey()} to another consumer thread while the first attempt is still blocked, so
     *       the per-key ordering guarantee only holds as long as handlers complete within the timeout.</li>
     * </ul>
     */
    NONE
}
