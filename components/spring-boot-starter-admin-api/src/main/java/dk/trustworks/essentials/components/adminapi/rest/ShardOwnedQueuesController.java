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

import dk.trustworks.essentials.components.adminapi.rest.dto.DeleteResult;
import dk.trustworks.essentials.components.adminapi.rest.ShardOwnedQueueRequests.*;
import dk.trustworks.essentials.components.queue.shardowned.api.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link ShardOwnedQueuesApi}, serving the {@code shard-owned-queues} area.
 *
 * <h2>Why this lives here and not in {@code spring-boot-starter-admin-api}</h2>
 * The engine it exposes is experimental and not published ({@code maven.deploy.skip=true}). A
 * controller in the published admin-api starter would give a published artifact a dependency on one
 * that is not in any repository, and would put an unstable surface inside the frozen
 * {@code EssentialsAdminApiSpec} contract, which is compatibility-checked at version {@code 1.0.0}.
 * So the controller ships with the engine's own starter and borrows the admin API's conventions —
 * base path, principal resolution, exception handling — without extending its contract. Moving it,
 * and adding the {@code EssentialsAdminApiSpec} entries, is the step that goes with publishing the
 * engine.
 *
 * <h2>Every path carries the queue name</h2>
 * A {@link MessageId} is {@code (lane, shard, sequence)} and sequences are per {@code (queue, shard)},
 * so it identifies a message only within its own queue. The name is a path segment on every by-id
 * operation for that reason, not for symmetry with the durable-queues controller.
 *
 * <h2>Payloads</h2>
 * Withheld by the SPI unless the caller holds the payload-reader role, so this layer never decides
 * anything about message contents.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class ShardOwnedQueuesController {

    private final ShardOwnedQueuesApi       shardOwnedQueuesApi;
    private final AdminApiPrincipalResolver principalResolver;

    public ShardOwnedQueuesController(ShardOwnedQueuesApi shardOwnedQueuesApi,
                                      AdminApiPrincipalResolver principalResolver) {
        this.shardOwnedQueuesApi = requireNonNull(shardOwnedQueuesApi, "No shardOwnedQueuesApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/shard-owned-queues")
    public List<QueueName> getQueueNames() {
        return shardOwnedQueuesApi.getQueueNames(principal());
    }

    @GetMapping("/shard-owned-queues/{queueName}/status")
    public ApiShardOwnedQueueStatus getQueueStatus(@PathVariable String queueName) {
        return shardOwnedQueuesApi.getQueueStatus(principal(), QueueName.of(queueName))
                                  .orElseThrow(() -> noSuchQueue(queueName));
    }

    /**
     * This instance's counters for the queue. See {@code ApiShardOwnedQueueStatistics} — they are
     * per-JVM and reset on restart, which is why the response says whether this instance consumes
     * the queue at all.
     */
    @GetMapping("/shard-owned-queues/{queueName}/statistics")
    public ApiShardOwnedQueueStatistics getQueueStatistics(@PathVariable String queueName) {
        return shardOwnedQueuesApi.getQueueStatistics(principal(), QueueName.of(queueName))
                                  .orElseThrow(() -> new AdminApiResourceNotFoundException(
                                          "No shard-owned queue '" + queueName + "'."));
    }

    @GetMapping("/shard-owned-queues/{queueName}/messages/{messageId}")
    public ApiShardOwnedMessage getMessage(@PathVariable String queueName,
                                           @PathVariable String messageId) {
        return shardOwnedQueuesApi.getMessage(principal(), QueueName.of(queueName), MessageId.parse(messageId))
                                  .orElseThrow(() -> noSuchMessage(queueName, messageId));
    }

    @GetMapping("/shard-owned-queues/{queueName}/dead-letter-messages")
    public List<ApiShardOwnedMessage> getDeadLetterMessages(@PathVariable String queueName,
                                                            @RequestParam(defaultValue = AdminApiPaths.DEFAULT_START_INDEX) int offset,
                                                            @RequestParam(defaultValue = AdminApiPaths.DEFAULT_PAGE_SIZE) int limit) {
        return shardOwnedQueuesApi.getDeadLetterMessages(principal(), QueueName.of(queueName), offset, limit);
    }

    @DeleteMapping("/shard-owned-queues/{queueName}/messages/{messageId}")
    public DeleteResult deleteMessage(@PathVariable String queueName,
                                      @PathVariable String messageId) {
        return new DeleteResult(shardOwnedQueuesApi.deleteMessage(principal(),
                                                                  QueueName.of(queueName),
                                                                  MessageId.parse(messageId)));
    }

    @PostMapping("/shard-owned-queues/{queueName}/messages/{messageId}/retry")
    public MessageOperationResult retryMessage(@PathVariable String queueName,
                                               @PathVariable String messageId,
                                               @RequestBody(required = false) RetryMessageRequest request) {
        var delay = request == null ? Duration.ZERO : request.delayOrImmediate();
        return new MessageOperationResult(shardOwnedQueuesApi.retryMessage(principal(),
                                                                           QueueName.of(queueName),
                                                                           MessageId.parse(messageId),
                                                                           delay));
    }

    @PostMapping("/shard-owned-queues/{queueName}/messages/{messageId}/mark-as-dead-letter")
    public MessageOperationResult markAsDeadLetterMessage(@PathVariable String queueName,
                                                          @PathVariable String messageId,
                                                          @RequestBody(required = false) MarkAsDeadLetterRequest request) {
        var reason = request == null ? "Marked as dead letter via the admin API" : request.reasonOrDefault();
        return new MessageOperationResult(shardOwnedQueuesApi.markAsDeadLetterMessage(principal(),
                                                                                      QueueName.of(queueName),
                                                                                      MessageId.parse(messageId),
                                                                                      reason));
    }

    @PostMapping("/shard-owned-queues/{queueName}/messages/{messageId}/resurrect")
    public MessageOperationResult resurrectDeadLetterMessage(@PathVariable String queueName,
                                                             @PathVariable String messageId) {
        return new MessageOperationResult(shardOwnedQueuesApi.resurrectDeadLetterMessage(principal(),
                                                                                         QueueName.of(queueName),
                                                                                         MessageId.parse(messageId)));
    }

    @DeleteMapping("/shard-owned-queues/{queueName}/messages")
    public ShardOwnedPurgeResult purgeQueue(@PathVariable String queueName) {
        return new ShardOwnedPurgeResult(shardOwnedQueuesApi.purgeQueue(principal(), QueueName.of(queueName)));
    }

    private Object principal() {
        return principalResolver.requireAuthenticatedPrincipal();
    }

    private static AdminApiResourceNotFoundException noSuchQueue(String queueName) {
        return new AdminApiResourceNotFoundException("No shard-owned queue named '" + queueName + "' is registered.");
    }

    private static AdminApiResourceNotFoundException noSuchMessage(String queueName, String messageId) {
        return new AdminApiResourceNotFoundException("No message '" + messageId + "' in shard-owned queue '" + queueName + "'.");
    }
}
