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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.api;

import dk.trustworks.essentials.components.adminapi.rest.AdminApiPaths;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Essentials admin HTTP API.
 */
@ConfigurationProperties(prefix = "essentials.admin-api")
public class EssentialsAdminApiProperties {

    /**
     * Whether to expose the Essentials admin HTTP API. Enabled by default: adding this starter is the opt-in, and
     * every operation still has to pass the application's own {@code EssentialsSecurityProvider}.
     */
    private boolean enabled = true;

    /**
     * Where the API is mounted. Change this only to match a gateway prefix — the path major must keep tracking the
     * contract major.
     */
    private String basePath = AdminApiPaths.DEFAULT_BASE_PATH;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
