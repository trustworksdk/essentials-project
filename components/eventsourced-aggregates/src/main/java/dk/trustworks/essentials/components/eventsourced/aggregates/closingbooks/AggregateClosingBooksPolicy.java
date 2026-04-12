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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import java.lang.annotation.*;

/**
 * Declares default closing-books behavior for an aggregate implementation.
 * <p>
 * The annotation is intended as a code-local default that can be overridden by external configuration,
 * for example Spring Boot properties keyed by {@code AggregateType}.
 * <p>
 * When {@link #defaultPolicy()} is {@link ClosingBooksDefaultPolicyType#TIME_BOUNDARY} or
 * {@link ClosingBooksDefaultPolicyType#EVENT_COUNT_OR_TIME_BOUNDARY}, the aggregate must expose
 * a persisted current period id through a provider or {@link HasClosingBooksPeriodId}. The stored
 * period-id format must match the configured {@link #timeBoundary()}:
 * {@link ClosingBooksTimeBoundary#END_OF_DAY} and {@link ClosingBooksTimeBoundary#EVERY_N_DAYS}
 * use {@code yyyy-MM-dd}, {@link ClosingBooksTimeBoundary#END_OF_WEEK} uses {@code yyyy-Www},
 * {@link ClosingBooksTimeBoundary#END_OF_MONTH} uses {@code yyyy-MM}, and
 * {@link ClosingBooksTimeBoundary#END_OF_YEAR} uses {@code yyyy}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateClosingBooksPolicy {
    boolean enabled() default true;

    ClosingBooksTriggerMode triggerMode() default ClosingBooksTriggerMode.ON_ACCESS;

    ClosingBooksDefaultPolicyType defaultPolicy() default ClosingBooksDefaultPolicyType.UNSPECIFIED;

    long eventThreshold() default -1;

    ClosingBooksTimeBoundary timeBoundary() default ClosingBooksTimeBoundary.NONE;

    String zoneId() default "UTC";

    int intervalDays() default -1;

    String aggregateType() default "";
}
