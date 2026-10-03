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

package dk.trustworks.essentials.examples.trading._demo_harness;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Load shaping for the shard-owned queue demo.
 * <p>
 * Two shapes, because they stress different things. <b>Sustained</b> traffic is what the idle and
 * steady-state costs are about — whether the engine's query rate follows the work or the fan-out.
 * <b>Spikes</b> are what ordering and backlog handling are about: a burst arrives faster than
 * handlers can drain it, so the cursor falls behind, the watermark's re-read window opens, and
 * per-key ordering has to survive a queue that is genuinely deep.
 */
@ConfigurationProperties(prefix = "trading-demo.queue-load")
public class QueueLoadGeneratorProperties {

    /** Off by default: the demo should still start on a machine that cannot spare the throughput. */
    private boolean enabled = false;

    /** The queue both lanes are driven through. One queue, two lanes, as the engine models it. */
    private String queueName = "trading-events";

    /**
     * Unordered shards. The ordered lane does not take a number here — it routes on its own fixed
     * space, which is the entire point of that design.
     */
    private int shardCount = 4;

    private Duration sustainedInterval = Duration.ofMillis(250);
    /** Messages per tick on each lane, so the sustained rate is {@code batch / interval} per lane. */
    private int sustainedBatch = 10;

    /** Messages per lane in one spike. Large enough to outrun the handlers and build a backlog. */
    private int spikeSize = 5_000;

    /** Distinct ordering keys. Fewer keys means deeper per-key chains and more head-of-line blocking. */
    private int keyCount = 200;

    /** Simulated work per message. Non-zero, or the handlers never fall behind and a spike is not one. */
    private Duration handlerDelay = Duration.ofMillis(1);

    private int parallelConsumers = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public int getShardCount() {
        return shardCount;
    }

    public void setShardCount(int shardCount) {
        this.shardCount = shardCount;
    }

    public Duration getSustainedInterval() {
        return sustainedInterval;
    }

    public void setSustainedInterval(Duration sustainedInterval) {
        this.sustainedInterval = sustainedInterval;
    }

    public int getSustainedBatch() {
        return sustainedBatch;
    }

    public void setSustainedBatch(int sustainedBatch) {
        this.sustainedBatch = sustainedBatch;
    }

    public int getSpikeSize() {
        return spikeSize;
    }

    public void setSpikeSize(int spikeSize) {
        this.spikeSize = spikeSize;
    }

    public int getKeyCount() {
        return keyCount;
    }

    public void setKeyCount(int keyCount) {
        this.keyCount = keyCount;
    }

    public Duration getHandlerDelay() {
        return handlerDelay;
    }

    public void setHandlerDelay(Duration handlerDelay) {
        this.handlerDelay = handlerDelay;
    }

    public int getParallelConsumers() {
        return parallelConsumers;
    }

    public void setParallelConsumers(int parallelConsumers) {
        this.parallelConsumers = parallelConsumers;
    }
}
