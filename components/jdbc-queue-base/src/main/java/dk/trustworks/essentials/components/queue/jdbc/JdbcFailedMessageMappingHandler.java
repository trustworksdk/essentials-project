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

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId;
import org.slf4j.Logger;

import java.util.function.BiFunction;

public final class JdbcFailedMessageMappingHandler {
    private JdbcFailedMessageMappingHandler() {
    }

    public static <F extends JdbcMessageMappingResult.FailedMessageMapping> void handleFailedMappings(
            JdbcMessageMappingResult<F> mappingResult,
            BiFunction<QueueEntryId, Exception, Boolean> markAsDeadLetterDirect,
            Logger log) {
        for (var failedMapping : mappingResult.failedMappings()) {
            log.error("[{}] Marking Message as DeadLetterMessage due to deserialization failure for message id '{}'",
                      failedMapping.queueName(), failedMapping.queueEntryId(), failedMapping.mappingException());
            try {
                var success = markAsDeadLetterDirect.apply(failedMapping.queueEntryId(), failedMapping.mappingException());
                if (success) {
                    log.debug("[{}] Successfully marked message '{}' as dead letter due to deserialization failure",
                              failedMapping.queueName(), failedMapping.queueEntryId());
                } else {
                    log.warn("[{}] Failed to mark message '{}' as dead letter - message may have been deleted",
                             failedMapping.queueName(), failedMapping.queueEntryId());
                }
            } catch (Exception e) {
                log.error("[{}] Error marking message '{}' as dead letter: {}",
                          failedMapping.queueName(), failedMapping.queueEntryId(), e.getMessage(), e);
            }
        }
    }
}
