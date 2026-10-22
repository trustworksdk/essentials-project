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

import dk.trustworks.essentials.examples.trading.reconciliation.types.*;

import java.time.*;
import java.util.List;

/**
 * A case as the operator sees it.
 *
 * @param history oldest first; empty in a list, filled for a single case
 */
public record CaseView(String caseId, String statementId, LocalDate businessDate, String kind, String reference, String detail, CaseState state,
                       String assignee, CaseResolution resolution, String note, String adjustmentId, Instant openedAt, Instant updatedAt,
                       List<HistoryEntry> history) {
    public record HistoryEntry(String event, String by, String detail, Instant at) {
    }
}
