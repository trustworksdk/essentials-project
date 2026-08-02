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

package dk.trustworks.essentials.components.adminapi.rest;

/**
 * Path constants shared by the admin API controllers.
 * <p>
 * The contract's paths are relative to the mount point, so every controller maps
 * {@link #BASE_PATH_PLACEHOLDER} — resolving the configured base path at context startup — and then declares only
 * the contract-relative remainder.
 */
public final class AdminApiPaths {

    /** Configuration property that relocates the whole API, e.g. when mounting behind a gateway prefix. */
    public static final String BASE_PATH_PROPERTY = "essentials.admin-api.base-path";

    /** The default mount point; its major aligns with the contract major. */
    public static final String DEFAULT_BASE_PATH = "/api/essentials/admin/v1";

    /** Class-level {@code @RequestMapping} value for every admin API controller. */
    public static final String BASE_PATH_PLACEHOLDER = "${" + BASE_PATH_PROPERTY + ":" + DEFAULT_BASE_PATH + "}";

    /** Default {@code startIndex} for paginated operations, matching the contract. */
    public static final String DEFAULT_START_INDEX = "0";

    /** Default {@code pageSize} for paginated operations, matching the contract. */
    public static final String DEFAULT_PAGE_SIZE = "100";

    private AdminApiPaths() {
    }
}
