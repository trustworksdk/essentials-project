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

/**
 * Annotation to define the configuration and scheduling details of a Time-To-Live (TTL) job.
 * TTL jobs are used to manage the lifecycle of data by enforcing expiration policies
 * and executing related actions at scheduled intervals.
 * <pre>
 *     @TTLJob(name = "durable_queues_statistics_ttl",
 *         enabledProperty = "essentials.durable-queues.enable-queue-statistics-ttl",
 *         tableNameProperty = "essentials.durable-queues.shared-queue-statistics-table-name",
 *         timestampColumn = "deletion_ts",
 *         cronExpression = "0 0 * * *", // every day at midnight
 *         ttlDurationProperty = "essentials.durable-queues.queue-statistics-ttl-duration"
 * )
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TTLJob {

    /**
     * Unique name for this TTL job; auto-derived if blank.
     */
    String name() default "";

    /**
     * Name of the table for which the TTL job should run.
     */
    String tableName() default "";

    /**
     * Environment or configuration file key to override the table name.
     */
    String tableNameProperty() default "";

    /**
     * Name of the timestamp column used to determine TTL expiration.
     */
    String timestampColumn();

    /**
     * Comparison operator for the timestamp predicate.
     */
    ComparisonOperator operator() default ComparisonOperator.LESS_THAN;

    /**
     * Cron expression used to schedule the TTL job.
     * Default: every day at midnight.
     */
    String cronExpression() default "0 0 * * *";

    /**
     * Default TTL duration, in days, if no override property is set.
     */
    long defaultTtlDays() default 1;

    /**
     * Environment or configuration file key to override TTL duration (days).
     */
    String ttlDurationProperty() default "";

    /**
     *  Environment or configuration file keyxx to enable or disable this TTL job.
     */
    String enabledProperty() default "";

    /**
     * Whether this TTL job is enabled by default.
     */
    boolean enabled() default true;

}
