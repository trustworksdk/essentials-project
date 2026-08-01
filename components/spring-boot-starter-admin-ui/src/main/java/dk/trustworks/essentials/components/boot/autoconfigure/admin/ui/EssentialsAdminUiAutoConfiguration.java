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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import dk.trustworks.essentials.components.boot.autoconfigure.admin.api.EssentialsAdminApiProperties;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Auto-configuration for the optional default admin UI.
 * <p>
 * Serves one Thymeleaf shell and a pair of static assets. All data reaches the browser through the admin
 * API, so this module adds no new way to read or mutate Essentials state — anything the UI can do, the
 * same caller could already do against the contract, subject to the same
 * {@code EssentialsSecurityProvider} decision.
 * <p>
 * Requires the admin API to be enabled: without it the UI would render a shell whose every request
 * 404s, so this backs off rather than presenting a broken console.
 *
 * @see EssentialsAdminUiProperties
 */
@AutoConfiguration
@ConditionalOnClass(SpringTemplateEngine.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "essentials.admin-ui", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "essentials.admin-api", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(EssentialsAdminUiProperties.class)
public class EssentialsAdminUiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EssentialsAdminUiAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AdminUiController essentialsAdminUiController(EssentialsAdminUiProperties uiProperties,
                                                         EssentialsAdminApiProperties apiProperties,
                                                         EssentialsAuthenticatedUser authenticatedUser) {
        log.info("Essentials admin UI available at '{}', calling the admin API at '{}'",
                 uiProperties.getBasePath(), apiProperties.getBasePath());
        return new AdminUiController(uiProperties, apiProperties, authenticatedUser);
    }
}
