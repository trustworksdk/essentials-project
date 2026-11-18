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
import org.springframework.http.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.*;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Who may call what (phase 1 design §2.3).
 * <ul>
 *     <li><b>Open:</b> actuator health, the login page, the customer UI's static assets, and {@code /webhooks/**} — the
 *     simulators authenticate those by their HMAC signature, not by a login.</li>
 *     <li><b>{@code CUSTOMER} or {@code OPERATOR}:</b> {@code /api/accounts/**}, {@code /api/market-data/**} and the
 *     customer UI's pages ({@code /}, {@code /app/**}, phase 7). Ownership of the account is checked by each account
 *     slice, not here.</li>
 *     <li><b>{@code OPERATOR}:</b> everything else — the demo's admin API and pages, the harness endpoints, and the
 *     Essentials admin API and console. The Essentials admin API's own SPIs stay all-access
 *     ({@code TradingDemoApplication}); this filter chain is what guards it.</li>
 * </ul>
 * Form login for browsers, HTTP basic for API clients and tests.
 * <p>
 * <b>CSRF</b> (phase 7 design §5): a write to the customer API ({@code /api/accounts/**}, {@code /api/market-data/**})
 * authenticated by the session cookie needs the session's CSRF token, which every customer page renders in a
 * {@code <meta>} tag for its script to send back in a header. The load driver does the same. A request carrying
 * HTTP Basic credentials needs none: it is not authenticated by anything a browser attaches by itself, and a cross-site
 * page cannot set that header without a CORS preflight, which nothing here allows. Two exemptions remain:
 * {@code /webhooks/**} (signed instead), and the operator API, every other {@code /api/**} path, which the Essentials
 * admin console calls from the browser on the operator's session without a token. That last one is a demo
 * simplification, recorded here so nobody takes it for a pattern.
 * <p>
 * Who a login is comes from {@code identity}'s {@code UserDetailsService} ({@code IdentitySecurityConfiguration}),
 * which reads the strongly consistent credentials table, so a lock applies to the very next request.
 */
@Configuration
@EnableWebSecurity
public class TradingDemoSecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var paths       = PathPatternRequestMatcher.withDefaults();
        var customerApi = new OrRequestMatcher(paths.matcher("/api/accounts/**"), paths.matcher("/api/market-data/**"));
        var operatorApi = new AndRequestMatcher(paths.matcher("/api/**"), new NegatedRequestMatcher(customerApi));
        RequestMatcher basicCredentials = request -> {
            var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            return authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6);
        };
        http.authorizeHttpRequests(requests -> requests
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/webhooks/**", "/login", "/error", "/customer_ui/**").permitAll()
                    .requestMatchers("/api/accounts/**", "/api/market-data/**", "/", "/app", "/app/**").hasAnyRole(Role.CUSTOMER.name(), Role.OPERATOR.name())
                    .anyRequest().hasRole(Role.OPERATOR.name()))
            .formLogin(withDefaults())
            .httpBasic(withDefaults())
            // An API client gets a 401, not a redirect to the login page (which an HTTP client would follow to a 200)
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                                                                          PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .csrf(csrf -> csrf.ignoringRequestMatchers(paths.matcher("/webhooks/**"), operatorApi, basicCredentials));
        return http.build();
    }
}
