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

package dk.trustworks.essentials.components.adminapi.rest.dto;

import java.time.Duration;

/** Contract schema {@code ResurrectDeadLetterMessageRequest}: re-queue parameters for a dead-letter message. */
public record ResurrectDeadLetterMessageRequest(Duration deliveryDelay) {

    /** @return the requested delay, or {@link Duration#ZERO} when the caller omitted it */
    public Duration deliveryDelayOrImmediate() {
        return deliveryDelay != null ? deliveryDelay : Duration.ZERO;
    }
}
