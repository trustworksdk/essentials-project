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

/**
 * Builder for {@link MarkAsDeadLetterMessageDirect}
 */
public final class MarkAsDeadLetterMessageDirectBuilder {
    private QueueEntryId queueEntryId;
    private Exception    causeForBeingMarkedAsDeadLetter;

    /**
     * @param queueEntryId the unique id of the message that must be marked as a Dead Letter Message
     * @return this builder instance
     */
    public MarkAsDeadLetterMessageDirectBuilder setQueueEntryId(QueueEntryId queueEntryId) {
        this.queueEntryId = queueEntryId;
        return this;
    }

    /**
     * @param causeForBeingMarkedAsDeadLetter the reason for the message being marked as a Dead Letter Message
     * @return this builder instance
     */
    public MarkAsDeadLetterMessageDirectBuilder setCauseForBeingMarkedAsDeadLetter(Exception causeForBeingMarkedAsDeadLetter) {
        this.causeForBeingMarkedAsDeadLetter = causeForBeingMarkedAsDeadLetter;
        return this;
    }

    /**
     * Build a {@link MarkAsDeadLetterMessageDirect} instance from the builder properties
     *
     * @return the {@link MarkAsDeadLetterMessageDirect} instance
     */
    public MarkAsDeadLetterMessageDirect build() {
        return new MarkAsDeadLetterMessageDirect(queueEntryId, causeForBeingMarkedAsDeadLetter);
    }
}
