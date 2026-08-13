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

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Minimal admin API for inspecting the runtime load generator.
 */
@RestController
@RequestMapping("/api/admin/load-generator")
public class TradingLoadGeneratorController {
    private final TradingLoadGeneratorManager loadGeneratorManager;

    public TradingLoadGeneratorController(TradingLoadGeneratorManager loadGeneratorManager) {
        this.loadGeneratorManager = loadGeneratorManager;
    }

    @GetMapping
    public TradingLoadGeneratorStatusView status() {
        return loadGeneratorManager.status();
    }

    @PostMapping("/start")
    public TradingLoadGeneratorStatusView start() {
        return loadGeneratorManager.startManually();
    }

    @PostMapping("/stop")
    public TradingLoadGeneratorStatusView stop() {
        return loadGeneratorManager.stopManually();
    }

    @PostMapping("/burst/trade-lifecycles")
    public TradingLoadGeneratorStatusView generateTradeLifecycleBurst(@RequestParam(defaultValue = "100") int count) {
        return executeBurst(() -> loadGeneratorManager.generateTradeLifecycleBurst(count));
    }

    @PostMapping("/burst/trades")
    public TradingLoadGeneratorStatusView generatePendingTradeBurst(@RequestParam(defaultValue = "100") int count) {
        return executeBurst(() -> loadGeneratorManager.generatePendingTradeBurst(count));
    }

    @PostMapping("/burst/settlements")
    public TradingLoadGeneratorStatusView settlePendingTradeBurst(@RequestParam(defaultValue = "100") int count) {
        return executeBurst(() -> loadGeneratorManager.settlePendingTradeBurst(count));
    }

    @PostMapping("/burst/price-updates")
    public TradingLoadGeneratorStatusView generatePriceUpdateBurst(@RequestParam(defaultValue = "100") int count) {
        return executeBurst(() -> loadGeneratorManager.generatePriceUpdateBurst(count));
    }

    @PostMapping("/price-stress/start")
    public TradingLoadGeneratorStatusView startPriceStress(@RequestParam(defaultValue = "500") int count,
                                                           @RequestParam(defaultValue = "100") long intervalMs,
                                                           @RequestParam(defaultValue = "aggregate-event-sourced") String mode) {
        return executeBurst(() -> loadGeneratorManager.startAsyncPriceStress(count, intervalMs, parsePriceStressMode(mode)));
    }

    @PostMapping("/price-stress/stop")
    public TradingLoadGeneratorStatusView stopPriceStress() {
        return loadGeneratorManager.stopAsyncPriceStress();
    }

    @PostMapping("/comparisons/price-path")
    public PricePathScenarioResultView runPricePathComparison(@RequestParam(defaultValue = "100") int count) {
        try {
            return loadGeneratorManager.runPricePathComparisonScenario(count);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/comparisons/trading-account")
    public TradingAccountScenarioResultView runTradingAccountComparison(@RequestParam(defaultValue = "90") int count,
                                                                        @RequestParam(defaultValue = "25") int readPasses,
                                                                        @RequestParam(defaultValue = "20") long eventThreshold) {
        try {
            return loadGeneratorManager.runTradingAccountComparisonScenario(count, readPasses, eventThreshold);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        }
    }

    private TradingLoadGeneratorStatusView executeBurst(BurstOperation operation) {
        try {
            return operation.execute();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface BurstOperation {
        TradingLoadGeneratorStatusView execute();
    }

    private PriceStressMode parsePriceStressMode(String mode) {
        return PriceStressMode.valueOf(mode.trim().replace('-', '_').toUpperCase());
    }
}
