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

package dk.trustworks.essentials.ui;

import com.vaadin.flow.spring.security.AuthenticationContext;
import dk.trustworks.essentials.shared.security.*;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TestAuthenticatedUser implements EssentialsAuthenticatedUser {

    private final AuthenticationContext authenticationContext;

    public TestAuthenticatedUser(AuthenticationContext authenticationContext) {
        this.authenticationContext = authenticationContext;
    }

    @Override
    public Object getPrincipal() {
        return authenticationContext;
    }

    @Override
    public boolean isAuthenticated() {
        return authenticationContext.isAuthenticated();
    }

    @Override
    public boolean hasRole(String role) {
        return authenticationContext.hasRole(role);
    }

    @Override
    public Optional<String> getPrincipalName() {
        return authenticationContext.getPrincipalName();
    }

    @Override
    public boolean hasLockReaderRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.LOCK_READER.getRoleName());
    }

    @Override
    public boolean hasLockWriterRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.LOCK_WRITER.getRoleName());
    }

    @Override
    public boolean hasQueueReaderRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.QUEUE_READER.getRoleName());
    }

    @Override
    public boolean hasQueuePayloadReaderRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.QUEUE_PAYLOAD_READER.getRoleName());
    }

    @Override
    public boolean hasQueueWriterRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.QUEUE_WRITER.getRoleName());
    }

    @Override
    public boolean hasSubscriptionReaderRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.SUBSCRIPTION_READER.getRoleName());
    }

    @Override
    public boolean hasSubscriptionWriterRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.SUBSCRIPTION_WRITER.getRoleName());
    }

    @Override
    public boolean hasPostgresqlStatsReaderRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.POSTGRESQL_STATS_READER.getRoleName());
    }

    @Override
    public boolean hasAdminRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.ESSENTIALS_ADMIN.getRoleName());
    }

    @Override
    public boolean hasSchedulerReaderRole() {
        return authenticationContext.hasRole(EssentialsSecurityRoles.SCHEDULER_READER.getRoleName());
    }

    @Override
    public void logout() {
        authenticationContext.logout();
    }
}
