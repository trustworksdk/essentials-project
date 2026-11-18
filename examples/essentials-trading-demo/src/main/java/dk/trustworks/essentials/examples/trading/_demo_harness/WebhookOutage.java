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

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 6 fault F-2 (design phase 6 §10, Q-6): while an outage window is open, every webhook request
 * ({@code /webhooks/**}) is answered {@code 503} before anything reads it - no signature check, no intake, no inbox.
 * The simulators then fail the delivery, retry it and in the end dead-letter it, and what they gave up on has to arrive
 * through the catch-up readers. Opened and closed by {@code /api/admin/webhook-outage} ({@link WebhookOutageController});
 * closed by default, and it closes itself when its window ends.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebhookOutage extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(WebhookOutage.class);

    private volatile Instant    until;
    private final    AtomicLong refused = new AtomicLong();

    public record Status(boolean open, Instant until, long refused) {
    }

    /**
     * Opens a window of {@code duration} from now, replacing any open one; {@link Duration#ZERO} closes it.
     */
    public Status open(Duration duration) {
        until = duration.isPositive() ? Instant.now().plus(duration) : null;
        log.info(until == null ? "Webhook outage closed" : "Webhook outage open until {}", until);
        return status();
    }

    public Status status() {
        var current = until;
        return new Status(current != null && Instant.now().isBefore(current), current, refused.get());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var current = until;
        if (current != null && Instant.now().isBefore(current)) {
            refused.incrementAndGet();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
