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

/**
 * Who may be handling a queue's messages right now, cluster-wide - see {@link DurableQueues#getQueueConsumers(QueueName)}.
 *
 * @param liveInstances                 instances consuming the queue: registered, and not yet given up on
 * @param departedInstancesHoldingUnits instances no longer counted live that still hold part of the queue: one that
 *                                      crashed, or one that stopped while a handler was still running and keeps its
 *                                      part until that handler returns. The queue implementation cannot tell the two
 *                                      apart, so either may still be running a handler
 */
public record QueueConsumers(int liveInstances, int departedInstancesHoldingUnits) {
    /**
     * @return {@code true} if no instance consumes the queue and no departed instance still holds part of it
     */
    public boolean none() {
        return liveInstances == 0 && departedInstancesHoldingUnits == 0;
    }
}
