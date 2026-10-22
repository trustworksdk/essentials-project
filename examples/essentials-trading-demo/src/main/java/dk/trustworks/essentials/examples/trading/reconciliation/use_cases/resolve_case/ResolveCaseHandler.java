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

import dk.trustworks.essentials.examples.trading.reconciliation.aggregates.ReconciliationCases;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Closes a case with the operator's resolution. Books nothing (Q-2).
 */
@Service
public class ResolveCaseHandler extends AnnotatedCommandHandler {
    private final ReconciliationCases cases;

    public ResolveCaseHandler(ReconciliationCases cases) {
        this.cases = requireNonNull(cases, "No cases provided");
    }

    @CmdHandler
    public void handle(ResolveCase cmd) {
        requireNonNull(cmd, "No cmd provided");
        cases.get(cmd.caseId()).resolve(cmd.resolution(), cmd.note(), cmd.adjustmentId(), cmd.resolvedBy());
    }
}
