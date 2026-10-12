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

package dk.trustworks.essentials.components.foundation.messaging;

import dk.trustworks.essentials.components.foundation.startup.EssentialsRunReport;
import org.slf4j.*;

import java.time.Duration;
import java.util.*;

/**
 * The checks the {@link RedeliveryPolicy} builders run in {@code build()} (QL-3).
 * <p>
 * Each builder leaves a setting it was never given at its Java default, and several of those defaults are silently
 * wrong rather than invalid: an unset {@code maximumNumberOfRedeliveries} is {@code 0} (the first failure dead-letters the
 * message), an unset multiplier is {@code 0.0} (the follow-up delay never grows). An unset delay already fails, but as a
 * {@code requireNonNull} on a field name, typically during Spring bean creation, where it reads like a wiring problem.
 * <p>
 * In 0.60.x every problem found is logged in one WARN that names the setter to call, and {@code build()} otherwise
 * behaves as before. From 0.61 {@code build()} throws {@link IllegalArgumentException} instead.
 */
final class RedeliveryPolicyBuilderChecks {
    private static final Logger log = LoggerFactory.getLogger(RedeliveryPolicy.class);

    private final String       builderName;
    private final List<String> problems = new ArrayList<>();

    RedeliveryPolicyBuilderChecks(Class<?> builderType) {
        this.builderName = builderType.getSimpleName();
    }

    RedeliveryPolicyBuilderChecks delay(Duration delay, String setter) {
        if (delay == null) {
            problems.add(setter + "(...) was not called - a RedeliveryPolicy needs this delay, so build() will fail");
        }
        return this;
    }

    RedeliveryPolicyBuilderChecks multiplier(double multiplier) {
        // `!(x >= 1.0)` rather than `x < 1.0` so NaN is reported too
        if (!(multiplier >= 1.0d)) {
            problems.add("setFollowupRedeliveryDelayMultiplier(...) was not called or is below 1.0 (it is " + multiplier
                         + ") - it is treated as 1.0, so the follow-up delay never grows; pass 1.0 for a constant follow-up delay");
        }
        return this;
    }

    RedeliveryPolicyBuilderChecks maximumNumberOfRedeliveries(boolean set) {
        if (!set) {
            problems.add("setMaximumNumberOfRedeliveries(...) was not called - it defaults to 0 redeliveries, so a message is "
                         + "dead-lettered on its first failure; call it, with 0 if that is what you want");
        }
        return this;
    }

    /**
     * @return what {@link #warnIfIncomplete()} would report - for tests
     */
    List<String> problems() {
        return List.copyOf(problems);
    }

    void warnIfIncomplete() {
        if (!problems.isEmpty()) {
            log.warn("Incomplete RedeliveryPolicy built with {}: {}. From 0.61 build() will throw an IllegalArgumentException instead.",
                     builderName, String.join("; ", problems));
            EssentialsRunReport.addToCurrent(EssentialsRunReport.Finding.warn("RedeliveryPolicy built with " + builderName,
                                                                                      "incomplete: " + String.join("; ", problems),
                                                                                      "Call the setters named on the builder. From 0.61 build() throws an IllegalArgumentException instead"));
        }
    }
}
