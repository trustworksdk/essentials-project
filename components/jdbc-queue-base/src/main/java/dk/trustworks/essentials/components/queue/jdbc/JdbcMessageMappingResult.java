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

package dk.trustworks.essentials.components.queue.jdbc;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.shared.FailFast;

import java.util.List;

public class JdbcMessageMappingResult<F extends JdbcMessageMappingResult.FailedMessageMapping> {
    private final List<QueuedMessage> successfulMessages;
    private final List<F>             failedMappings;

    public JdbcMessageMappingResult(List<QueuedMessage> successfulMessages, List<F> failedMappings) {
        this.successfulMessages = FailFast.requireNonNull(successfulMessages, "No successfulMessages provided");
        this.failedMappings = FailFast.requireNonNull(failedMappings, "No failedMappings provided");
    }

    public List<QueuedMessage> successfulMessages() {
        return successfulMessages;
    }

    public List<F> failedMappings() {
        return failedMappings;
    }

    public static class FailedMessageMapping {
        private final QueueName   queueName;
        private final QueueEntryId queueEntryId;
        private final Exception   mappingException;

        public FailedMessageMapping(QueueName queueName, QueueEntryId queueEntryId, Exception mappingException) {
            this.queueName = FailFast.requireNonNull(queueName, "No queueName provided");
            this.queueEntryId = FailFast.requireNonNull(queueEntryId, "No queueEntryId provided");
            this.mappingException = FailFast.requireNonNull(mappingException, "No mappingException provided");
        }

        public QueueName queueName() {
            return queueName;
        }

        public QueueEntryId queueEntryId() {
            return queueEntryId;
        }

        public Exception mappingException() {
            return mappingException;
        }
    }
}
