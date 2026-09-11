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

import java.time.Duration;

/**
 * The request and response bodies that are specific to the shard-owned queue endpoints.
 * <p>
 * Grouped in one holder rather than spread over a {@code dto} package because there are four of them
 * and each is one field. The generic ones — {@code DeleteResult}, {@code CountResult} — are reused
 * from the admin API starter rather than restated here.
 */
public final class ShardOwnedQueueRequests {

    private ShardOwnedQueueRequests() {
    }

    /**
     * @param delay how long to wait before the message becomes deliverable again. Absent or zero means
     *              immediately, which is the useful case once whatever the handler was failing on has
     *              been fixed
     */
    public record RetryMessageRequest(Duration delay) {
        public Duration delayOrImmediate() {
            return delay == null ? Duration.ZERO : delay;
        }
    }

    /**
     * @param reason recorded as the message's last error, so the dead-letter table says a human put it
     *               there rather than showing a handler failure that never happened
     */
    public record MarkAsDeadLetterRequest(String reason) {
        public String reasonOrDefault() {
            return reason == null || reason.isBlank() ? "Marked as dead letter via the admin API" : reason;
        }
    }

    /**
     * Whether a by-id operation found its message and applied.
     *
     * @param applied {@code false} means the message was not there — already delivered and
     *                acknowledged, or already acted on by someone else. It does not mean the operation
     *                failed
     */
    public record MessageOperationResult(boolean applied) {
    }

    /** @param purgedCount rows removed across both lanes and the dead-letter table */
    public record ShardOwnedPurgeResult(long purgedCount) {
    }
}
