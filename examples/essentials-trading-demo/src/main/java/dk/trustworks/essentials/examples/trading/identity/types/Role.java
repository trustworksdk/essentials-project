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

package dk.trustworks.essentials.examples.trading.identity.types;

/**
 * What a user may do. Mapped one-to-one onto Spring Security authorities ({@code ROLE_CUSTOMER},
 * {@code ROLE_OPERATOR}).
 */
public enum Role {
    /**
     * Holds accounts and trades in them; sees only their own.
     */
    CUSTOMER,
    /**
     * Runs the brokerage: the admin pages, the operator endpoints, the Essentials admin console.
     */
    OPERATOR
}
