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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api.*;
import org.springframework.web.bind.annotation.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link CdcApi}, implementing the contract's {@code cdc} tag.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class CdcController {

    private final CdcApi                    cdcApi;
    private final AdminApiPrincipalResolver principalResolver;

    public CdcController(CdcApi cdcApi, AdminApiPrincipalResolver principalResolver) {
        this.cdcApi = requireNonNull(cdcApi, "No cdcApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/event-store/cdc/status")
    public ApiCdcStatus getStatus() {
        return cdcApi.getStatus(principalResolver.requireAuthenticatedPrincipal());
    }
}
