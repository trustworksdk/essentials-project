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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.core.util.Yaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard: the committed {@code essentials-admin-api.yaml} MUST describe the same contract as the spec
 * regenerated from the SPI interfaces. Any change to a mapped interface or its DTOs (new/removed method, changed
 * field) makes this fail with a regenerate hint — keeping the contract honest.
 * <p>
 * The comparison is <em>semantic</em>, not textual: both documents are parsed and their mapping keys sorted before
 * being compared, so a pure key-order flip is not drift. Serialized key order depends on the order Jackson
 * discovers accessors on the swagger model classes, which is not stable across machines — the same sources have
 * been observed to emit {@code default} before {@code enum} on one build host and after it on another. Sequence
 * order (parameters, tags, {@code enum} values, …) is still compared strictly, since that order is ours and is
 * meaningful to the generated clients.
 * <p>
 * To regenerate after an intended change:
 * <pre>{@code
 * mvn -pl components/admin-api-spec test -Dtest=OpenApiSpecGenerationTest -Dopenapi.regenerate=true
 * }</pre>
 * Regeneration rewrites the file only when the contract actually differs, so it never introduces a key-order-only
 * diff on the machine it happens to run on.
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
        String generated   = OpenApiSpecGenerator.generateYaml();
        boolean specExists = Files.exists(OpenApiSpecGenerator.SPEC_FILE);
        String committed   = specExists ? Files.readString(OpenApiSpecGenerator.SPEC_FILE, StandardCharsets.UTF_8) : null;
        List<String> differences = specExists ? differences(generated, committed) : List.of();

        if (Boolean.getBoolean("openapi.regenerate")) {
            if (specExists && differences.isEmpty()) {
                System.out.println("Committed spec already up to date (key ordering ignored): "
                                           + OpenApiSpecGenerator.SPEC_FILE.toAbsolutePath());
                return;
            }
            Files.createDirectories(OpenApiSpecGenerator.SPEC_FILE.getParent());
            Files.writeString(OpenApiSpecGenerator.SPEC_FILE, generated, StandardCharsets.UTF_8);
            System.out.println("Regenerated " + OpenApiSpecGenerator.SPEC_FILE.toAbsolutePath());
            return;
        }

        assertThat(specExists)
                .as("Committed spec %s is missing — regenerate with -Dopenapi.regenerate=true",
                    OpenApiSpecGenerator.SPEC_FILE)
                .isTrue();

        assertThat(differences)
                .as("Committed admin API spec is out of date — %s differs from what the SPI interfaces generate. "
                            + "Regenerate with:%n"
                            + "  mvn -pl components/admin-api-spec -am test -Dtest=OpenApiSpecGenerationTest "
                            + "-Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.regenerate=true",
                    OpenApiSpecGenerator.SPEC_FILE)
                .isEmpty();
    }

    /**
     * Lists where the two contracts disagree, as {@code <json-pointer>: generated … | committed …} lines. Empty means
     * the documents are equivalent. Capped, because a genuinely regenerated contract can differ in hundreds of places
     * and the first handful already say what changed.
     */
    private static List<String> differences(String generated, String committed) {
        var differences = new ArrayList<String>();
        collectDifferences("",
                           canonicalize(parse(generated, "generated")),
                           canonicalize(parse(committed, "committed")),
                           differences);
        return differences;
    }

    private static final int MAX_REPORTED_DIFFERENCES = 25;

    private static void collectDifferences(String pointer, JsonNode generated, JsonNode committed, List<String> differences) {
        if (Objects.equals(generated, committed) || differences.size() >= MAX_REPORTED_DIFFERENCES) {
            return;
        }
        if (generated != null && committed != null && generated.isObject() && committed.isObject()) {
            var names = new TreeSet<String>();
            generated.fieldNames().forEachRemaining(names::add);
            committed.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> collectDifferences(pointer + "/" + name, generated.get(name), committed.get(name), differences));
            return;
        }
        if (generated != null && committed != null && generated.isArray() && committed.isArray() && generated.size() == committed.size()) {
            for (int i = 0; i < generated.size(); i++) {
                collectDifferences(pointer + "/" + i, generated.get(i), committed.get(i), differences);
            }
            return;
        }
        differences.add((pointer.isEmpty() ? "/" : pointer) + ": generated " + describe(generated) + " | committed " + describe(committed));
    }

    private static String describe(JsonNode node) {
        if (node == null) {
            return "(absent)";
        }
        String text = node.toString();
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }

    /** Recursively sorts mapping keys, leaving sequences alone, so only real contract differences survive. */
    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            var sorted = JsonNodeFactory.instance.objectNode();
            names.forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode elements = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> elements.add(canonicalize(element)));
            return elements;
        }
        return node;
    }

    private static JsonNode parse(String yaml, String origin) {
        try {
            return Yaml.mapper().readTree(yaml);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The " + origin + " admin API spec is not parseable YAML", e);
        }
    }
}
