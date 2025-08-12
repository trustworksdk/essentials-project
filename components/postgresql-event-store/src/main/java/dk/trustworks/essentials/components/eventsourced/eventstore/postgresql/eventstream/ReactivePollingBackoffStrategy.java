/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream;

import org.slf4j.*;

import java.time.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Implements intelligent exponential backoff strategy for reactive event stream polling.
 * This strategy dynamically adjusts polling intervals based on activity levels and provides
 * immediate response capabilities when notifications indicate new events are available.
 *
 * <p>Key features:
 * <ul>
 *   <li>Exponential backoff during quiet periods to reduce database load</li>
 *   <li>Immediate reset to minimum interval when activity is detected</li>
 *   <li>Configurable minimum and maximum intervals</li>
 *   <li>Activity tracking with decay over time</li>
 * </ul>
 */
public class ReactivePollingBackoffStrategy {
    private static final Logger log = LoggerFactory.getLogger(ReactivePollingBackoffStrategy.class);

    private final Duration minimumInterval;
    private final Duration maximumInterval;
    private final double   backoffMultiplier;
    private final Duration activityWindowDuration;

    private final AtomicReference<Duration> currentInterval;
    private final AtomicInteger             consecutiveEmptyPolls;
    private final AtomicReference<Instant>  lastActivityTime;

    /**
     * Creates a new reactive polling backoff strategy with default settings
     */
    public ReactivePollingBackoffStrategy() {
        this(Duration.ofMillis(100),     // minimum interval: 100ms
             Duration.ofMinutes(5),      // maximum interval: 5 minutes
             2.0,                        // double the interval each time
             Duration.ofMinutes(10));    // activity window: 10 minutes
    }

    /**
     * Creates a new reactive polling backoff strategy with custom settings
     *
     * @param minimumInterval        the minimum polling interval (used when activity is detected)
     * @param maximumInterval        the maximum polling interval (used during extended quiet periods)
     * @param backoffMultiplier      the multiplier for exponential backoff (e.g., 2.0 doubles the interval)
     * @param activityWindowDuration how long to remember activity for decay calculations
     */
    public ReactivePollingBackoffStrategy(Duration minimumInterval,
                                          Duration maximumInterval,
                                          double backoffMultiplier,
                                          Duration activityWindowDuration) {
        this.minimumInterval = requireNonNull(minimumInterval, "No minimumInterval provided");
        this.maximumInterval = requireNonNull(maximumInterval, "No maximumInterval provided");
        this.backoffMultiplier = backoffMultiplier;
        this.activityWindowDuration = requireNonNull(activityWindowDuration, "No activityWindowDuration provided");

        if (minimumInterval.compareTo(maximumInterval) >= 0) {
            throw new IllegalArgumentException("minimumInterval must be less than maximumInterval");
        }
        if (backoffMultiplier <= 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be greater than 1.0");
        }

        this.currentInterval = new AtomicReference<>(minimumInterval);
        this.consecutiveEmptyPolls = new AtomicInteger(0);
        this.lastActivityTime = new AtomicReference<>(Instant.now());
    }

    /**
     * Gets the current polling interval based on recent activity levels
     *
     * @return the current recommended polling interval
     */
    public Duration getCurrentInterval() {
        return currentInterval.get();
    }

    /**
     * Records that polling returned no new events, triggering backoff logic
     *
     * @param consecutiveEmptyCount the number of consecutive empty polls
     */
    public void recordEmptyPoll(int consecutiveEmptyCount) {
        consecutiveEmptyPolls.set(consecutiveEmptyCount);

        // Apply exponential backoff based on consecutive empty polls
        if (consecutiveEmptyCount > 0 && consecutiveEmptyCount % 3 == 0) { // Every 3 empty polls
            Duration newInterval = Duration.ofMillis(
                    Math.min(
                            (long) (currentInterval.get().toMillis() * backoffMultiplier),
                            maximumInterval.toMillis()
                            )
                                                    );

            if (!newInterval.equals(currentInterval.get())) {
                currentInterval.set(newInterval);
                log.debug("Increased polling interval to {} after {} consecutive empty polls",
                          newInterval, consecutiveEmptyCount);
            }
        }
    }

    /**
     * Records that new events were found, resetting backoff to minimum interval
     *
     * @param eventCount the number of events found
     */
    public void recordActivity(int eventCount) {
        if (eventCount > 0) {
            lastActivityTime.set(Instant.now());
            consecutiveEmptyPolls.set(0);

            // Reset to minimum interval when activity is detected
            if (!currentInterval.get().equals(minimumInterval)) {
                currentInterval.set(minimumInterval);
                log.debug("Reset polling interval to {} due to activity ({} events)",
                          minimumInterval, eventCount);
            }
        }
    }

    /**
     * Records that immediate notifications are available, ensuring minimal polling interval
     */
    public void recordNotificationActivity() {
        lastActivityTime.set(Instant.now());
        consecutiveEmptyPolls.set(0);
        currentInterval.set(minimumInterval);
        log.trace("Reset polling interval to {} due to notification activity", minimumInterval);
    }

    /**
     * Gets statistics about the current backoff state
     *
     * @return BackoffStatistics containing current state information
     */
    public BackoffStatistics getStatistics() {
        Instant  lastActivity          = lastActivityTime.get();
        Duration timeSinceLastActivity = Duration.between(lastActivity, Instant.now());

        return new BackoffStatistics(
                currentInterval.get(),
                consecutiveEmptyPolls.get(),
                timeSinceLastActivity,
                isInQuietPeriod()
        );
    }

    /**
     * Determines if the system is currently in a quiet period (extended time without activity)
     *
     * @return true if in quiet period, false otherwise
     */
    public boolean isInQuietPeriod() {
        Duration timeSinceLastActivity = Duration.between(lastActivityTime.get(), Instant.now());
        return timeSinceLastActivity.compareTo(activityWindowDuration) > 0;
    }

    /**
     * Statistics about the current backoff state
     */
    public static class BackoffStatistics {
        public final Duration currentInterval;
        public final int      consecutiveEmptyPolls;
        public final Duration timeSinceLastActivity;
        public final boolean  inQuietPeriod;

        private BackoffStatistics(Duration currentInterval,
                                  int consecutiveEmptyPolls,
                                  Duration timeSinceLastActivity,
                                  boolean inQuietPeriod) {
            this.currentInterval = currentInterval;
            this.consecutiveEmptyPolls = consecutiveEmptyPolls;
            this.timeSinceLastActivity = timeSinceLastActivity;
            this.inQuietPeriod = inQuietPeriod;
        }

        @Override
        public String toString() {
            return "BackoffStatistics{" +
                    "currentInterval=" + currentInterval +
                    ", consecutiveEmptyPolls=" + consecutiveEmptyPolls +
                    ", timeSinceLastActivity=" + timeSinceLastActivity +
                    ", inQuietPeriod=" + inQuietPeriod +
                    '}';
        }
    }
}