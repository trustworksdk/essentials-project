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
package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.shared.network.Network;
import org.jdbi.v3.core.Jdbi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The schema harness: which {@link SchemaApplier} {@code essentials.schema.mode} selects, and the
 * {@link EssentialsSchemaHarnessRunner} that applies every {@link EssentialsSchemaContributor} bean at startup.
 * <p>
 * The components themselves read the same property: in {@link SchemaMode#CREATE} each creates its own schema as it is
 * constructed, exactly as in earlier releases; in every other mode they leave it to the harness
 * ({@link SchemaMode#schemaOwnership()}).
 */
@AutoConfiguration(after = EssentialsComponentsConfiguration.class)
@EnableConfigurationProperties(EssentialsComponentsProperties.class)
public class EssentialsSchemaConfiguration {

    /**
     * @param jdbi       where the schema is created or validated
     * @param properties the {@code essentials.schema.*} settings
     * @return the applier the mode selects
     */
    @Bean
    @ConditionalOnMissingBean
    public SchemaApplier essentialsSchemaApplier(Jdbi jdbi, EssentialsComponentsProperties properties) {
        var schema = properties.getSchema();
        return switch (schema.getMode()) {
            case CREATE -> new PostgresqlCreateSchemaApplier(jdbi, schema.getHistoryTableName(), Network.hostName());
            case VALIDATE -> new PostgresqlValidateSchemaApplier(jdbi, schema.getHistoryTableName());
            case EMIT -> new PostgresqlEmitSchemaApplier(schema.getEmit().getScriptFile(), schema.getHistoryTableName());
            case EXTERNAL -> new ExternalSchemaApplier();
        };
    }

    /**
     * @param applier      what happens to the collected schema
     * @param contributors every schema contributor bean
     * @param context      an optional {@link SchemaContext} bean handed to the contributors
     * @param properties   the {@code essentials.schema.*} settings
     * @return the runner that applies the schema once every singleton exists
     */
    @Bean
    @ConditionalOnMissingBean
    public EssentialsSchemaHarnessRunner essentialsSchemaHarnessRunner(SchemaApplier applier,
                                                                       ObjectProvider<EssentialsSchemaContributor> contributors,
                                                                       ObjectProvider<SchemaContext> context,
                                                                       EssentialsComponentsProperties properties) {
        return new EssentialsSchemaHarnessRunner(applier,
                                                 contributors,
                                                 context,
                                                 properties.getSchema().getMode(),
                                                 properties.getSchema().getEmit().isExit());
    }
}
