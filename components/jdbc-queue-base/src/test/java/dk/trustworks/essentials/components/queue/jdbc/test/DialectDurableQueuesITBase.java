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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;

public abstract class DialectDurableQueuesITBase<DURABLE_QUEUES extends DurableQueues>
        extends DurableQueuesIT<DURABLE_QUEUES, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    /**
     * Determine whether to use the centralized message fetcher.
     */
    protected abstract boolean useCentralizedMessageFetcher();

    /**
     * Create the dialect specific {@link JSONSerializer} for payload handling.
     */
    protected abstract JSONSerializer createDialectJSONSerializer();

    /**
     * Create the dialect specific DurableQueues implementation.
     */
    protected abstract DURABLE_QUEUES createDialectDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                                                  JSONSerializer jsonSerializer,
                                                                  boolean useCentralizedMessageFetcher);

    @Override
    protected final JSONSerializer createJSONSerializer() {
        return createDialectJSONSerializer();
    }

    @Override
    protected final DURABLE_QUEUES createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                                       JSONSerializer jsonSerializer) {
        return createDialectDurableQueues(unitOfWorkFactory,
                                          jsonSerializer,
                                          useCentralizedMessageFetcher());
    }
}
