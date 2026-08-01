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
 * Thrown when no authenticated caller can be resolved. Mapped to {@code 401} by {@link AdminApiExceptionHandler}.
 * <p>
 * Distinct from {@link dk.trustworks.essentials.shared.security.EssentialsSecurityException}, which means the caller
 * <em>is</em> known but lacks a required role and maps to {@code 403}.
 */
public class AdminApiUnauthenticatedException extends RuntimeException {

    public AdminApiUnauthenticatedException(String message) {
        super(message);
    }
}
