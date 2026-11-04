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

package dk.trustworks.essentials.examples.trading._demo_harness.demo_control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The operator page's API (phase 8 §5): the exchange's clock, the background traffic and the system at a glance.
 * Operator only and, like the rest of the operator API, without a CSRF token (phase 7 §5).
 */
@RestController
@RequestMapping("/api/admin/demo")
@ConditionalOnProperty(prefix = "trading-demo.simulators", name = {"exchange.base-url", "webhook-secret-file"})
public class DemoControlAPI {
    private final ExchangeClockControl clock;
    private final BackgroundTraffic    traffic;
    private final JdbcTemplate         jdbcTemplate;

    public record Speed(BigDecimal speed) {
    }

    public record Skip(ExchangeClockControl.Target to) {
    }

    public record Advance(int businessDays) {
    }

    public record Rate(double ordersPerExchangeMinute) {
    }

    /**
     * What the system holds now, from the views: orders and payments by status, reconciliation cases by state.
     */
    public record Overview(Map<String, Long> ordersByStatus, Map<String, Long> paymentsByStatus, Map<String, Long> casesByState) {
    }

    public DemoControlAPI(ExchangeClockControl clock, BackgroundTraffic traffic, JdbcTemplate jdbcTemplate) {
        this.clock = requireNonNull(clock, "No clock provided");
        this.traffic = requireNonNull(traffic, "No traffic provided");
        this.jdbcTemplate = requireNonNull(jdbcTemplate, "No jdbcTemplate provided");
    }

    @GetMapping("/clock")
    public ExchangeClockControl.ClockState clock() {
        return clock.state();
    }

    @PostMapping("/clock/pause")
    public ExchangeClockControl.ClockState pause() {
        clock.pause();
        return clock.state();
    }

    @PostMapping("/clock/resume")
    public ExchangeClockControl.ClockState resume() {
        clock.resume();
        return clock.state();
    }

    @PutMapping("/clock/speed")
    public ExchangeClockControl.ClockState speed(@RequestBody Speed speed) {
        clock.speed(requireNonNull(speed, "No speed provided").speed());
        return clock.state();
    }

    @PostMapping("/clock/skip")
    public ResponseEntity<ExchangeClockControl.Job> skip(@RequestBody Skip skip) {
        return ResponseEntity.accepted().body(clock.skip(requireNonNull(skip, "No skip provided").to()));
    }

    @PostMapping("/clock/advance")
    public ResponseEntity<ExchangeClockControl.Job> advance(@RequestBody Advance advance) {
        return ResponseEntity.accepted().body(clock.advance(requireNonNull(advance, "No advance provided").businessDays()));
    }

    @GetMapping("/traffic")
    public BackgroundTraffic.Status traffic() {
        return traffic.status();
    }

    @PostMapping("/traffic/start")
    public BackgroundTraffic.Status startTraffic(@RequestBody(required = false) Rate rate) {
        return traffic.start(rate != null ? rate.ordersPerExchangeMinute() : 30);
    }

    @PutMapping("/traffic/rate")
    public BackgroundTraffic.Status rate(@RequestBody Rate rate) {
        return traffic.rate(requireNonNull(rate, "No rate provided").ordersPerExchangeMinute());
    }

    @PostMapping("/traffic/burst")
    public BackgroundTraffic.Status burst() {
        return traffic.burst();
    }

    @PostMapping("/traffic/stop")
    public BackgroundTraffic.Status stopTraffic() {
        return traffic.stop();
    }

    @GetMapping("/overview")
    public Overview overview() {
        return new Overview(counts("SELECT status AS k, count(*) AS n FROM projection_orders GROUP BY status"),
                            counts("SELECT lower(kind) || ' ' || status AS k, count(*) AS n FROM projection_funding_payment GROUP BY kind, status"),
                            counts("SELECT state AS k, count(*) AS n FROM projection_reconciliation_case GROUP BY state"));
    }

    private Map<String, Long> counts(String sql) {
        var counts = new TreeMap<String, Long>();
        jdbcTemplate.query(sql, rs -> { counts.put(rs.getString("k"), rs.getLong("n")); });
        return counts;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
