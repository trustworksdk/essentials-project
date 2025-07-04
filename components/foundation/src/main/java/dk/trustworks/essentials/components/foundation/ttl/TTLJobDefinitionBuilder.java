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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A builder class for constructing {@link TTLJobDefinition} instances.
 * This class allows the configuration of the action and schedule details
 * required for the definition of a Time-To-Live (TTL) job.
 * <p>
 * A TTL job is used to manage the lifecycle of data by executing specific
 * actions based on expiration rules and schedules.
 */
public class TTLJobDefinitionBuilder {

    private TTLJobAction          action;
    private ScheduleConfiguration scheduleConfiguration;

    /**
     * Sets the {@link TTLJobAction} for the {@link TTLJobDefinitionBuilder}.
     * This method allows specifying the action to be performed by the TTL job.
     *
     * @param action the {@link TTLJobAction} to associate with the {@link TTLJobDefinition}.
     *               This represents the specific behavior or task executed as part of the TTL job.
     *               Must not be {@code null} - see {@link TTLJobAction#create(String, String, String, String)}/{@link DefaultTTLJobAction} and their <b>associated security warnings!</b>
     * @return the updated {@link TTLJobDefinitionBuilder} instance with the specified {@link TTLJobAction}.
     */
    public TTLJobDefinitionBuilder withAction(TTLJobAction action) {
        this.action = action;
        return this;
    }

    /**
     * Configures the {@link ScheduleConfiguration} for the {@link TTLJobDefinitionBuilder}.
     * This method allows specifying how the TTL job should be scheduled by
     * setting a schedule configuration.
     *
     * @param scheduleConfiguration the {@link ScheduleConfiguration} defining the scheduling
     *                              behavior for the TTL job. This value determines
     *                              when and how often the job is executed. Examples of
     *                              schedule configurations might include cron-based or
     *                              fixed-interval scheduling. Must not be {@code null}.
     * @return the updated {@link TTLJobDefinitionBuilder} instance with the configured
     * {@link ScheduleConfiguration}.
     */
    public TTLJobDefinitionBuilder withSchedule(ScheduleConfiguration scheduleConfiguration) {
        this.scheduleConfiguration = scheduleConfiguration;
        return this;
    }

    /**
     * Builds and returns a {@link TTLJobDefinition} instance using the configured
     * {@link TTLJobAction} and {@link ScheduleConfiguration} properties of the {@link TTLJobDefinitionBuilder}.
     * <p>
     * This method ensures that both {@link TTLJobAction} and {@link ScheduleConfiguration}
     * are not null before creating the {@link TTLJobDefinition}.
     * If either property is null, an {@link IllegalArgumentException} will be thrown.
     * </p>
     *
     * @return a new {@link TTLJobDefinition} instance composed of the {@link TTLJobAction}
     * and {@link ScheduleConfiguration} provided via {@link #withAction(TTLJobAction)}
     * and {@link #withSchedule(ScheduleConfiguration)}.
     * <p>
     * <b>Usage Example:</b>
     * <pre>{@code
     * TTLJobAction action = TTLJobAction.create("myTTLJob", "myTableName", "deletion_ts < current_timestamp", "DELETE FROM myTableName WHERE deletion_ts < current_timestamp");
     * ScheduleConfiguration scheduleConfiguration = new CronScheduleConfiguration("0 0 * * *");
     * TTLJobDefinition jobDefinition = new TTLJobDefinitionBuilder()
     *         .withAction(action)
     *         .withSchedule(scheduleConfiguration)
     *         .build();
     * }</pre>
     */
    public TTLJobDefinition build() {
        requireNonNull(action, "Action required");
        requireNonNull(scheduleConfiguration, "Schedule configuration required");
        return new TTLJobDefinition(action, scheduleConfiguration);
    }
}
