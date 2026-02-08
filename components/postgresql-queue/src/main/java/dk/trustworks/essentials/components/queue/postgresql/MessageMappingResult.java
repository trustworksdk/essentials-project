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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.FailFast;

import java.util.List;

/**
 * Result of mapping database rows to QueuedMessage objects, containing both
 * successfully mapped messages and failed mappings with their exceptions.
 */
public record MessageMappingResult(List<QueuedMessage> successfulMessages, List<FailedMessageMapping> failedMappings) {
    
    public MessageMappingResult {
        FailFast.requireNonNull(successfulMessages, "No successfulMessages provided");
        FailFast.requireNonNull(failedMappings, "No failedMappings provided");
    }

    /**
     * Represents a failed message mapping with the QueueName, QueueEntryId and the exception that caused the failure.
     */
    public record FailedMessageMapping(QueueName queueName, QueueEntryId queueEntryId, Exception mappingException) {
        
        public FailedMessageMapping {
            FailFast.requireNonNull(queueName, "No queueName provided");
            FailFast.requireNonNull(queueEntryId, "No queueEntryId provided");
            FailFast.requireNonNull(mappingException, "No mappingException provided");
        }
    }
}