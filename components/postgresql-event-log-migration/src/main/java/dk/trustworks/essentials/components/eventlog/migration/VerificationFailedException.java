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

package dk.trustworks.essentials.components.eventlog.migration;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The cut-over's verification found a difference: the cut-over transaction rolled back to the
 * state after the last epoch. The report names the windows and rows.
 */
public final class VerificationFailedException extends IllegalStateException {
    private final VerificationReport report;

    public VerificationFailedException(VerificationReport report) {
        super("Migration verification failed; the cut-over is rolled back:\n" + requireNonNull(report, "No report provided").describe());
        this.report = report;
    }

    public VerificationReport report() {
        return report;
    }
}
