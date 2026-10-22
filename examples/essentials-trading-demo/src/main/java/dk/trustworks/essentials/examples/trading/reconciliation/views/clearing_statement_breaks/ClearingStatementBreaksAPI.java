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

package dk.trustworks.essentials.examples.trading.reconciliation.views.clearing_statement_breaks;

import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only (under {@code /api/admin}): the clearing statements received and each one's breaks.
 */
@RestController
@RequestMapping("/api/admin/reconciliation/clearing-statements")
public class ClearingStatementBreaksAPI {
    private final ClearingStatementBreaksQuery query;

    public ClearingStatementBreaksAPI(ClearingStatementBreaksQuery query) {
        this.query = requireNonNull(query, "No query provided");
    }

    @GetMapping
    public List<ClearingStatementBreaksQuery.StatementSummary> statements() {
        return query.statements();
    }

    @GetMapping("/{statementId}/breaks")
    public List<ClearingBreak> breaks(@PathVariable String statementId) {
        return query.breaksOf(statementId);
    }
}
