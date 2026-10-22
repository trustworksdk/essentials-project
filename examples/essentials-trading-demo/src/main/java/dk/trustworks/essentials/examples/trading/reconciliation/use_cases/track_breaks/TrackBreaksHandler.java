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

package dk.trustworks.essentials.examples.trading.reconciliation.use_cases.track_breaks;

import dk.trustworks.essentials.examples.trading.reconciliation.aggregates.*;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Opens and clears cases as the breaks come and go. Both are idempotent: the automation may see a close twice, or on
 * two instances.
 */
@Service
public class TrackBreaksHandler extends AnnotatedCommandHandler {
    private final ReconciliationCases cases;

    public TrackBreaksHandler(ReconciliationCases cases) {
        this.cases = requireNonNull(cases, "No cases provided");
    }

    @CmdHandler
    public void handle(OpenCase cmd) {
        requireNonNull(cmd, "No cmd provided");
        if (cases.find(cmd.caseId()).isPresent()) {
            return;
        }
        cases.saveNew(new ReconciliationCase(cmd.caseId(), cmd.statementId(), cmd.businessDate(), cmd.kind(), cmd.reference(), cmd.detail()));
    }

    @CmdHandler
    public void handle(ClearCase cmd) {
        requireNonNull(cmd, "No cmd provided");
        cases.find(cmd.caseId()).ifPresent(ReconciliationCase::clear);
    }
}
