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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.adminapi.rest.dto.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues.QueueingSortOrder;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link DurableQueuesApi}, implementing the contract's {@code durable-queues} tag.
 * <p>
 * The operations that mutate a queue (delete, purge, resurrect, mark-as-dead-letter) require a queue-writer role,
 * which the SPI enforces. Message payloads are redacted by the SPI unless the caller additionally holds the
 * queue-payload-reader role, so this layer never has to reason about them.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class DurableQueuesController {

    private final DurableQueuesApi          durableQueuesApi;
    private final AdminApiPrincipalResolver principalResolver;

    public DurableQueuesController(DurableQueuesApi durableQueuesApi, AdminApiPrincipalResolver principalResolver) {
        this.durableQueuesApi = requireNonNull(durableQueuesApi, "No durableQueuesApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/durable-queues")
    public Set<QueueName> getQueueNames() {
        return durableQueuesApi.getQueueNames(principalResolver.requireAuthenticatedPrincipal());
    }

    @GetMapping("/durable-queues/messages/{queueEntryId}")
    public ApiQueuedMessage getQueuedMessage(@PathVariable String queueEntryId) {
        return durableQueuesApi.getQueuedMessage(principalResolver.requireAuthenticatedPrincipal(),
                                                QueueEntryId.of(queueEntryId))
                               .orElseThrow(() -> noSuchMessage(queueEntryId));
    }

    @GetMapping("/durable-queues/messages/{queueEntryId}/queue-name")
    public QueueNameResult getQueueNameFor(@PathVariable String queueEntryId) {
        return durableQueuesApi.getQueueNameFor(principalResolver.requireAuthenticatedPrincipal(),
                                               QueueEntryId.of(queueEntryId))
                               .map(queueName -> new QueueNameResult(queueName.toString()))
                               .orElseThrow(() -> noSuchMessage(queueEntryId));
    }

    @PostMapping("/durable-queues/messages/{queueEntryId}/resurrect")
    public ApiQueuedMessage resurrectDeadLetterMessage(@PathVariable String queueEntryId,
                                                      @RequestBody ResurrectDeadLetterMessageRequest request) {
        requireNonNull(request, "No request body provided");
        return durableQueuesApi.resurrectDeadLetterMessage(principalResolver.requireAuthenticatedPrincipal(),
                                                          QueueEntryId.of(queueEntryId),
                                                          request.deliveryDelayOrImmediate())
                               .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                       "No dead-letter message with queue entry id '" + queueEntryId + "' could be resurrected."));
    }

    @PostMapping("/durable-queues/messages/{queueEntryId}/mark-as-dead-letter")
    public ApiQueuedMessage markAsDeadLetterMessage(@PathVariable String queueEntryId) {
        return durableQueuesApi.markAsDeadLetterMessage(principalResolver.requireAuthenticatedPrincipal(),
                                                       QueueEntryId.of(queueEntryId))
                               .orElseThrow(() -> noSuchMessage(queueEntryId));
    }

    @DeleteMapping("/durable-queues/messages/{queueEntryId}")
    public DeleteResult deleteMessage(@PathVariable String queueEntryId) {
        var deleted = durableQueuesApi.deleteMessage(principalResolver.requireAuthenticatedPrincipal(),
                                                    QueueEntryId.of(queueEntryId));
        return new DeleteResult(deleted);
    }

    @GetMapping("/durable-queues/queues/{queueName}/messages/count")
    public CountResult getTotalMessagesQueuedFor(@PathVariable String queueName) {
        return new CountResult(durableQueuesApi.getTotalMessagesQueuedFor(principalResolver.requireAuthenticatedPrincipal(),
                                                                         QueueName.of(queueName)));
    }

    @GetMapping("/durable-queues/queues/{queueName}/dead-letter-messages/count")
    public CountResult getTotalDeadLetterMessagesQueuedFor(@PathVariable String queueName) {
        return new CountResult(durableQueuesApi.getTotalDeadLetterMessagesQueuedFor(principalResolver.requireAuthenticatedPrincipal(),
                                                                                   QueueName.of(queueName)));
    }

    @GetMapping("/durable-queues/queues/{queueName}/messages")
    public List<ApiQueuedMessage> getQueuedMessages(@PathVariable String queueName,
                                                    @RequestParam(defaultValue = "ASC") QueueingSortOrder sortOrder,
                                                    @RequestParam(defaultValue = AdminApiPaths.DEFAULT_START_INDEX) long startIndex,
                                                    @RequestParam(defaultValue = AdminApiPaths.DEFAULT_PAGE_SIZE) long pageSize) {
        return durableQueuesApi.getQueuedMessages(principalResolver.requireAuthenticatedPrincipal(),
                                                  QueueName.of(queueName), sortOrder, startIndex, pageSize);
    }

    @GetMapping("/durable-queues/queues/{queueName}/dead-letter-messages")
    public List<ApiQueuedMessage> getDeadLetterMessages(@PathVariable String queueName,
                                                        @RequestParam(defaultValue = "ASC") QueueingSortOrder sortOrder,
                                                        @RequestParam(defaultValue = AdminApiPaths.DEFAULT_START_INDEX) long startIndex,
                                                        @RequestParam(defaultValue = AdminApiPaths.DEFAULT_PAGE_SIZE) long pageSize) {
        return durableQueuesApi.getDeadLetterMessages(principalResolver.requireAuthenticatedPrincipal(),
                                                      QueueName.of(queueName), sortOrder, startIndex, pageSize);
    }

    @DeleteMapping("/durable-queues/queues/{queueName}/messages")
    public PurgeResult purgeQueue(@PathVariable String queueName) {
        var purged = durableQueuesApi.purgeQueue(principalResolver.requireAuthenticatedPrincipal(),
                                                QueueName.of(queueName));
        return new PurgeResult(purged);
    }

    @GetMapping("/durable-queues/queues/{queueName}/statistics")
    public ApiQueuedStatistics getQueuedStatistics(@PathVariable String queueName) {
        return durableQueuesApi.getQueuedStatistics(principalResolver.requireAuthenticatedPrincipal(),
                                                   QueueName.of(queueName))
                               .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                       "No statistics available for queue '" + queueName + "'."));
    }

    private static AdminApiResourceNotFoundException noSuchMessage(String queueEntryId) {
        return new AdminApiResourceNotFoundException("No queued message with queue entry id '" + queueEntryId + "'.");
    }
}
