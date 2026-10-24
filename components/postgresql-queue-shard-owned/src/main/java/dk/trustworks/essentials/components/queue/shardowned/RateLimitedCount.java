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

import java.util.concurrent.atomic.*;

/**
 * A rate-limited WARN that says how many times its event happened since the last one: an {@link ErrorWindow} plus a
 * count of the events recorded since the window was last taken. The first event is a WARN, the later ones within the
 * minute are logged at DEBUG, and the next event once the minute has passed is a WARN counting every event since the
 * last WARN, itself included. One count per thing the WARN is about - the owner, for both of its uses,
 * {@link InDoubtReleaseFailures} and {@link OwedMoveReleaseRefusals}, which name what the event is.
 * <p>
 * The counter and the window are atomics, and {@link ErrorWindow#take(AtomicLong, long)} lets one caller take the
 * window, so a count may be recorded from several threads at once ({@link OwedMoveReleaseRefusals} is asked from
 * whichever thread releases). The caller that takes the window subtracts what it read rather than resetting to zero, so
 * an event another thread recorded between the increment and the take is counted in the next WARN rather than lost.
 */
final class RateLimitedCount {
    private final AtomicLong    warning          = new AtomicLong(ErrorWindow.NEVER_TAKEN);
    private final AtomicInteger sinceLastWarning = new AtomicInteger();

    /**
     * Record one more event at {@code now} ({@code System.nanoTime()}, or an injected clock).
     *
     * @return how many events were recorded since the last WARN, this one included, when this one is to be logged at
     * WARN; {@code 0} when it is to be logged at DEBUG
     */
    int record(long now) {
        var sinceWarning = sinceLastWarning.incrementAndGet();
        if (ErrorWindow.take(warning, now)) {
            sinceLastWarning.addAndGet(-sinceWarning);
            return sinceWarning;
        }
        return 0;
    }
}
