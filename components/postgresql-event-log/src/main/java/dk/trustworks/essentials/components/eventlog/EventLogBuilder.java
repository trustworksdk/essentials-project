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
package dk.trustworks.essentials.components.eventlog;

import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;

import javax.sql.DataSource;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Builds an {@link EventLog}.
 * <ul>
 *     <li>{@link SchemaOwnership#COMPONENT} (default): the engine creates its fixed schema in {@link EventLog#start()}
 *     and each aggregate type's tables as it registers, under the bootstrap lock.</li>
 *     <li>{@link SchemaOwnership#HARNESS}: the engine executes no DDL; pass the {@link EventLogSchemaContributor}
 *     registered with the harness, through which aggregate types' tables are created.</li>
 * </ul>
 */
public final class EventLogBuilder {
    private DataSource                dataSource;
    private SchemaOwnership           schemaOwnership = SchemaOwnership.COMPONENT;
    private EventLogSchemaContributor schemaContributor;
    private EventLogSettings          settings        = EventLogSettings.defaults();
    private EventLogObserver          observer        = EventLogObserver.NONE;

    EventLogBuilder() {
    }

    /**
     * @param dataSource the engine's own database access: schema, event-type registration, start-up checks and
     *                   connection-less reads. Appends use the caller's connection
     */
    public EventLogBuilder setDataSource(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        return this;
    }

    /**
     * @param schemaOwnership who applies the schema; in Spring pass {@code properties.getSchema().getMode().schemaOwnership()}
     */
    public EventLogBuilder setSchemaOwnership(SchemaOwnership schemaOwnership) {
        this.schemaOwnership = requireNonNull(schemaOwnership, "No schemaOwnership provided");
        return this;
    }

    /**
     * @param schemaContributor the contributor registered with the schema harness; required with
     *                          {@link SchemaOwnership#HARNESS}
     */
    public EventLogBuilder setSchemaContributor(EventLogSchemaContributor schemaContributor) {
        this.schemaContributor = requireNonNull(schemaContributor, "No schemaContributor provided");
        return this;
    }

    /**
     * @param settings subscription settings; {@link EventLogSettings#defaults()} if not set
     */
    public EventLogBuilder setSettings(EventLogSettings settings) {
        this.settings = requireNonNull(settings, "No settings provided");
        return this;
    }

    /**
     * @param observer metrics and diagnostics callbacks; {@link EventLogObserver#NONE} if not set
     */
    public EventLogBuilder setObserver(EventLogObserver observer) {
        this.observer = requireNonNull(observer, "No observer provided");
        return this;
    }

    public EventLog build() {
        requireNonNull(dataSource, "No dataSource provided");
        var ddl = switch (schemaOwnership) {
            case COMPONENT -> EventLogSchema.lockedAggregateTypeDdl(dataSource);
            case HARNESS -> requireNonNull(schemaContributor, "SchemaOwnership.HARNESS requires the EventLogSchemaContributor registered with the harness").aggregateTypeDdl();
        };
        return new EventLog(dataSource, schemaOwnership, ddl, settings, observer);
    }
}
