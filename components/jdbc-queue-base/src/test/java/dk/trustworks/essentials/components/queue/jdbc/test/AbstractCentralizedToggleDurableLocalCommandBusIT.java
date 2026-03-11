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

package dk.trustworks.essentials.components.queue.jdbc.test;

import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.test.reactive.command.AbstractDurableLocalCommandBusIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;

public abstract class AbstractCentralizedToggleDurableLocalCommandBusIT<DURABLE_QUEUES extends DurableQueues>
        extends AbstractDurableLocalCommandBusIT<DURABLE_QUEUES, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    protected abstract boolean useCentralizedMessageFetcher();

    protected abstract DURABLE_QUEUES createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                                          boolean useCentralizedMessageFetcher);

    @Override
    protected final DURABLE_QUEUES createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return createDurableQueues(unitOfWorkFactory, useCentralizedMessageFetcher());
    }
}
