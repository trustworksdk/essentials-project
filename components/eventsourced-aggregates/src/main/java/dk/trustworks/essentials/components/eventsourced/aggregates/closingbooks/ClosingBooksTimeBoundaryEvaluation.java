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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Result of evaluating a configured time boundary against the current clock time.
 *
 * @param resolvedPeriodId the current period id derived from the configured time boundary
 * @param advancedPeriods  how many boundary windows the period advanced relative to the aggregate's current period id
 */
public record ClosingBooksTimeBoundaryEvaluation(String resolvedPeriodId,
                                                 long advancedPeriods) {
    public ClosingBooksTimeBoundaryEvaluation {
        requireNonNull(resolvedPeriodId, "No resolvedPeriodId provided");
        if (advancedPeriods < 0) {
            throw new IllegalArgumentException("advancedPeriods must be >= 0");
        }
    }

    public boolean boundaryAdvanced() {
        return advancedPeriods > 0;
    }

    public boolean gapDetected() {
        return advancedPeriods > 1;
    }
}
