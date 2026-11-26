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
package dk.trustworks.essentials.components.eventlog;

import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The parked heads of one subscription, as {@link EventLogAdmin#parkedSummary()} reads them from the database: the
 * cluster's view, whichever node runs the subscription.
 *
 * @param key                      the subscription's progress row
 * @param retryingHeads            parked heads the engine retries by itself
 * @param terminalHeads            parked heads that failed with a {@link ParkEventException}: only an operator retries or
 *                                 discards them
 * @param oldestParkedAt           when the oldest parked head was parked
 * @param nextRetryAt              the earliest due automatic retry of a retrying head, empty without one
 * @param lastFailedAt             when a parked head last failed, empty if none has
 * @param lastTerminalErrorType    the error type of the terminal head that failed last, empty without a terminal head
 * @param lastTerminalErrorMessage its error message, empty without one
 */
public record ParkedSummary(ProgressKey key, long retryingHeads, long terminalHeads, OffsetDateTime oldestParkedAt, Optional<OffsetDateTime> nextRetryAt,
                            Optional<OffsetDateTime> lastFailedAt, Optional<String> lastTerminalErrorType, Optional<String> lastTerminalErrorMessage) {
    public ParkedSummary {
        requireNonNull(key, "No key provided");
        requireNonNull(oldestParkedAt, "No oldestParkedAt provided");
        requireNonNull(nextRetryAt, "No nextRetryAt provided");
        requireNonNull(lastFailedAt, "No lastFailedAt provided");
        requireNonNull(lastTerminalErrorType, "No lastTerminalErrorType provided");
        requireNonNull(lastTerminalErrorMessage, "No lastTerminalErrorMessage provided");
    }
}
