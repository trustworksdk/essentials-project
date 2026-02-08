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

package dk.trustworks.essentials.ui;

import com.vaadin.flow.spring.security.AuthenticationContext;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.slf4j.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.*;

public class TestSecurityProvider implements EssentialsSecurityProvider {

    private static final Logger log = LoggerFactory.getLogger(TestSecurityProvider.class);

    @Override
    public boolean isAllowed(Object principal, String requiredRole) {
        requiredRole = "ROLE_" + requiredRole;
        if (principal instanceof AuthenticationContext authentication) {
            Collection<? extends GrantedAuthority> authorities = authentication.getGrantedAuthorities();
            log.debug("Authorities: '{}'", authorities);
            return authorities != null && authorities.contains(new SimpleGrantedAuthority(requiredRole));
        }

        return false;
    }

    @Override
    public Optional<String> getPrincipalName(Object principal) {
        if (principal instanceof AuthenticationContext authentication) {
            return authentication.getPrincipalName();
        }
        return Optional.empty();
    }
}
