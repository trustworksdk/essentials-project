/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.scheduler;

import dk.trustworks.essentials.shared.network.Network;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The JobNameResolver class provides functionality to resolve job names in a hostname-specific manner.
 * It appends the current instance's hostname to the given job name if it's not yet included.
 */
public class JobNameResolver {

    public static final String UNDER_SCORE  = "_";

    /**
     * Resolves a job name by appending the current instance's hostname suffix if the suffix
     * is not already present. The hostname suffix is formed by concatenating an underscore (`_`)
     * and the hostname, as retrieved by {@link Network#hostName()}.
     *
     * @param name the original job name to resolve; must not be null
     * @return the resolved job name which includes the hostname suffix if it was not originally present
     * @throws IllegalArgumentException if {@code name} is {@code null}
     *
     * <p><b>Usage Example:</b></p>
     * <pre>
     * String resolvedName = JobNameResolver.resolve("jobA");
     * System.out.println(resolvedName);  // Output: jobA_hostname (where "hostname" is the current instance hostname)
     * </pre>
     */
    public static String resolve(String name) {
        requireNonNull(name, "name cannot be null");
        String instanceId = Network.hostName();
        String suffix = UNDER_SCORE + instanceId;
        return name.endsWith(suffix)
               ? name
               : name + suffix;
    }
}
