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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.examples.trading.identity.types.*;
import dk.trustworks.essentials.examples.trading.identity.use_cases.register_user.RegisterUser;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Registers the configured demo users at startup, so there is an operator to log in as on a fresh database: without it
 * nothing behind the login is reachable at all, tests included.
 * <p>
 * Idempotent without reading anything: a user whose email is already registered fails the registration's uniqueness
 * guard ({@link EmailAlreadyRegisteredException}), and that is taken as "already seeded".
 */
@Component
@Order(0)
@EnableConfigurationProperties(DemoUsersProperties.class)
public class DemoUsersSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoUsersSeeder.class);

    private final DemoUsersProperties properties;
    private final CommandBus          commandBus;

    public DemoUsersSeeder(DemoUsersProperties properties, CommandBus commandBus) {
        this.properties = requireNonNull(properties, "No properties provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var user : properties.getSeedUsers()) {
            try {
                commandBus.send(new RegisterUser(UserId.of(user.getUserId()),
                                                 Email.of(user.getEmail()),
                                                 user.getDisplayName(),
                                                 user.getRoles(),
                                                 user.getPassword()));
                log.info("Seeded demo user <{}> with roles {}", user.getEmail(), user.getRoles());
            } catch (RuntimeException e) {
                if (isAlreadyRegistered(e)) {
                    log.debug("Demo user <{}> already registered", user.getEmail());
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * The command bus may wrap the handler's exception, so the cause chain is searched.
     */
    private static boolean isAlreadyRegistered(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof EmailAlreadyRegisteredException) {
                return true;
            }
        }
        return false;
    }
}
