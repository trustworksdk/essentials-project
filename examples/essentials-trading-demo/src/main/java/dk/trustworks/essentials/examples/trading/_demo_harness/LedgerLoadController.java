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
 * {@code /api/admin/ledger-load}: start a run and read its result. Operator only, like every harness endpoint.
 * <pre>
 * POST /api/admin/ledger-load/start?mode=ORDERED&amp;accounts=1000&amp;cycles=20000&amp;hotAccounts=10&amp;hotSharePercent=50&amp;threads=8
 * GET  /api/admin/ledger-load
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/ledger-load")
public class LedgerLoadController {
    private final LedgerLoadGenerator generator;

    public LedgerLoadController(LedgerLoadGenerator generator) {
        this.generator = requireNonNull(generator, "No generator provided");
    }

    @PostMapping("/start")
    public LedgerLoadResultView start(@RequestParam(defaultValue = "ORDERED") LedgerLoadGenerator.Mode mode,
                                      @RequestParam(defaultValue = "1000") int accounts,
                                      @RequestParam(defaultValue = "20000") int cycles,
                                      @RequestParam(defaultValue = "10") int hotAccounts,
                                      @RequestParam(defaultValue = "50") int hotSharePercent,
                                      @RequestParam(defaultValue = "8") int threads) {
        return generator.start(new LedgerLoadGenerator.Settings(mode, accounts, cycles, hotAccounts, hotSharePercent, threads));
    }

    @GetMapping
    public ResponseEntity<LedgerLoadResultView> status() {
        var status = generator.status();
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> refused(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "message", e.getMessage()));
    }
}
