/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.PgOutputProperties;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.jdbi.v3.core.Handle;

import java.util.Map;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * {@link LogicalDecodingPlugin} adapter for PostgreSQL built-in {@code pgoutput}.
 */
public final class PgOutputLogicalDecodingPlugin implements LogicalDecodingPlugin {
    public static final String PLUGIN_NAME = "pgoutput";

    private final PgOutputProperties properties;

    public PgOutputLogicalDecodingPlugin(PgOutputProperties properties) {
        this.properties = requireNonNull(properties, "properties cannot be null");
        requireNonBlank(properties.getPublicationName(), "publicationName cannot be blank");
        requireTrue(properties.getProtoVersion() > 0, "protoVersion must be > 0");
    }

    @Override
    public String pluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public Optional<String> unusableReason(Handle handle) {
        if (!PostgresqlUtil.isOutputPluginUsable(handle, pluginName())) {
            return Optional.of("pgoutput plugin not usable");
        }
        if (!PostgresqlUtil.isPublicationAvailable(handle, properties.getPublicationName())) {
            return Optional.of("pgoutput publication '" + properties.getPublicationName() + "' does not exist");
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> slotOptions() {
        return Map.of(
                "proto_version", properties.getProtoVersion(),
                "publication_names", properties.getPublicationName(),
                "binary", properties.isBinary(),
                "messages", properties.isMessages()
        );
    }

    @Override
    public boolean supportsCurrentPayloadPipeline() {
        return true;
    }

    public int protocolVersion() {
        return properties.getProtoVersion();
    }
}
