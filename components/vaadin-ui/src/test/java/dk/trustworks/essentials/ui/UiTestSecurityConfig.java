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

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import dk.trustworks.essentials.shared.security.*;
import dk.trustworks.essentials.ui.view.AdminLoginView;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.*;

import java.util.*;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class UiTestSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/tasks", "/tasks/**").permitAll());
        return http.with(VaadinSecurityConfigurer.vaadin(), configurer ->
                configurer.loginView(AdminLoginView.class)).build();
    }

    @Bean
    public EssentialsSecurityProvider essentialsSecurityProvider() {
        return new TestSecurityProvider();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    public static String[] essentialsSecurityRoles() {
        List<String> roles = Arrays.stream(EssentialsSecurityRoles.values()).map(EssentialsSecurityRoles::getRoleName).collect(Collectors.toList());
        roles.add("ADMIN");
        return roles.toArray(String[]::new);
    }

    @Bean
    UserDetailsManager userDetailsManager() {
        String[] roles = essentialsSecurityRoles();
        UserDetails admin = User.withUsername("admin")
                                .password(passwordEncoder().encode("admin"))
                                .roles(roles)
                                .build();
        UserDetails lasse = User.withUsername("lasse")
                                .password(passwordEncoder().encode("admin"))
                                .roles(roles)
                                .build();
        UserDetails jeppe = User.withUsername("jeppe")
                                .password(passwordEncoder().encode("admin"))
                                .roles(roles)
                                .build();

        return new InMemoryUserDetailsManager(admin, lasse, jeppe);
    }
}
