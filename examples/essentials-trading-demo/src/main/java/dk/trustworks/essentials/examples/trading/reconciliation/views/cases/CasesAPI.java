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

package dk.trustworks.essentials.examples.trading.reconciliation.views.cases;

import dk.trustworks.essentials.examples.trading.reconciliation.types.CaseState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only: the reconciliation cases, filtered by state, and one case with its history.
 */
@RestController
@RequestMapping("/api/admin/reconciliation/cases")
public class CasesAPI {
    private final CasesProjection cases;

    public CasesAPI(CasesProjection cases) {
        this.cases = requireNonNull(cases, "No cases provided");
    }

    @GetMapping
    public List<CaseView> cases(@RequestParam(name = "state", required = false) CaseState state) {
        return cases.cases(state);
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseView> reconciliationCase(@PathVariable String caseId) {
        return ResponseEntity.of(cases.caseWithHistory(caseId));
    }
}
