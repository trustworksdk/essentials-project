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

import dk.trustworks.essentials.examples.trading.identity.aggregates.*;
import dk.trustworks.essentials.reactive.command.*;
import org.slf4j.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of {@code identity.register_user}. Writes the credentials row and appends
 * {@code UserRegistered} in the command bus's one unit of work: a duplicate email fails the insert, the unit of work
 * rolls back, and no half-registered user is left behind.
 * <p>
 * A repeated registration of the same {@code userId} with the same email fails on the credentials row too, which is
 * the right answer for a demo whose callers supply the id: retrying a registration that succeeded reports a conflict
 * rather than silently succeeding with possibly different details.
 */
@Service
public class RegisterUserHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(RegisterUserHandler.class);

    private final Users           users;
    private final UserCredentials credentials;
    private final PasswordEncoder passwordEncoder;

    public RegisterUserHandler(Users users, UserCredentials credentials, PasswordEncoder passwordEncoder) {
        this.users = requireNonNull(users, "No users provided");
        this.credentials = requireNonNull(credentials, "No credentials provided");
        this.passwordEncoder = requireNonNull(passwordEncoder, "No passwordEncoder provided");
    }

    @CmdHandler
    public void handle(RegisterUser cmd) {
        requireNonNull(cmd, "No cmd provided");
        log.debug("===> Registering user '{}' <{}> with roles {}", cmd.userId(), cmd.email(), cmd.roles());
        credentials.add(cmd.userId(), cmd.email(), passwordEncoder.encode(cmd.password()), cmd.roles());
        users.registerNewUser(new User(cmd.userId(), cmd.email(), cmd.displayName(), cmd.roles()));
    }
}
