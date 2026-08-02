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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional default admin UI.
 */
@ConfigurationProperties(prefix = "essentials.admin-ui")
public class EssentialsAdminUiProperties {

    /**
     * Whether to serve the admin UI. Enabled by default: adding this starter is the opt-in, and the UI
     * exposes nothing the admin API would not already have exposed to the same caller.
     */
    private boolean enabled = true;

    /** Where the UI is served from. The API keeps its own, separate base path. */
    private String basePath = "/essentials/admin";

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
