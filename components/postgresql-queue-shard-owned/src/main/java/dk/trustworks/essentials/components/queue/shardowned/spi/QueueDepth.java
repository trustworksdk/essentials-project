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

/**
 * Queue depth, split by lane so that a backlog can be attributed.
 * <p>
 * The lanes fail differently and conflating them hides which one is in trouble: unordered depth means
 * the consumers are behind, ordered depth can mean a single stuck key while every other key is idle.
 */
public record QueueDepth(long unordered, long ordered, long deadLettered) {

    public long total() {
        return unordered + ordered;
    }
}
