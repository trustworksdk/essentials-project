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

import dk.trustworks.essentials.examples.trading.identity.aggregates.UserCredentials;
import dk.trustworks.essentials.examples.trading.identity.types.*;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;

/**
 * How a login becomes an {@link AuthenticatedUser}: looked up by email in {@link UserCredentials}, the strongly
 * consistent half of a user, so a lock applies to the very next request. The filter chain itself is app-level
 * ({@code config/TradingDemoSecurityConfiguration}).
 */
@Configuration
public class IdentitySecurityConfiguration {

    @Bean
    public UserDetailsService userDetailsService(UserCredentials credentials) {
        return username -> {
            Email email;
            try {
                email = Email.of(username);
            } catch (IllegalArgumentException e) {
                throw new UsernameNotFoundException("Not a user");
            }
            return credentials.findByEmail(email)
                              .map(stored -> new AuthenticatedUser(stored.userId(),
                                                                   stored.email().toString(),
                                                                   stored.passwordHash(),
                                                                   stored.locked(),
                                                                   stored.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList()))
                              .orElseThrow(() -> new UsernameNotFoundException("Not a user"));
        };
    }
}
