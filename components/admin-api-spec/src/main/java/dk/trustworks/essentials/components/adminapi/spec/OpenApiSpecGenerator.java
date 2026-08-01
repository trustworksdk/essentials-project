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

import io.swagger.v3.core.converter.*;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

import java.io.IOException;
// Explicit, not java.lang.reflect.*: an on-demand import would make Parameter ambiguous with the swagger one.
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

/**
 * Code-first generator for the Essentials admin OpenAPI contract.
 * <p>
 * It reflects the DTO record types into JSON schemas (via swagger-core {@link ModelConverters}, with
 * {@link EssentialsValueTypeModelConverter} collapsing semantic value types to primitives) and builds the
 * REST surface from the declarative mapping table in {@link EssentialsAdminApiSpec}. Every public method of
 * every mapped {@code *Api} interface MUST have exactly one operation descriptor — an unmapped or stale
 * mapping makes {@link #buildOpenApi()} throw, which fails the drift test and forces the contract to be
 * regenerated when the SPIs change.
 *
 * @see OpenApiSpecGenerationTest
 */
public final class OpenApiSpecGenerator {

    /** Path (relative to the module root) of the committed contract — canonical input for the client modules. */
    public static final Path SPEC_FILE = Path.of("openapi", "essentials-admin-api.yaml");

    /** Classpath location the contract is published at, so adapters and their tests can read it as a resource. */
    public static final String SPEC_CLASSPATH_RESOURCE = "/openapi/essentials-admin-api.yaml";

    private static volatile boolean valueTypeConverterRegistered = false;

    private OpenApiSpecGenerator() {
    }

    /**
     * Regenerates the committed spec file. Intended to be invoked by the build (or manually) when the
     * SPIs change. See {@link OpenApiSpecGenerationTest} for the drift guard run in CI.
     */
    public static void main(String[] args) throws IOException {
        Path target = SPEC_FILE;
        Files.createDirectories(target.getParent());
        Files.writeString(target, generateYaml(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + target.toAbsolutePath());
    }

    /** Builds the contract and serializes it to deterministic YAML. */
    public static String generateYaml() {
        return Yaml.pretty(buildOpenApi());
    }

    /** Builds the in-memory {@link OpenAPI} contract, including the unmapped-method parity guard. */
    public static OpenAPI buildOpenApi() {
        var builder = new SpecBuilder();
        EssentialsAdminApiSpec.defineOperations(builder);
        builder.assertEveryInterfaceMethodMapped();

        var openAPI = new OpenAPI()
                .openapi("3.0.3")
                .info(info())
                .addServersItem(new Server()
                                        .url(EssentialsAdminApiSpec.BASE_PATH)
                                        .description("Relative to the host that mounts the Essentials admin API"))
                .tags(tags())
                .paths(builder.paths());

        var components = new Components();
        builder.schemas().forEach(components::addSchemas);
        openAPI.setComponents(components);
        return openAPI;
    }

    private static Info info() {
        return new Info()
                .title("Essentials Admin API")
                .version(EssentialsAdminApiSpec.CONTRACT_VERSION)
                .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0"))
                .description("""
                             Proposed HTTP admin/monitoring contract for the Essentials components (fenced locks, \
                             durable queues, scheduler, event store, CDC and PostgreSQL statistics).

                             This contract is generated code-first from the in-process `*Api` SPI interfaces and is the \
                             single source of truth for both the generated Java client and the HTTP adapter that \
                             serves it (`spring-boot-starter-admin-api`).

                             This document deliberately prescribes no authentication scheme. Authorization is \
                             role-based: each operation lists the roles that satisfy it under the `x-required-roles` \
                             vendor extension, and the `ESSENTIALS_ADMIN` role satisfies every operation. Those \
                             decisions are made by the hosting application's `EssentialsSecurityProvider` \
                             implementation, against the caller reported by its `EssentialsAuthenticatedUser` \
                             implementation. How the caller was authenticated is the host's business.""");
    }

    private static List<Tag> tags() {
        return EssentialsAdminApiSpec.TAGS.entrySet().stream()
                                          .map(e -> new Tag().name(e.getKey()).description(e.getValue()))
                                          .collect(Collectors.toList());
    }

    /**
     * Fluent builder accumulating operations into {@link Paths} while tracking which interface method each
     * operation maps, so {@link #assertEveryInterfaceMethodMapped()} can prove full coverage.
     */
    static final class SpecBuilder {
        private final Map<String, PathItem>            pathItems   = new LinkedHashMap<>();
        private final Map<String, Schema<?>>           schemas     = new TreeMap<>();
        private final Map<Class<?>, Set<String>>       mappedByApi = new LinkedHashMap<>();

        SpecBuilder() {
            resolveDtoSchemas();
            addWrapperSchemas();
        }

        /**
         * Starts an operation descriptor for {@code apiInterface#methodName}, failing fast on a typo, an interface
         * missing from {@link EssentialsAdminApiSpec#API_INTERFACES}, or an overload. Overloads are rejected because
         * the parity guard identifies a method by name alone — mapping one overload would silently pass off the other
         * as covered.
         */
        OperationSpec operation(Class<?> apiInterface, String methodName) {
            if (!EssentialsAdminApiSpec.API_INTERFACES.contains(apiInterface)) {
                throw new IllegalStateException(apiInterface.getSimpleName() + " is not listed in "
                                                        + "EssentialsAdminApiSpec.API_INTERFACES, so its operations would escape the parity guard.");
            }
            var matches = Arrays.stream(apiInterface.getDeclaredMethods())
                                .filter(m -> !m.isSynthetic() && !m.isBridge())
                                .filter(m -> m.getName().equals(methodName))
                                .toList();
            if (matches.isEmpty()) {
                throw new IllegalStateException(apiInterface.getSimpleName() + " declares no method named '"
                                                        + methodName + "' — fix or remove the descriptor in EssentialsAdminApiSpec.");
            }
            if (matches.size() > 1) {
                throw new IllegalStateException(apiInterface.getSimpleName() + "#" + methodName + " is overloaded ("
                                                        + matches.size() + " variants). The contract maps one operation per method name; give the "
                                                        + "overloads distinct names, or extend the mapping table and parity guard to key on the full signature.");
            }
            return new OperationSpec(this, apiInterface, methodName);
        }

        Paths paths() {
            var paths = new Paths();
            pathItems.forEach(paths::addPathItem);
            return paths;
        }

        Map<String, Schema<?>> schemas() {
            return schemas;
        }

        /**
         * Registers the operation under its server-relative path. The {@code /v1} prefix lives in
         * {@code servers[0].url} only — repeating it in the path keys would make every generated client prepend it
         * twice (its default base URI is that same server URL).
         */
        private void register(OperationSpec spec) {
            var pathItem = pathItems.computeIfAbsent(spec.path, k -> new PathItem());
            if (pathItem.readOperationsMap().containsKey(spec.method)) {
                throw new IllegalStateException("Duplicate operation " + spec.method + " " + spec.path
                                                        + " — two descriptors in EssentialsAdminApiSpec map the same verb and path.");
            }
            pathItem.operation(spec.method, spec.toOperation());
            mappedByApi.computeIfAbsent(spec.apiInterface, k -> new LinkedHashSet<>())
                       .add(spec.methodName);
        }

        Schema<?> ref(String schemaName) {
            if (!schemas.containsKey(schemaName)) {
                throw new IllegalStateException("Unknown schema referenced: " + schemaName
                                                        + ". Known schemas: " + schemas.keySet());
            }
            return new Schema<>().$ref("#/components/schemas/" + schemaName);
        }

        private void resolveDtoSchemas() {
            var converters = ModelConverters.getInstance();
            if (!valueTypeConverterRegistered) {
                converters.addConverter(new EssentialsValueTypeModelConverter());
                valueTypeConverterRegistered = true;
            }
            for (Class<?> dto : EssentialsAdminApiSpec.DTO_CLASSES) {
                ResolvedSchema resolved = converters.readAllAsResolvedSchema(dto);
                if (resolved == null || resolved.referencedSchemas.isEmpty()) {
                    throw new IllegalStateException("Failed to resolve schema for " + dto.getName());
                }
                resolved.referencedSchemas.forEach((name, schema) -> schemas.put(name, (Schema<?>) schema));
            }
            applyPropertyMetadata();
        }

        /**
         * Applies the two facets reflection cannot derive: {@code required} for properties guaranteed to be present,
         * and {@code nullable} + reason for those that are null by design. Both are declared in
         * {@link EssentialsAdminApiSpec#ALWAYS_PRESENT_PROPERTIES} / {@link EssentialsAdminApiSpec#NULLABLE_PROPERTIES}
         * and validated against the actual record components, so a renamed field fails the build rather than silently
         * losing its metadata.
         */
        private void applyPropertyMetadata() {
            var recordTypes = discoverRecordTypes(EssentialsAdminApiSpec.DTO_CLASSES);
            assertDeclaredPropertiesExist(recordTypes);

            recordTypes.forEach((schemaName, recordType) -> {
                Schema<?> schema = schemas.get(schemaName);
                if (schema == null || schema.getProperties() == null) {
                    return;
                }
                var alwaysPresent = EssentialsAdminApiSpec.ALWAYS_PRESENT_PROPERTIES.getOrDefault(schemaName, Set.of());
                var nullable      = EssentialsAdminApiSpec.NULLABLE_PROPERTIES.getOrDefault(schemaName, Map.of());
                // Iterate the record components (declaration order) rather than the declaration tables, so the
                // generated YAML is deterministic regardless of Map.of() iteration order.
                for (RecordComponent component : recordType.getRecordComponents()) {
                    var       name     = component.getName();
                    Schema<?> property = (Schema<?>) schema.getProperties().get(name);
                    if (property == null) {
                        continue;
                    }
                    var nullableReason = nullable.get(name);
                    if (nullableReason != null) {
                        markNullable(schema, name, property, nullableReason);
                    } else if (component.getType().isPrimitive() || alwaysPresent.contains(name)) {
                        schema.addRequiredItem(name);
                    }
                }
            });
        }

        /**
         * Marks a property nullable and documents why.
         * <p>
         * A {@code $ref} property needs special handling: in OpenAPI 3.0 any sibling of {@code $ref} is ignored, so
         * setting {@code nullable}/{@code description} directly on it would be silently dropped on serialization.
         * Wrapping the reference in a single-element {@code allOf} gives the annotations somewhere to live; the
         * explicit {@code type} keeps {@code nullable} meaningful (and satisfies the {@code nullable-type-sibling}
         * lint rule).
         */
        @SuppressWarnings("rawtypes")
        private static void markNullable(Schema<?> owningSchema, String propertyName, Schema<?> property, String reason) {
            Schema target = property;
            if (property.get$ref() != null) {
                target = new Schema<>().type("object")
                                       .addAllOfItem(new Schema<>().$ref(property.get$ref()));
                owningSchema.getProperties().put(propertyName, target);
            }
            target.setNullable(true);
            target.setDescription(reason);
        }

        /** Transitively collects every record type reachable from the declared DTOs, keyed by schema (= simple) name. */
        private static Map<String, Class<?>> discoverRecordTypes(List<Class<?>> roots) {
            var byName  = new LinkedHashMap<String, Class<?>>();
            var pending = new ArrayDeque<Class<?>>(roots);
            while (!pending.isEmpty()) {
                Class<?> type = pending.poll();
                if (!type.isRecord() || byName.containsKey(type.getSimpleName())) {
                    continue;
                }
                byName.put(type.getSimpleName(), type);
                for (RecordComponent component : type.getRecordComponents()) {
                    pending.add(component.getType());
                }
            }
            return byName;
        }

        private static void assertDeclaredPropertiesExist(Map<String, Class<?>> recordTypes) {
            var problems = new TreeSet<String>();
            EssentialsAdminApiSpec.ALWAYS_PRESENT_PROPERTIES.forEach(
                    (schemaName, properties) -> properties.forEach(
                            property -> checkDeclaredProperty(recordTypes, problems, "ALWAYS_PRESENT_PROPERTIES", schemaName, property)));
            EssentialsAdminApiSpec.NULLABLE_PROPERTIES.forEach(
                    (schemaName, properties) -> properties.keySet().forEach(
                            property -> checkDeclaredProperty(recordTypes, problems, "NULLABLE_PROPERTIES", schemaName, property)));
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Admin API property metadata refers to unknown DTO properties:\n  "
                                                        + String.join("\n  ", problems));
            }
        }

        private static void checkDeclaredProperty(Map<String, Class<?>> recordTypes,
                                                  Set<String> problems,
                                                  String table,
                                                  String schemaName,
                                                  String property) {
            Class<?> recordType = recordTypes.get(schemaName);
            if (recordType == null) {
                problems.add(table + " declares '" + schemaName + "', which is not a record reachable from DTO_CLASSES.");
                return;
            }
            var known = Arrays.stream(recordType.getRecordComponents())
                              .map(RecordComponent::getName)
                              .collect(Collectors.toCollection(TreeSet::new));
            if (!known.contains(property)) {
                problems.add(table + "['" + schemaName + "'] declares unknown property '" + property
                                     + "' — known properties: " + known);
            }
        }

        private void addWrapperSchemas() {
            schemas.put("CountResult", new ObjectSchema()
                    .description("A total count.")
                    .addProperty("total", new IntegerSchema().format("int64"))
                    .addRequiredItem("total"));
            schemas.put("ReleaseResult", new ObjectSchema()
                    .description("Outcome of a lock release.")
                    .addProperty("released", new BooleanSchema())
                    .addRequiredItem("released"));
            schemas.put("DeleteResult", new ObjectSchema()
                    .description("Outcome of a message deletion.")
                    .addProperty("deleted", new BooleanSchema())
                    .addRequiredItem("deleted"));
            schemas.put("PurgeResult", new ObjectSchema()
                    .description("Number of messages removed by a purge.")
                    .addProperty("purgedCount", new IntegerSchema().format("int32"))
                    .addRequiredItem("purgedCount"));
            schemas.put("QueueNameResult", new ObjectSchema()
                    .description("A resolved queue name.")
                    .addProperty("queueName", new StringSchema())
                    .addRequiredItem("queueName"));
            schemas.put("GlobalEventOrderResult", new ObjectSchema()
                    .description("A persisted global event order value.")
                    .addProperty("globalEventOrder", new IntegerSchema().format("int64"))
                    .addRequiredItem("globalEventOrder"));
            schemas.put("ResurrectDeadLetterMessageRequest", new ObjectSchema()
                    .description("Re-queue parameters for resurrecting a dead-letter message.")
                    .addProperty("deliveryDelay", new StringSchema()
                            .format("duration")
                            .example("PT0S")
                            .description("ISO-8601 delay before re-delivery; PT0S means immediate."))
                    .addRequiredItem("deliveryDelay"));
            schemas.put("Error", new ObjectSchema()
                    .description("Error response.")
                    .addProperty("status", new IntegerSchema().format("int32").description("The HTTP status code."))
                    .addProperty("error", new StringSchema().description("Short, stable reason phrase for the status."))
                    .addProperty("message", new StringSchema()
                            .description("Human-readable detail. May be omitted where detail would leak internals.")
                            .nullable(true))
                    .addRequiredItem("status")
                    .addRequiredItem("error"));
        }

        void assertEveryInterfaceMethodMapped() {
            var problems = new ArrayList<String>();
            for (Class<?> api : EssentialsAdminApiSpec.API_INTERFACES) {
                var declared = Arrays.stream(api.getDeclaredMethods())
                                     .filter(m -> !m.isSynthetic() && !m.isBridge())
                                     .map(Method::getName)
                                     .collect(Collectors.toCollection(TreeSet::new));
                var mapped = new TreeSet<>(mappedByApi.getOrDefault(api, Set.of()));
                var unmapped = new TreeSet<>(declared);
                unmapped.removeAll(mapped);
                var stale = new TreeSet<>(mapped);
                stale.removeAll(declared);
                if (!unmapped.isEmpty()) {
                    problems.add(api.getSimpleName() + " has unmapped method(s): " + unmapped
                                         + " — add a descriptor in EssentialsAdminApiSpec.");
                }
                if (!stale.isEmpty()) {
                    problems.add(api.getSimpleName() + " maps non-existent method(s): " + stale
                                         + " — remove the stale descriptor in EssentialsAdminApiSpec.");
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Admin API contract is out of sync with the SPI interfaces:\n  "
                                                        + String.join("\n  ", problems));
            }
        }
    }

    /** Builder for a single operation; a terminal {@code response*} call registers it with the {@link SpecBuilder}. */
    static final class OperationSpec {
        private final SpecBuilder       owner;
        private final Class<?>          apiInterface;
        private final String            methodName;
        private       String            tag;
        private       PathItem.HttpMethod method;
        private       String            path;
        private       String            summary;
        private       List<String>      roles = List.of();
        private final List<Parameter>   parameters  = new ArrayList<>();
        private       RequestBody       requestBody;

        OperationSpec(SpecBuilder owner, Class<?> apiInterface, String methodName) {
            this.owner = owner;
            this.apiInterface = apiInterface;
            this.methodName = methodName;
        }

        OperationSpec tag(String tag) {
            this.tag = tag;
            return this;
        }

        OperationSpec get(String path) {
            return verb(PathItem.HttpMethod.GET, path);
        }

        OperationSpec post(String path) {
            return verb(PathItem.HttpMethod.POST, path);
        }

        OperationSpec delete(String path) {
            return verb(PathItem.HttpMethod.DELETE, path);
        }

        private OperationSpec verb(PathItem.HttpMethod method, String path) {
            this.method = method;
            this.path = path;
            return this;
        }

        OperationSpec summary(String summary) {
            this.summary = summary;
            return this;
        }

        OperationSpec roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        OperationSpec pathParam(String name, Schema<?> schema, String description) {
            parameters.add(new PathParameter().name(name).required(true).schema(schema).description(description));
            return this;
        }

        OperationSpec queryParam(String name, Schema<?> schema, boolean required, String description) {
            parameters.add(new QueryParameter().name(name).required(required).schema(schema).description(description));
            return this;
        }

        OperationSpec pagination() {
            queryParam("startIndex", new IntegerSchema().format("int64")._default(0L), false, "Zero-based index of the first row to return.");
            queryParam("pageSize", new IntegerSchema().format("int64")._default(100L), false, "Maximum number of rows to return.");
            return this;
        }

        OperationSpec requestBody(String schemaName) {
            this.requestBody = new RequestBody().required(true).content(jsonContent(owner.ref(schemaName)));
            return this;
        }

        // ---- terminal response builders (register the operation) ----

        void responseArray(String schemaName) {
            ok(new ArraySchema().items(owner.ref(schemaName)), "The matching items.");
        }

        void responseStringSet(String description) {
            ok(new ArraySchema().uniqueItems(true).items(new StringSchema()), description);
        }

        void responseRef(String schemaName, String description) {
            ok(owner.ref(schemaName), description);
        }

        void responseMap(String valueSchemaName, String description) {
            ok(new MapSchema().additionalProperties(owner.ref(valueSchemaName)), description);
        }

        void responseOptionalRef(String schemaName, String description) {
            okOrNotFound(owner.ref(schemaName), description);
        }

        void responseCount() {
            ok(owner.ref("CountResult"), "The total count.");
        }

        void responseReleased() {
            ok(owner.ref("ReleaseResult"), "Whether the lock was released.");
        }

        void responseDeleted() {
            ok(owner.ref("DeleteResult"), "Whether the message was deleted.");
        }

        void responsePurged() {
            ok(owner.ref("PurgeResult"), "Number of messages purged.");
        }

        void responseQueueNameOptional() {
            okOrNotFound(owner.ref("QueueNameResult"), "The resolved queue name.");
        }

        void responseGlobalEventOrderOptional() {
            okOrNotFound(owner.ref("GlobalEventOrderResult"), "The highest persisted global event order.");
        }

        private void ok(Schema<?> body, String description) {
            register(responses(body, description, false));
        }

        private void okOrNotFound(Schema<?> body, String description) {
            register(responses(body, description, true));
        }

        /**
         * Builds the full response set for the operation: the success body plus every error status an adapter is
         * allowed to return. {@code 400} is only declared where there is something to reject (a parameter or a
         * request body); {@code 500} is declared everywhere.
         */
        private ApiResponses responses(Schema<?> body, String description, boolean notFound) {
            var responses = new ApiResponses()
                    .addApiResponse("200", jsonResponse("200 — " + description, body));
            if (!parameters.isEmpty() || requestBody != null) {
                responses.addApiResponse("400", error("Malformed or out-of-range parameter, or invalid request body."));
            }
            responses.addApiResponse("401", error("Unauthenticated."))
                     .addApiResponse("403", error("Caller lacks one of the required roles."));
            if (notFound) {
                responses.addApiResponse("404", error("No value exists for the given identifier."));
            }
            return responses.addApiResponse("500", error("Unexpected server error."));
        }

        private ApiResponse error(String description) {
            return jsonResponse(description, owner.ref("Error"));
        }

        private void register(ApiResponses responses) {
            this.builtResponses = responses;
            owner.register(this);
        }

        private ApiResponses builtResponses;

        private Operation toOperation() {
            var operation = new Operation()
                    .operationId(methodName)
                    .summary(summary)
                    .addTagsItem(tag)
                    .responses(builtResponses);
            operation.addExtension("x-required-roles", new ArrayList<>(roles));
            parameters.forEach(operation::addParametersItem);
            if (requestBody != null) {
                operation.setRequestBody(requestBody);
            }
            return operation;
        }

        private static Content jsonContent(Schema<?> schema) {
            return new Content().addMediaType("application/json", new MediaType().schema(schema));
        }

        private static ApiResponse jsonResponse(String description, Schema<?> schema) {
            return new ApiResponse().description(description).content(jsonContent(schema));
        }
    }
}
