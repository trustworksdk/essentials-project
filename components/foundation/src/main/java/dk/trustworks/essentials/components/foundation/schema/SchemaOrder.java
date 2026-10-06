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
package dk.trustworks.essentials.components.foundation.schema;

/**
 * The coarse positions {@link EssentialsSchemaContributor#order()} chooses from, kept in one place so the
 * ordering of the framework's schema is reviewable at a glance. Lower runs first; contributors with the same order
 * run in {@link EssentialsSchemaContributor#moduleId()} order.
 */
public final class SchemaOrder {
    /** Reserved for the schema-history ledger, which the applier creates before any contribution runs */
    public static final int ORDER_LEDGER         = 0;
    /** Scheduled jobs, fenced locks - what the rest of the framework uses at startup */
    public static final int ORDER_INFRASTRUCTURE = 100;
    /** Subscriptions, gaps, the CDC inbox */
    public static final int ORDER_EVENT_STORE    = 200;
    /** Durable queues */
    public static final int ORDER_QUEUES         = 300;
    /** Snapshots, snapshot jobs, archive, closing books */
    public static final int ORDER_AGGREGATES     = 400;
    /** Reserved for consumers' own contributors */
    public static final int ORDER_APPLICATION    = 1000;

    private SchemaOrder() {
    }
}
