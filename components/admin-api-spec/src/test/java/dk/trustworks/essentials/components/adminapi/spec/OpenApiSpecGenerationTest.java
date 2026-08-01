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

package dk.trustworks.essentials.components.adminapi.spec;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard: the committed {@code essentials-admin-api.yaml} MUST equal the spec regenerated from the SPI
 * interfaces. Any change to a mapped interface or its DTOs (new/removed method, changed field) makes this fail
 * with a regenerate hint — keeping the contract honest.
 * <p>
 * To regenerate after an intended change:
 * <pre>{@code
 * mvn -pl components/admin-api-spec test -Dtest=OpenApiSpecGenerationTest -Dopenapi.regenerate=true
 * }</pre>
 */
class OpenApiSpecGenerationTest {

    @Test
    void every_interface_method_is_mapped_to_exactly_one_operation() {
        OpenAPI openAPI = OpenApiSpecGenerator.buildOpenApi(); // throws if a method is unmapped or stale

        long mappedOperations = openAPI.getPaths().values().stream()
                                       .mapToLong(item -> item.readOperations().size())
                                       .sum();
        long declaredMethods = EssentialsAdminApiSpec.API_INTERFACES.stream()
                                                                    .mapToLong(api -> api.getDeclaredMethods().length)
                                                                    .sum();
        assertThat(mappedOperations)
                .as("Every SPI method must yield exactly one operation — a lower count means two descriptors "
                            + "collapsed onto the same verb and path")
                .isEqualTo(declaredMethods);
    }

    /**
     * Paths must be relative to {@code servers[0].url}. If they repeated the {@code /v1} prefix, every generated
     * client would prepend it a second time from its default base URI.
     */
    @Test
    void paths_are_relative_to_the_server_url_and_do_not_repeat_the_version_prefix() {
        OpenAPI openAPI = OpenApiSpecGenerator.buildOpenApi();

        assertThat(openAPI.getServers()).singleElement()
                                        .satisfies(server -> assertThat(server.getUrl()).isEqualTo(EssentialsAdminApiSpec.BASE_PATH));
        assertThat(openAPI.getPaths().keySet())
                .allSatisfy(path -> assertThat(path).startsWith("/")
                                                    .doesNotContain(EssentialsAdminApiSpec.BASE_PATH));
    }

    @Test
    void every_operation_declares_the_standard_error_responses() {
        OpenAPI openAPI = OpenApiSpecGenerator.buildOpenApi();

        openAPI.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((verb, operation) -> {
            assertThat(operation.getResponses().keySet())
                    .as("%s %s", verb, path)
                    .contains("200", "401", "403", "500");
            if (operation.getParameters() != null && !operation.getParameters().isEmpty()) {
                assertThat(operation.getResponses().keySet())
                        .as("%s %s takes parameters, so it must be able to reject them", verb, path)
                        .contains("400");
            }
        }));
    }

    /**
     * The contract prescribes no authentication scheme. Authorization is role-based, surfaced per operation via
     * {@code x-required-roles}, and enforced by the application's own {@code EssentialsSecurityProvider}; how a caller
     * is authenticated is the host's business, so declaring a scheme here would overstate what the contract knows.
     */
    @Test
    void the_contract_prescribes_no_authentication_scheme() {
        OpenAPI openAPI = OpenApiSpecGenerator.buildOpenApi();

        assertThat(openAPI.getComponents().getSecuritySchemes()).isNullOrEmpty();
        assertThat(openAPI.getSecurity()).isNullOrEmpty();
        assertThat(openAPI.getPaths().values())
                .flatMap(pathItem -> pathItem.readOperations())
                .allSatisfy(operation -> {
                    assertThat(operation.getSecurity()).isNullOrEmpty();
                    assertThat(operation.getExtensions())
                            .as("every operation must still declare the roles that satisfy it")
                            .containsKey("x-required-roles");
                });
    }

    @Test
    void primitive_dto_properties_are_required_while_nullable_ones_are_not() {
        Schema<?> queuedMessage = OpenApiSpecGenerator.buildOpenApi().getComponents().getSchemas().get("ApiQueuedMessage");

        assertThat(queuedMessage.getRequired())
                .contains("totalDeliveryAttempts", "redeliveryAttempts", "isDeadLetterMessage", "isBeingDelivered")
                .contains("id", "queueName")                      // declared in ALWAYS_PRESENT_PROPERTIES
                .doesNotContain("payload",                        // role-gated, hence nullable
                                "deliveryTimestamp",              // boxed reference type, not verified non-null
                                "lastDeliveryError");
    }

    /**
     * Guards against silently dropped metadata: a {@code nullable} annotation set directly on a {@code $ref} property
     * serializes away entirely, because OpenAPI 3.0 ignores every sibling of {@code $ref}.
     */
    @Test
    void declared_nullable_reasons_survive_serialization() {
        String yaml = OpenApiSpecGenerator.generateYaml();

        EssentialsAdminApiSpec.NULLABLE_PROPERTIES.values().stream()
                                                  .flatMap(properties -> properties.values().stream())
                                                  .forEach(reason -> assertThat(collapseWhitespace(yaml))
                                                          .as("nullable reason is missing from the generated YAML: %s", reason)
                                                          .contains(collapseWhitespace(reason)));
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }

    @Test
    void committed_spec_is_in_sync_with_the_spi_interfaces() throws IOException {
        String generated = OpenApiSpecGenerator.generateYaml();

        if (Boolean.getBoolean("openapi.regenerate")) {
            Files.createDirectories(OpenApiSpecGenerator.SPEC_FILE.getParent());
            Files.writeString(OpenApiSpecGenerator.SPEC_FILE, generated, StandardCharsets.UTF_8);
            System.out.println("Regenerated " + OpenApiSpecGenerator.SPEC_FILE.toAbsolutePath());
            return;
        }

        assertThat(Files.exists(OpenApiSpecGenerator.SPEC_FILE))
                .as("Committed spec %s is missing — regenerate with -Dopenapi.regenerate=true",
                    OpenApiSpecGenerator.SPEC_FILE)
                .isTrue();

        String committed = Files.readString(OpenApiSpecGenerator.SPEC_FILE, StandardCharsets.UTF_8);
        assertThat(normalize(generated))
                .as("Committed admin API spec is out of date. Regenerate with:%n"
                            + "  mvn -pl components/admin-api-spec test -Dtest=OpenApiSpecGenerationTest -Dopenapi.regenerate=true")
                .isEqualTo(normalize(committed));
    }

    private static String normalize(String yaml) {
        return yaml.replace("\r\n", "\n").stripTrailing();
    }
}
