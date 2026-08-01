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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring of the admin UI auto-configuration. No database and no HTTP calls — the UI's data source is the
 * API, which is out of scope here.
 */
class EssentialsAdminUiAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(EssentialsAdminUiAutoConfiguration.class))
                    .withBean(EssentialsAdminApiProperties.class)
                    .withBean(EssentialsAuthenticatedUser.class, EssentialsAuthenticatedUser.NoAccessAuthenticatedUser::new);

    @Test
    void the_ui_is_wired_by_default_when_the_starter_is_on_the_classpath() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(AdminUiController.class)
                                                        .hasSingleBean(EssentialsAdminUiProperties.class));
    }

    @Test
    void the_ui_can_be_switched_off_independently_of_the_api() {
        contextRunner.withPropertyValues("essentials.admin-ui.enabled=false")
                     .run(context -> assertThat(context).doesNotHaveBean(AdminUiController.class));
    }

    /** A UI whose every request would 404 is worse than no UI, so it backs off with the API. */
    @Test
    void the_ui_backs_off_when_the_api_is_disabled() {
        contextRunner.withPropertyValues("essentials.admin-api.enabled=false")
                     .run(context -> assertThat(context).doesNotHaveBean(AdminUiController.class));
    }

    @Test
    void the_mount_point_can_be_relocated() {
        contextRunner.withPropertyValues("essentials.admin-ui.base-path=/internal/console")
                     .run(context -> assertThat(context.getBean(EssentialsAdminUiProperties.class).getBasePath())
                             .isEqualTo("/internal/console"));
    }
}
