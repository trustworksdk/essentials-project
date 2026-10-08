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

package dk.trustworks.essentials.components.foundation.boot;

/**
 * What the Spring Boot starters do with a persistence {@code JSONSerializer} bean whose mapper does not reproduce the
 * persisted JSON format. Property: {@code essentials.json.wire-format-check}.
 *
 * @see WireFormatCheckBeanPostProcessor
 */
public enum WireFormatCheckMode {
    /** Log one ERROR banner per deviating bean and start anyway. The default. */
    WARN,
    /** Fail the bean's creation, and with it the application context. */
    FAIL,
    /** Do not check. */
    OFF
}
