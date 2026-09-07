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

import java.time.Duration;

/**
 * Consumption settings.
 * <p>
 * Notably absent: a thread count. Concurrency is shard count times per-key parallelism, both of which
 * the engine decides, and offering a knob that cannot affect either would be a lie in the shape of an
 * option.
 *
 * @param maxShards         upper bound on shards this consumer holds; rebalancing keeps instances
 *                          within a fair share of each other regardless
 * @param maxAttempts       deliveries before a message is dead-lettered, counting the first
 * @param retryDelay        first backoff
 * @param retryMultiplier   1.0 for fixed backoff, above 1.0 for exponential
 * @param maxRetryDelay     backoff ceiling
 */
/**
 * @param parallelConsumers how many handler invocations THIS consumer may have in flight.
 *                          <p>
 *                          Per consumer, deliberately, and named as in {@code ConsumeFromQueue} where
 *                          it means the same thing. A single process-wide budget was the wrong shape:
 *                          it lets one busy queue starve every other, and it gives a caller no way to
 *                          say that this queue deserves four handlers and that one thirty-two. The
 *                          process-wide {@code handlerConcurrency} remains, but as a ceiling that
 *                          protects whatever the handlers contend for — not as the knob.
 *                          <p>
 *                          <b>The default of 8 is measured, and it is deliberately not the fastest
 *                          value.</b> {@code NextGenConcurrencySweepIT} drains a fixed workload
 *                          through a 2 ms handler at 1, 2, 4 … 128 parallel consumers, interleaved so
 *                          that the drift which ruins an absolute throughput figure is common to
 *                          every arm:
 *                          <pre>
 *                            permits    msg/s    % of peak    msg/s per permit
 *                                  1      399          11%                 399
 *                                  4    1 622          47%                 406
 *                                  8    2 420          70%                 302
 *                                 16    2 924          84%                 183
 *                                 32    3 445          99%                 108
 *                                 64    3 475         100%                  54
 *                          </pre>
 *                          Throughput peaks at 32, and 32 was briefly the default. That was
 *                          optimising the wrong thing. Return per permit is flat to 4 and then
 *                          collapses, and the peak is the ceiling for <em>one consumer alone on the
 *                          machine</em> — whereas a default is what runs when nobody has reasoned
 *                          about their handler, next to every other consumer in the process. Eight
 *                          takes 70% of that peak for a quarter of the budget.
 *                          <p>
 *                          The current implementation has no default at all: {@code ConsumeFromQueue}
 *                          requires the number, and callers in this codebase pick 1, 3 or 5. Treat
 *                          eight as a starting point to lower as often as raise, and re-run the sweep
 *                          for a workload whose handler does not look like a 2 ms wait.
 */
public record ConsumerOptions(int parallelConsumers,
                              int maxShards,
                              int maxAttempts,
                              Duration retryDelay,
                              double retryMultiplier,
                              Duration maxRetryDelay) {

    public static ConsumerOptions defaults() {
        return new ConsumerOptions(8, Integer.MAX_VALUE, 3, Duration.ofMillis(100), 2.0d, Duration.ofSeconds(30));
    }
}
