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

package dk.trustworks.essentials.components.eventlog.migration.cli;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.migration.*;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * What the CLI ({@link MigrationCli}) is told: the connection, the aggregate types with their old tables, the copy
 * settings and the optional resume-point import. Read from YAML by
 * {@link MigrationConfigYaml}; this record itself does not depend on a YAML library, so it can be built in code as well.
 *
 * @param connection      the database holding both the old tables and the event log's (the same database)
 * @param schemaOwnership the event log's schema ownership: {@link SchemaOwnership#COMPONENT} lets {@code prepare} create
 *                        the event log's schema; {@link SchemaOwnership#HARNESS} expects it to exist
 * @param tables          per aggregate type, the old table and its column names
 * @param settings        the copy settings
 * @param imports         the resume points to import at the cut-over; {@link Imports#none()} for none
 */
public record MigrationConfig(ConnectionSettings connection, SchemaOwnership schemaOwnership, List<SourceTable> tables, CopySettings settings, Imports imports) {
    public MigrationConfig {
        requireNonNull(connection, "No connection provided");
        requireNonNull(schemaOwnership, "No schemaOwnership provided");
        requireNonEmpty(requireNonNull(tables, "No tables provided"), "No tables provided");
        requireNonNull(settings, "No settings provided");
        requireNonNull(imports, "No imports provided");
        tables = List.copyOf(tables);
    }

    /**
     * @param url      the pgjdbc URL, e.g. {@code jdbc:postgresql://localhost:5432/app}
     * @param user     the user; may be null (the driver's default)
     * @param password the password; may be null
     */
    public record ConnectionSettings(String url, String user, String password) {
        public ConnectionSettings {
            requireNonBlank(url, "No connection url provided");
            requireTrue(url.startsWith("jdbc:postgresql:"), "The connection url must be a 'jdbc:postgresql:' url");
        }

        @Override
        public String toString() {
            return "ConnectionSettings[url=" + url + ", user=" + user + ", password=" + (password == null ? "<none>" : "***") + "]";
        }
    }

    /**
     * The resume-point import; its table names default to the old engine's
     */
    public record Imports(String durableSubscriptionsTable, String transientGapsTable, String permanentGapsTable, List<Subscription> subscriptions) {
        public Imports {
            requireNonBlank(durableSubscriptionsTable, "No durableSubscriptionsTable provided");
            requireNonBlank(transientGapsTable, "No transientGapsTable provided");
            requireNonBlank(permanentGapsTable, "No permanentGapsTable provided");
            subscriptions = List.copyOf(requireNonNull(subscriptions, "No subscriptions provided"));
        }

        /** @return no import, with the default table names */
        public static Imports none() {
            return new Imports(ResumePointImport.DEFAULT_DURABLE_SUBSCRIPTIONS_TABLE, ResumePointImport.DEFAULT_TRANSIENT_GAPS_TABLE,
                               ResumePointImport.DEFAULT_PERMANENT_GAPS_TABLE, List.of());
        }
    }

    /**
     * One old subscriber's mapping to an event log subscription. The definition must equal the one
     * the application will subscribe with (same subscriber, aggregate type, ownership, consistency, event types and
     * tenant), or the engine will not recognise the imported progress row as its own.
     *
     * @param oldSubscriberId the old engine's subscriber id ({@code durable_subscriptions.subscriber_id})
     * @param subscriber      the new subscription's subscriber name
     * @param aggregateType   the aggregate type, the old row's and the new subscription's
     * @param model           the new subscription's progress model
     * @param consistency     the new subscription's consistency mode
     * @param instanceNames   empty for an exclusive subscription; else every instance that will run the per-instance one
     * @param eventTypes      the new subscription's event-type filter; empty for all
     * @param tenant          the new subscription's tenant; may be null
     * @param trustOldResumePoint import the old resume point unchanged, on the operator's statement that the old subscriber
     *                        already handled its gap fills ({@link ImportedSubscription#trustOldResumePoint()})
     */
    public record Subscription(String oldSubscriberId, String subscriber, String aggregateType, ProgressModel model, ConsistencyMode consistency,
                               List<String> instanceNames, Set<String> eventTypes, String tenant, boolean trustOldResumePoint) {
        public Subscription {
            requireNonBlank(oldSubscriberId, "No oldSubscriber provided");
            requireNonBlank(subscriber, "No subscriber provided");
            requireNonBlank(aggregateType, "No aggregateType provided");
            requireNonNull(model, "No model provided");
            requireNonNull(consistency, "No consistency provided");
            instanceNames = List.copyOf(requireNonNull(instanceNames, "No instanceNames provided"));
            eventTypes = Set.copyOf(requireNonNull(eventTypes, "No eventTypes provided"));
        }

        /** @return the definition for {@code instanceName} ({@code ""} for an exclusive subscription) */
        public SubscriptionDefinition definition(String instanceName) {
            var builder = SubscriptionDefinition.builder(subscriber, aggregateType).consistency(consistency).tenant(tenant);
            if (!eventTypes.isEmpty()) {
                builder.eventTypes(EventTypeFilter.of(eventTypes.toArray(String[]::new)));
            }
            return (instanceNames.isEmpty() ? builder.exclusive() : builder.perInstance(instanceName)).build();
        }

        /** @return the mapping the {@link ResumePointImport} takes */
        public ImportedSubscription imported() {
            var imported = instanceNames.isEmpty() ? ImportedSubscription.exclusive(oldSubscriberId, definition(""), model)
                                           : ImportedSubscription.perInstance(oldSubscriberId, definition(instanceNames.getFirst()), model, instanceNames);
            return trustOldResumePoint ? imported.trustingOldResumePoint() : imported;
        }
    }

    /**
     * @return a pgjdbc data source for {@link #connection()}; opens no connection
     */
    public DataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(connection.url());
        if (connection.user() != null) {
            dataSource.setUser(connection.user());
        }
        if (connection.password() != null) {
            dataSource.setPassword(connection.password());
        }
        return dataSource;
    }

    /**
     * @return an event log over {@code dataSource} with {@link #schemaOwnership()}; not started
     */
    public EventLog eventLog(DataSource dataSource) {
        return EventLog.builder().setDataSource(requireNonNull(dataSource, "No dataSource provided")).setSchemaOwnership(schemaOwnership).build();
    }

    /**
     * @param eventLog a started event log ({@link EventLog#admin()} requires it)
     * @return the cut-over's import step; empty when no subscription is mapped
     */
    public Optional<ResumePointImport> resumePointImport(EventLog eventLog) {
        return imports.subscriptions().isEmpty() ? Optional.empty() : Optional.of(importStep(eventLog));
    }

    /**
     * @param eventLog a started event log
     * @return the import step, also when no subscription is mapped (its preview then lists only the unmapped subscribers)
     */
    public ResumePointImport importStep(EventLog eventLog) {
        var builder = ResumePointImport.builder(eventLog.admin())
                                       .setDurableSubscriptionsTable(imports.durableSubscriptionsTable())
                                       .setTransientGapsTable(imports.transientGapsTable())
                                       .setPermanentGapsTable(imports.permanentGapsTable());
        imports.subscriptions().forEach(s -> builder.addSubscription(s.imported()));
        return builder.build();
    }

    /**
     * @return the migration plan over {@code dataSource} and {@code eventLog}, running {@code cutoverSteps} in the cut-over
     */
    public MigrationPlan plan(DataSource dataSource, EventLog eventLog, List<CutoverStep> cutoverSteps) {
        return new MigrationPlan(dataSource, eventLog, tables, settings, cutoverSteps);
    }
}
