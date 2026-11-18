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

package dk.trustworks.essentials.examples.trading.identity.types;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The logged-in principal. A Spring Security {@link User} that also carries the {@link UserId}, so a slice that must
 * check ownership ("is this the caller's account?") reads the id off the principal instead of looking the email up on
 * every request. In {@code types/} because other contexts' API files take it as an
 * {@code @AuthenticationPrincipal} parameter.
 */
public class AuthenticatedUser extends User {
    private final UserId userId;

    public AuthenticatedUser(UserId userId, String username, String passwordHash, boolean locked, Collection<? extends GrantedAuthority> authorities) {
        super(username, passwordHash, true, true, true, !locked, authorities);
        this.userId = requireNonNull(userId, "No userId provided");
    }

    public UserId userId() {
        return userId;
    }

    public boolean isOperator() {
        return getAuthorities().stream().anyMatch(authority -> ("ROLE_" + Role.OPERATOR.name()).equals(authority.getAuthority()));
    }
}
