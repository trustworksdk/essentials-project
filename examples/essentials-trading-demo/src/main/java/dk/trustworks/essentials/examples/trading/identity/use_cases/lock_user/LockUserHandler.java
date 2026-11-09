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

package dk.trustworks.essentials.examples.trading.identity.use_cases.lock_user;

import dk.trustworks.essentials.examples.trading.identity.aggregates.*;
import dk.trustworks.essentials.reactive.command.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Locks the aggregate and the credentials row in one unit of work, so the next login is refused at once rather than
 * when a projection catches up.
 */
@Service
public class LockUserHandler extends AnnotatedCommandHandler {
    private final Users           users;
    private final UserCredentials credentials;

    public LockUserHandler(Users users, UserCredentials credentials) {
        this.users = requireNonNull(users, "No users provided");
        this.credentials = requireNonNull(credentials, "No credentials provided");
    }

    @CmdHandler
    public void handle(LockUser cmd) {
        requireNonNull(cmd, "No cmd provided");
        users.getUser(cmd.userId()).lock(cmd.reason());
        credentials.setLocked(cmd.userId(), true);
    }
}
