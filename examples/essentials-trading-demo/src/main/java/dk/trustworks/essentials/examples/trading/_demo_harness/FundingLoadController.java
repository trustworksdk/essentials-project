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

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * {@code /api/admin/funding-load}: start a run and read its result. Operator only, like every harness endpoint.
 * <pre>
 * POST /api/admin/funding-load/start?customers=200&amp;depositsPerCustomer=5&amp;withdrawalsPerCustomer=5&amp;threads=32
 * GET  /api/admin/funding-load
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/funding-load")
public class FundingLoadController {
    private final FundingLoadGenerator generator;

    public FundingLoadController(FundingLoadGenerator generator) {
        this.generator = requireNonNull(generator, "No generator provided");
    }

    @PostMapping("/start")
    public FundingLoadResultView start(@RequestParam(defaultValue = "200") int customers,
                                       @RequestParam(defaultValue = "5") int depositsPerCustomer,
                                       @RequestParam(defaultValue = "5") int withdrawalsPerCustomer,
                                       @RequestParam(defaultValue = "32") int threads) {
        return generator.start(new FundingLoadGenerator.Settings(customers, depositsPerCustomer, withdrawalsPerCustomer, threads));
    }

    @GetMapping
    public ResponseEntity<FundingLoadResultView> status() {
        var status = generator.status();
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> refused(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "message", e.getMessage()));
    }
}
