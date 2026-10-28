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

package dk.trustworks.essentials.components.foundation.json;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One way in which a mapper's behaviour differs from the persisted JSON format Essentials has written since 0.50, as
 * found by {@link EssentialsObjectMappers#findWireFormatDeviations(tools.jackson.databind.ObjectMapper)}.
 *
 * @param aspect   what was probed, e.g. {@code "Duration format"}
 * @param expected what the persisted format requires
 * @param actual   what the mapper produced, or the exception it threw
 */
public record WireFormatDeviation(String aspect, String expected, String actual) {
    public WireFormatDeviation {
        requireNonNull(aspect, "No aspect provided");
        requireNonNull(expected, "No expected provided");
        requireNonNull(actual, "No actual provided");
    }

    @Override
    public String toString() {
        return aspect + ": expected " + expected + " but was " + actual;
    }
}
