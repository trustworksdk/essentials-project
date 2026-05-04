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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

/**
 * Defines the contract for validating the configuration of aggregate lifecycles.
 * Implementations of this interface are responsible for ensuring that the
 * lifecycle configurations of aggregates meet the required specifications and
 * adhere to the defined policies.
 */
public interface AggregateLifecycleConfigurationValidator {

    /**
     * Validates the configurations related to aggregate lifecycles.
     * This method ensures that the configurations align with the
     * required specifications and adhere to predefined policies.
     * Implementations should perform checks and necessary validations
     * to ensure consistency and correctness of the aggregate lifecycle setup.
     */
    void validate();
}
