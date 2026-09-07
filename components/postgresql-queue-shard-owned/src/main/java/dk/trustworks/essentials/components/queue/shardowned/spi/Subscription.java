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

import dk.trustworks.essentials.shared.Lifecycle;

/**
 * A running consumer. Closing it releases the shards it holds, so that another instance can pick them
 * up without waiting out a lease.
 */
public interface Subscription extends Lifecycle, AutoCloseable {

    /** Shards currently held. Moves as instances join and leave. */
    int shardsHeld();

    /** Equivalent to {@link #stop()}. */
    @Override
    default void close() {
        stop();
    }
}
