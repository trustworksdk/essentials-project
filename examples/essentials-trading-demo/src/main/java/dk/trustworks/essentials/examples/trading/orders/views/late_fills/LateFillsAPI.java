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
package dk.trustworks.essentials.examples.trading.orders.views.late_fills;

import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only (under {@code /api/admin}): fills the exchange reported for orders that were already closed.
 */
@RestController
@RequestMapping("/api/admin/orders")
public class LateFillsAPI {
    private final LateFillsQuery query;

    public LateFillsAPI(LateFillsQuery query) {
        this.query = requireNonNull(query, "No query provided");
    }

    @GetMapping("/late-fills")
    public List<LateFill> lateFills() {
        return query.lateFills();
    }
}
