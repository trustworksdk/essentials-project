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
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Operator only: close a case with a resolution.
 */
@RestController
@RequestMapping("/api/admin/reconciliation/cases")
public class ResolveCaseAPI {
    /**
     * @param adjustmentId {@code LEDGER_CORRECTED} only
     */
    public record Resolution(CaseResolution resolution, String note, AdjustmentId adjustmentId) {
    }

    private final CommandBus commandBus;

    public ResolveCaseAPI(CommandBus commandBus) {
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @PostMapping("/{caseId}/resolution")
    public void resolve(@PathVariable CaseId caseId, @RequestBody Resolution resolution, Principal operator) {
        commandBus.send(new ResolveCase(caseId, resolution.resolution(), resolution.note() != null ? resolution.note() : "", resolution.adjustmentId(), operator.getName()));
    }
}
