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
 * {@code POST /api/admin/webhook-outage?seconds=60} opens an F-2 outage window ({@link WebhookOutage}), {@code seconds=0}
 * closes it; {@code GET} reports it and how many webhook requests it refused since startup. Operator only, as every
 * {@code /api/admin} path.
 */
@RestController
@RequestMapping("/api/admin/webhook-outage")
public class WebhookOutageController {
    private final WebhookOutage outage;

    public WebhookOutageController(WebhookOutage outage) {
        this.outage = requireNonNull(outage, "No outage provided");
    }

    @GetMapping
    public WebhookOutage.Status status() {
        return outage.status();
    }

    @PostMapping
    public WebhookOutage.Status open(@RequestParam long seconds) {
        return outage.open(Duration.ofSeconds(Math.max(0, seconds)));
    }
}
