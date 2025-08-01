/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.ui.util;

import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;

public class SecurityUtils {

    private final EssentialsAuthenticatedUser authenticatedUser;

    public SecurityUtils(EssentialsAuthenticatedUser authenticatedUser) {
        this.authenticatedUser = authenticatedUser;
    }

    public boolean canAccessLocks() {
        return authenticatedUser.hasLockReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canWriteLocks() {
        return authenticatedUser.hasLockWriterRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessQueues() {
        return authenticatedUser.hasQueueReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canWriteQueues() {
        return authenticatedUser.hasQueueWriterRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessSubscriptions() {
        return authenticatedUser.hasSubscriptionReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessPostgresqlStats() {
        return authenticatedUser.hasPostgresqlStatsReaderRole() || authenticatedUser.hasAdminRole();
    }

    public boolean canAccessScheduler() {
        return authenticatedUser.hasSchedulerReaderRole() || authenticatedUser.hasAdminRole();
    }
}
