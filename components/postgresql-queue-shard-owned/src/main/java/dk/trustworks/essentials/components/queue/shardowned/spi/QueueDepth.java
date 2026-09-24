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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import java.time.Instant;

/**
 * Queue depth, split by lane so that a backlog can be attributed.
 * <p>
 * The lanes fail differently and conflating them hides which one is in trouble: unordered depth means
 * the consumers are behind, ordered depth can mean a single stuck key while every other key is idle.
 * <p>
 * There is deliberately no in-flight count. A shard's owner hands messages to handlers from memory and
 * writes nothing per delivery, so the storage cannot tell a message being handled from one waiting;
 * only a pull session's row lease is visible in it.
 *
 * @param unordered     messages in the unordered lane, including ones being handled
 * @param ordered       messages in the ordered lane, including ones being handled
 * @param deadLettered  dead letters
 * @param oldestReadyAt when the oldest message that is ready for delivery became ready - the minimum
 *                      {@code visible_at} over both lanes, excluding rows a live pull session holds - or
 *                      {@code null} when nothing is ready. Because deliveries are not recorded, this
 *                      includes a message an owner is handling right now, and in the ordered lane a
 *                      message waiting behind its key's head while that head is being handled. A key
 *                      stopped behind a dead letter does not count: the messages behind it are
 *                      dead-lettered too, so it shows in {@code deadLettered} instead
 */
public record QueueDepth(long unordered, long ordered, long deadLettered, Instant oldestReadyAt) {

    public long total() {
        return unordered + ordered;
    }
}
