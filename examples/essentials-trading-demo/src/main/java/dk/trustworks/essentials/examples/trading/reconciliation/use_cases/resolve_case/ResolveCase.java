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

package dk.trustworks.essentials.examples.trading.reconciliation.use_cases.resolve_case;

import dk.trustworks.essentials.examples.trading.accounts.types.AdjustmentId;
import dk.trustworks.essentials.examples.trading.reconciliation.types.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * @param adjustmentId {@code LEDGER_CORRECTED} only: the {@code AdjustCash} the operator posted, case id as its reason
 * @param resolvedBy   the operator sending it; set by the endpoint from the login, never from the body
 */
public record ResolveCase(CaseId caseId, CaseResolution resolution, String note, AdjustmentId adjustmentId, String resolvedBy) {
    public ResolveCase {
        requireNonNull(caseId, "No caseId provided");
        requireNonNull(resolution, "No resolution provided");
        requireNonNull(note, "No note provided");
        requireNonNull(resolvedBy, "No resolvedBy provided");
    }
}
