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

import dk.trustworks.essentials.shared.security.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Resolves the principal that the {@code *Api} SPIs authorize against.
 * <p>
 * The admin API performs no authentication of its own and depends on no security framework. It asks the consumer's
 * {@link EssentialsAuthenticatedUser} implementation who the caller is; the consumer's
 * {@link EssentialsSecurityProvider} implementation then decides — inside the SPI beans — whether that principal holds
 * the roles an operation requires. How the caller was authenticated in the first place (session, bearer token, mTLS,
 * gateway header, …) is entirely the host application's business.
 * <p>
 * Both SPIs default to their no-access implementations, so an application that has not implemented them rejects every
 * request rather than exposing anything.
 */
public class AdminApiPrincipalResolver {

    private final EssentialsAuthenticatedUser authenticatedUser;

    public AdminApiPrincipalResolver(EssentialsAuthenticatedUser authenticatedUser) {
        this.authenticatedUser = requireNonNull(authenticatedUser, "No authenticatedUser provided");
    }

    /**
     * @return the authenticated caller's principal
     * @throws AdminApiUnauthenticatedException if there is no authenticated caller — mapped to {@code 401}
     */
    public Object requireAuthenticatedPrincipal() {
        if (!authenticatedUser.isAuthenticated()) {
            throw new AdminApiUnauthenticatedException("No authenticated user. The EssentialsAuthenticatedUser "
                                                              + "implementation reports the caller as unauthenticated.");
        }
        var principal = authenticatedUser.getPrincipal();
        if (principal == null) {
            throw new AdminApiUnauthenticatedException("The EssentialsAuthenticatedUser implementation reports the "
                                                              + "caller as authenticated but supplied no principal.");
        }
        return principal;
    }
}
