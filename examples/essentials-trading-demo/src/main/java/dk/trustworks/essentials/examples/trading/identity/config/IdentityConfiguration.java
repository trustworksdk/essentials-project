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

package dk.trustworks.essentials.examples.trading.identity.config;

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.examples.trading.identity.aggregates.*;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring wiring for the {@code identity} bounded context.
 */
@Configuration
public class IdentityConfiguration {

    /**
     * Declares {@link User}, so the admin console lists the stream; {@code User} carries no snapshot or closing-books
     * policy, so this publishes nothing else.
     */
    @Bean
    public EssentialsAggregateDeclarations identityAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                              .declare(Users.AGGREGATE_TYPE, User.class)
                                              .build();
    }

    /**
     * BCrypt by default, with the algorithm id stored in the hash ({@code {bcrypt}...}) so the encoding can change later
     * without invalidating stored passwords.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
