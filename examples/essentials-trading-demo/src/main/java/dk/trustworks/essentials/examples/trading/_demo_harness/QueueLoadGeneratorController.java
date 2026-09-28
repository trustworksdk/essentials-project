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

import org.springframework.web.bind.annotation.*;

/**
 * Drive and observe the shard-owned queue load from outside the process.
 * <p>
 * Under the demo's own {@code /api/admin} base path rather than the framework admin API, for the
 * same reason the trading load generator is: this is harness, not product.
 */
@RestController
@RequestMapping("/api/admin/queue-load")
public class QueueLoadGeneratorController {

    private final QueueLoadGenerator generator;

    public QueueLoadGeneratorController(QueueLoadGenerator generator) {
        this.generator = generator;
    }

    /**
     * Enqueued and handled per lane, ordering violations, live depth and ownership.
     * <p>
     * Watch {@code orderedDepth} rise and drain after a spike, {@code orderViolations} stay at zero
     * while it does, and {@code unownedShards} stay at zero throughout — that last one is the signal
     * depth cannot give you, because a queue nobody is consuming and a queue that is merely busy look
     * identical by depth alone.
     */
    @GetMapping
    public QueueLoadGenerator.QueueLoadStatus status() {
        return generator.status();
    }

    @PostMapping("/start")
    public QueueLoadGenerator.QueueLoadStatus start() {
        generator.start();
        return generator.status();
    }

    @PostMapping("/stop")
    public QueueLoadGenerator.QueueLoadStatus stop() {
        generator.stop();
        return generator.status();
    }

    /**
     * One burst on each lane, enqueued as a single batch per lane.
     *
     * @param size messages per lane; defaults to the configured spike size
     */
    @PostMapping("/spike")
    public SpikeResult spike(@RequestParam(required = false, defaultValue = "0") int size) {
        var enqueued = generator.spike(size);
        return new SpikeResult(enqueued, generator.status());
    }

    public record SpikeResult(int enqueued, QueueLoadGenerator.QueueLoadStatus status) {
    }
}
