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

import dk.trustworks.essentials.components.adminapi.rest.dto.ReleaseResult;
import dk.trustworks.essentials.components.foundation.fencedlock.LockName;
import dk.trustworks.essentials.components.foundation.fencedlock.api.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * HTTP surface for {@link DBFencedLockApi}, implementing the contract's {@code fenced-locks} tag.
 * <p>
 * Role enforcement happens inside the SPI, not here.
 */
@RestController
@RequestMapping(AdminApiPaths.BASE_PATH_PLACEHOLDER)
public class FencedLocksController {

    private final DBFencedLockApi          dbFencedLockApi;
    private final AdminApiPrincipalResolver principalResolver;

    public FencedLocksController(DBFencedLockApi dbFencedLockApi, AdminApiPrincipalResolver principalResolver) {
        this.dbFencedLockApi = requireNonNull(dbFencedLockApi, "No dbFencedLockApi provided");
        this.principalResolver = requireNonNull(principalResolver, "No principalResolver provided");
    }

    @GetMapping("/fenced-locks")
    public List<ApiDBFencedLock> getAllLocks() {
        return dbFencedLockApi.getAllLocks(principalResolver.requireAuthenticatedPrincipal());
    }

    @DeleteMapping("/fenced-locks/{lockName}")
    public ReleaseResult releaseLock(@PathVariable String lockName) {
        var released = dbFencedLockApi.releaseLock(principalResolver.requireAuthenticatedPrincipal(),
                                                  LockName.of(lockName));
        return new ReleaseResult(released);
    }
}
