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

package dk.trustworks.essentials.examples.trading.reconciliation.use_cases.assign_case;

import dk.trustworks.essentials.examples.trading.reconciliation.types.CaseId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only: assign a case to an operator.
 */
@RestController
@RequestMapping("/api/admin/reconciliation/cases")
public class AssignCaseAPI {
    public record Assignment(String assignee) {
    }

    private final CommandBus commandBus;

    public AssignCaseAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{caseId}/assignment")
    public void assign(@PathVariable CaseId caseId, @RequestBody Assignment assignment, Principal operator) {
        commandBus.send(new AssignCase(caseId, assignment.assignee(), operator.getName()));
    }
}
