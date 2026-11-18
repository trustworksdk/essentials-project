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

package dk.trustworks.essentials.examples.trading.identity.use_cases.register_user;

import dk.trustworks.essentials.examples.trading.identity.types.*;

import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Register a user with an initial password. Both the command and the request body of {@code POST /api/admin/users}.
 * <p>
 * <b>Carries a plaintext password, so it must only ever be sent with {@code CommandBus.send}</b> — never
 * {@code sendAndDontWait}, which would persist it in a durable queue — and {@link #toString()} masks it so no log line
 * can print it.
 * <p>
 * The caller supplies the {@link UserId}, so a retried registration addresses the same user.
 */
public record RegisterUser(UserId userId,
                           Email email,
                           String displayName,
                           Set<Role> roles,
                           String password) {
    public static final int MINIMUM_PASSWORD_LENGTH = 8;

    public RegisterUser {
        requireNonNull(userId, "No userId provided");
        requireNonNull(email, "No email provided");
        requireNonNull(displayName, "No displayName provided");
        requireNonNull(roles, "No roles provided");
        requireTrue(!roles.isEmpty(), "A user must have at least one role");
        requireNonNull(password, "No password provided");
        requireTrue(password.length() >= MINIMUM_PASSWORD_LENGTH, "A password must have at least " + MINIMUM_PASSWORD_LENGTH + " characters");
        roles = Set.copyOf(roles);
    }

    @Override
    public String toString() {
        return "RegisterUser[userId=" + userId + ", email=" + email + ", displayName=" + displayName + ", roles=" + roles + ", password=****]";
    }
}
