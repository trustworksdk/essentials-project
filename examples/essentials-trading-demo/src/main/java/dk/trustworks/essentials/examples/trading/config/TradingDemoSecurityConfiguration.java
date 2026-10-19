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

package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.examples.trading.identity.types.Role;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Who may call what (phase 1 design §2.3).
 * <ul>
 *     <li><b>Open:</b> actuator health, the login page, and {@code /webhooks/**} — the simulators authenticate those by
 *     their HMAC signature, not by a login.</li>
 *     <li><b>{@code CUSTOMER} or {@code OPERATOR}:</b> {@code /api/accounts/**}. Ownership of the account is checked by
 *     each account slice, not here.</li>
 *     <li><b>{@code OPERATOR}:</b> everything else — the demo's admin API and pages, the harness endpoints, and the
 *     Essentials admin API and console. The Essentials admin API's own SPIs stay all-access
 *     ({@code TradingDemoApplication}); this filter chain is what guards it.</li>
 * </ul>
 * Form login for browsers, HTTP basic for API clients and tests. CSRF protection is off for {@code /api/**} and
 * {@code /webhooks/**}: the admin pages call the API from vanilla JS without a token. That is a demo simplification
 * the customer UI (phase 7) must not inherit — it will use a cookie-held CSRF token.
 * <p>
 * Who a login is comes from {@code identity}'s {@code UserDetailsService} ({@code IdentitySecurityConfiguration}),
 * which reads the strongly consistent credentials table, so a lock applies to the very next request.
 */
@Configuration
@EnableWebSecurity
public class TradingDemoSecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/webhooks/**", "/login", "/error").permitAll()
                    .requestMatchers("/api/accounts/**", "/api/market-data/**").hasAnyRole(Role.CUSTOMER.name(), Role.OPERATOR.name())
                    .anyRequest().hasRole(Role.OPERATOR.name()))
            .formLogin(withDefaults())
            .httpBasic(withDefaults())
            // An API client gets a 401, not a redirect to the login page (which an HTTP client would follow to a 200)
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                                                                          PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/webhooks/**"));
        return http.build();
    }
}
