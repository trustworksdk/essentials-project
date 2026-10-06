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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A handler that runs longer than the lease is the configuration under which a liveness lapse puts a
 * key in two handlers at once. It must be counted every time, and the WARN must not repeat per message.
 */
class LeaseOverrunTest {

    private static ShardOwnerSettings withLease(Duration leaseTtl) {
        var d = ShardOwnerSettings.defaults();
        return new ShardOwnerSettings(d.readBatchSize(), d.ackBatchSize(), d.ackFlushInterval(), d.chaseDelay(),
                                      d.holeExpiry(), d.sweepInterval(), d.maxHolesPerChase(), d.keyConcurrency(),
                                      d.pollBackstop(), d.maxSweepInterval(), d.pumpThreads(), d.shedGrace(),
                                      leaseTtl, d.watermarkCap());
    }

    @Test
    void a_handler_inside_the_lease_is_not_counted() {
        var metrics = new ShardOwnerMetrics();
        LeaseOverrun.check(metrics, withLease(Duration.ofSeconds(30)), "ordered", 3, System.nanoTime());
        assertThat(metrics.handlersOutlastingLease.sum()).isZero();
    }

    @Test
    void every_handler_that_outlasts_the_lease_is_counted_and_the_warning_is_rate_limited() {
        var metrics  = new ShardOwnerMetrics();
        var settings = withLease(Duration.ofSeconds(1));
        var twoSecondsAgo = System.nanoTime() - TimeUnit.SECONDS.toNanos(2);

        var before = metrics.nextLeaseOverrunWarningNanos.get();
        LeaseOverrun.check(metrics, settings, "ordered", 3, twoSecondsAgo);
        var afterFirst = metrics.nextLeaseOverrunWarningNanos.get();
        LeaseOverrun.check(metrics, settings, "unordered", 1, twoSecondsAgo);

        assertThat(metrics.handlersOutlastingLease.sum()).isEqualTo(2L);
        assertThat(afterFirst).as("the first overrun warns and pushes the next warning out").isGreaterThan(before);
        assertThat(metrics.nextLeaseOverrunWarningNanos.get())
                .as("the second, inside the minute, is counted but does not warn again")
                .isEqualTo(afterFirst);
        assertThat(metrics.snapshot()).containsEntry("handlersOutlastingLease", 2L);
    }
}
