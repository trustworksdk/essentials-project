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

import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.UnitOfWorkBoundaryOwningMessageConsumer;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;

/**
 * Marker for a {@link QueuedMessageHandler} that opens and commits its own {@link UnitOfWork}(s) per message, so that
 * a {@link DurableQueues} implementation must NOT wrap the handler invocation in a {@link UnitOfWork} of its own.
 * <p>
 * Under {@link TransactionalMode#SingleOperationTransaction} a {@link DurableQueues} implementation typically runs every
 * operation - including handing a message to its {@link QueuedMessageHandler} - inside its own single-operation
 * {@link UnitOfWork}. That is the right default, but it leaves no {@link UnitOfWork}-free window for a handler that
 * needs to perform blocking I/O. A handler implementing this interface takes that responsibility over.
 * <p>
 * Note that this only concerns the message <i>handling</i> operation: fetching the next message, acknowledging it and
 * scheduling redeliveries remain separate single-operation {@link UnitOfWork}s.
 *
 * @see UnitOfWorkBoundaryOwningMessageConsumer
 * @see dk.trustworks.essentials.components.foundation.messaging.UnitOfWorkMode#NONE
 */
public interface UnitOfWorkBoundaryOwningQueuedMessageHandler extends QueuedMessageHandler {
}
