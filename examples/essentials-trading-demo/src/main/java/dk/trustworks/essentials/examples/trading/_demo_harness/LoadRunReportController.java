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

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code GET /api/admin/load-report?runPrefix=LD-<run>-&accountPrefix=LDA-&bucketSeconds=10}: what the brokerage
 * recorded of a load-driver run ({@link LoadRunReport}). Operator only, as every {@code /api/admin} path.
 */
@RestController
@RequestMapping("/api/admin/load-report")
public class LoadRunReportController {
    private final LoadRunReport report;

    public LoadRunReportController(LoadRunReport report) {
        this.report = requireNonNull(report, "No report provided");
    }

    @GetMapping
    public LoadRunReport.Report report(@RequestParam String runPrefix,
                                       @RequestParam(defaultValue = "LDA-") String accountPrefix,
                                       @RequestParam(defaultValue = "10") long bucketSeconds) {
        return report.report(runPrefix, accountPrefix, Duration.ofSeconds(Math.max(1, bucketSeconds)));
    }
}
