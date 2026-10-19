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

import java.util.Map;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code /api/admin/ef2/noop-stats}: what the EF-2 measurement processors have handled, and at what rate.
 */
@RestController
@RequestMapping("/api/admin/ef2/noop-stats")
public class NoOpProcessorStatsController {
    private final NoOpProcessorStats stats;

    public NoOpProcessorStatsController(NoOpProcessorStats stats) {
        this.stats = requireNonNull(stats, "No stats provided");
    }

    @GetMapping
    public Map<String, Map<String, Object>> stats() {
        return stats.snapshot();
    }

    @PostMapping("/reset")
    public void reset() {
        stats.reset();
    }
}
