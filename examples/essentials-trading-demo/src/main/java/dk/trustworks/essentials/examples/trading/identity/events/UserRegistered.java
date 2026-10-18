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

package dk.trustworks.essentials.examples.trading.identity.events;

import dk.trustworks.essentials.examples.trading.identity.types.*;

import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * A user has been registered. The first event of every {@code User} stream.
 */
public record UserRegistered(UserId userId,
                             Email email,
                             String displayName,
                             Set<Role> roles) implements UserEvent {
    public UserRegistered {
        requireNonNull(userId, "No userId provided");
        requireNonNull(email, "No email provided");
        requireNonNull(displayName, "No displayName provided");
        requireNonNull(roles, "No roles provided");
        requireTrue(!roles.isEmpty(), "A user must have at least one role");
        roles = Set.copyOf(roles);
    }
}
