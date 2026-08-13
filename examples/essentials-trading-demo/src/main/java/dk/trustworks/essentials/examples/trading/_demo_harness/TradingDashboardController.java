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

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Serves the lightweight admin dashboard and its summary JSON.
 */
@Controller
public class TradingDashboardController {
    private final TradingDashboardQueryService queryService;
    private final TradingDashboardStreamService streamService;

    public TradingDashboardController(TradingDashboardQueryService queryService,
                                     TradingDashboardStreamService streamService) {
        this.queryService = queryService;
        this.streamService = streamService;
    }

    /**
     * Nothing is mapped at the root, so a bare {@code http://localhost:8080/} used to 404 — the first
     * thing anyone starting the demo tries. Send them to the dashboard instead.
     */
    @GetMapping("/")
    public String root() {
        return "redirect:/admin";
    }

    @GetMapping("/admin")
    public String admin() {
        return "forward:/admin/index.html";
    }

    @GetMapping("/api/admin/dashboard")
    @ResponseBody
    public DashboardSummaryView summary() {
        return queryService.getSummary();
    }

    @GetMapping("/api/admin/dashboard/stream")
    @ResponseBody
    public SseEmitter summaryStream() {
        return streamService.createEmitter();
    }
}
