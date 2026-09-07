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
 * A message's identity.
 * <p>
 * Structured rather than opaque, because the structure is the addressing scheme: a message lives in
 * exactly one lane of one shard, and that is what makes it findable without an index and ownable
 * without a lock. An opaque id would hide the one fact every operation on it depends on.
 */
public record MessageId(Lane lane, int shard, long sequence) {

    public enum Lane {
        UNORDERED,
        ORDERED
    }
}
