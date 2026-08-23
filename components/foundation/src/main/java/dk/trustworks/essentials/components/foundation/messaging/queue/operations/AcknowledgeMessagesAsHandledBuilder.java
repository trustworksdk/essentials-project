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

package dk.trustworks.essentials.components.foundation.messaging.queue.operations;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId;

import java.util.Collection;

/**
 * Builder for {@link AcknowledgeMessagesAsHandled}
 */
public final class AcknowledgeMessagesAsHandledBuilder {
    private Collection<QueueEntryId> queueEntryIds;

    /**
     * @param queueEntryIds the unique ids of the Messages to acknowledge
     * @return this builder instance
     */
    public AcknowledgeMessagesAsHandledBuilder setQueueEntryIds(Collection<QueueEntryId> queueEntryIds) {
        this.queueEntryIds = queueEntryIds;
        return this;
    }

    /**
     * Build an {@link AcknowledgeMessagesAsHandled} instance from the builder properties
     *
     * @return the {@link AcknowledgeMessagesAsHandled} instance
     */
    public AcknowledgeMessagesAsHandled build() {
        return new AcknowledgeMessagesAsHandled(queueEntryIds);
    }
}
