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

package dk.trustworks.essentials.components.foundation.ttl;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TTLJob {

    /**
     * Is TTL enabled
     */
    boolean enabled() default true;

    /**
     * Optionally, the key of a configuration property that controls whether TTL is enabled.
     * <p>
     * Example: "essentials.durable-queues.enable.queue.statistics.ttl"
     */
    String enabledProperty() default "";

    /**
     * Name of the table for which the TTL job should be scheduled.
     */
    String tableName();

    /**
     * The key of a configuration property that supplies the table name.
     * <p>
     * Example: "essentials.durable-queues.shared.queue.statistics.table.name"
     */
    String tableNameProperty() default "";

    /**
     * Delete statement template using placeholders for table name and number of days.
     * <pre>
     * Example: "DELETE FROM {:tableName} WHERE deletion_ts < (NOW() - INTERVAL '{:ttlDuration} day')"
     * </pre>
     */
    String deleteStatementTemplate();

    /**
     * The cron expression used to schedule the TTL job.
     * <p>
     * The default is "0 0 * * *" (every day at midnight).
     */
    String cronExpression() default "0 0 * * *";

    /**
     * Default TTL duration in days. This is used if no property is set.
     * <p>
     * Default is 1 day
     */
    long defaultTTLDuration() default 1;

    /**
     * The key of a configuration property that supplies the TTL duration in days.
     */
    String ttlDuration() default "";
}
