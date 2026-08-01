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

package dk.trustworks.essentials.components.adminapi.rest;

import dk.trustworks.essentials.components.adminapi.rest.dto.*;
import dk.trustworks.essentials.components.foundation.fencedlock.LockName;
import dk.trustworks.essentials.components.foundation.fencedlock.api.ApiDBFencedLock;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.api.ApiQueuedMessage;
import org.junit.jupiter.api.*;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serialization conformance: the JSON this module emits must match the schemas the contract declares.
 * <p>
 * Two independent risks are covered. First, the Essentials semantic value types must render as JSON primitives — a
 * {@code QueueName} serializing as {@code {"value":"orders"}} would satisfy no generated client. Second, the wrapper
 * records in {@code rest.dto} duplicate shapes the contract declares in {@code admin-api-spec}; comparing their
 * serialized field names against the contract catches the two definitions drifting apart.
 */
class AdminApiSerializationTest {

    private static final String CONTRACT_RESOURCE = "/openapi/essentials-admin-api.yaml";

    private static Map<String, Map<String, Object>> contractSchemas;

    private final JsonMapper mapper = JsonMapper.builder()
                                                .addModule(new AdminApiJacksonModule())
                                                .build();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadContractSchemas() throws Exception {
        try (InputStream contractYaml = AdminApiSerializationTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(contractYaml).isNotNull();
            var contract   = (Map<String, Object>) new Yaml().load(contractYaml);
            var components = (Map<String, Object>) contract.get("components");
            contractSchemas = (Map<String, Map<String, Object>>) components.get("schemas");
        }
    }

    @Test
    void semantic_value_types_serialize_as_json_primitives() {
        var message = new ApiQueuedMessage(QueueEntryId.of("entry-1"),
                                          QueueName.of("orders"),
                                          "the-payload",
                                          OffsetDateTime.parse("2026-07-31T10:15:30+02:00"),
                                          null,
                                          null,
                                          null,
                                          3,
                                          1,
                                          true,
                                          false);

        var json = mapper.writeValueAsString(message);

        assertThat(json).contains("\"id\":\"entry-1\"")
                        .contains("\"queueName\":\"orders\"")
                        .contains("\"totalDeliveryAttempts\":3")
                        .contains("\"isDeadLetterMessage\":true");
    }

    @Test
    void a_null_value_type_does_not_break_serialization() {
        // An unheld lock has no token and no holder — the contract marks those properties optional.
        var lock = new ApiDBFencedLock(LockName.of("my-lock"), null, null, null, null);

        assertThat(mapper.writeValueAsString(lock)).contains("\"lockName\":\"my-lock\"");
    }

    @Test
    void wrapper_dtos_serialize_exactly_the_properties_the_contract_declares() {
        assertMatchesContractSchema("CountResult", new CountResult(42L));
        assertMatchesContractSchema("ReleaseResult", new ReleaseResult(true));
        assertMatchesContractSchema("DeleteResult", new DeleteResult(true));
        assertMatchesContractSchema("PurgeResult", new PurgeResult(7));
        assertMatchesContractSchema("QueueNameResult", new QueueNameResult("orders"));
        assertMatchesContractSchema("GlobalEventOrderResult", new GlobalEventOrderResult(99L));
        assertMatchesContractSchema("Error", new ApiError(403, "Forbidden", "nope"));
    }

    @Test
    void the_request_body_dto_accepts_the_shape_the_contract_declares() {
        var request = mapper.readValue("""
                                       {"deliveryDelay":"PT30S"}""", ResurrectDeadLetterMessageRequest.class);

        assertThat(request.deliveryDelayOrImmediate()).hasSeconds(30);
        assertThat(declaredProperties("ResurrectDeadLetterMessageRequest")).containsExactly("deliveryDelay");
    }

    @Test
    void an_omitted_delivery_delay_means_immediate_redelivery() {
        var request = mapper.readValue("{}", ResurrectDeadLetterMessageRequest.class);

        assertThat(request.deliveryDelayOrImmediate()).isZero();
    }

    @SuppressWarnings("unchecked")
    private void assertMatchesContractSchema(String schemaName, Object dto) {
        var serialized = (Map<String, Object>) mapper.readValue(mapper.writeValueAsString(dto), Map.class);

        assertThat(serialized.keySet())
                .as("%s must serialize exactly the properties the contract's %s schema declares",
                    dto.getClass().getSimpleName(), schemaName)
                .containsExactlyInAnyOrderElementsOf(declaredProperties(schemaName));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> declaredProperties(String schemaName) {
        var schema = contractSchemas.get(schemaName);
        assertThat(schema).as("The contract declares no %s schema", schemaName).isNotNull();
        return ((Map<String, Object>) schema.get("properties")).keySet();
    }
}
