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


package dk.trustworks.essentials.components.queue.shardowned;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Whether a {@link ShardRuntime}'s pumps hold their database connection for as long as they run, or give it back to the
 * pool while their queues are quiet.
 * <p>
 * Every pump holds one connection by default, so a runtime holds {@code pumpThreads + 1} (the wake-up listener's
 * included) whether its queues are busy or quiet. With {@link #elastic(int, Duration)}, only the first
 * {@code minHeld} pumps do; the others, once they have delivered nothing for {@code releaseIdleAfter}, borrow a
 * connection only for a pass with database work - a backed-off sweep, say - and give it back at the end of that pass. Threads cost next to nothing; the connections are what a quiet process wants back.
 * <p>
 * The pool still has to have room for every pump at the peak - the start-up budget check still counts all of them - so
 * this frees connections for the application while queues are quiet, not capacity it can count on during a rush. A
 * released pump's cost is one connection checkout (and the pool's validation of a connection that sat idle) when it
 * next has work.
 *
 * @param minHeld          how many pumps hold their connection always; at least 0, and anything at or above the
 *                         runtime's {@code pumpThreads} means all of them
 * @param releaseIdleAfter how long a pump beyond {@code minHeld} goes without delivering a message before it releases
 *                         its connection. Long enough that a steady trickle keeps it. Measured in deliveries, not
 *                         database work: every idle owner still sweeps, so a pump serving a few dozen owners never
 *                         goes {@code releaseIdleAfter} without a statement
 */
public record PumpConnections(int minHeld, Duration releaseIdleAfter) {
    /**
     * Used by {@link #allHeld()}, where nothing is released
     */
    public static final Duration DEFAULT_RELEASE_IDLE_AFTER = Duration.ofSeconds(10);

    public PumpConnections {
        requireTrue(minHeld >= 0, "minHeld must not be negative, was " + minHeld);
        requireNonNull(releaseIdleAfter, "No releaseIdleAfter provided");
        requireTrue(!releaseIdleAfter.isNegative(), "releaseIdleAfter must not be negative");
    }

    /**
     * Every pump holds its connection for as long as it runs - the default, and how pumps always worked
     */
    public static PumpConnections allHeld() {
        return new PumpConnections(Integer.MAX_VALUE, DEFAULT_RELEASE_IDLE_AFTER);
    }

    /**
     * The first {@code minHeld} pumps hold their connection; the rest release it once they have delivered nothing for
     * {@code releaseIdleAfter}
     */
    public static PumpConnections elastic(int minHeld, Duration releaseIdleAfter) {
        return new PumpConnections(minHeld, releaseIdleAfter);
    }

    /**
     * Whether the pump at {@code index} (from 0) holds its connection always
     */
    boolean holdsAlways(int index) {
        return index < minHeld;
    }
}
