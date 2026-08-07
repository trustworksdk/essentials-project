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

package dk.trustworks.essentials.examples.trading.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Turns the demo's own precondition failures into a response that says what went wrong.
 * <p>
 * Spring's default handling renders an {@link IllegalStateException} as a bare 500 with no message, which is both the
 * wrong status — nothing failed, a feature is switched off — and useless to whoever called it. The archive endpoints are
 * the case that prompted this: they need {@code essentials.eventstore.archives.enabled=true}, and the whole point of
 * making that dependency optional was to explain the gap rather than refuse to start.
 * <p>
 * Scoped to this demo. The Essentials admin API has its own handler and deliberately withholds detail on 5xx.
 */
@RestControllerAdvice
public class TradingDemoErrorHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailablePrecondition(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                             .body(Map.of("timestamp", OffsetDateTime.now().toString(),
                                          "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                                          "error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                                          "message", e.getMessage() != null ? e.getMessage() : e.toString()));
    }
}
