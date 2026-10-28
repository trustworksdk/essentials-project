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
package dk.trustworks.essentials.examples.trading.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the simulators' own event schemas require of an event, per event type, read from their OpenAPI documents (the
 * copies in {@code src/main/openapi}, packaged under {@code simulator-openapi/}).
 * <p>
 * A webhook copy that lacks a field its schema requires, or carries {@code null} where the schema does not allow it, was
 * damaged on the way: the simulators never emit such an event. {@link SignedWebhookReceiver} refuses it, so the source
 * retries with a fresh copy - otherwise the damaged copy is recorded as the event and the intact one that follows is
 * dropped as a duplicate. Only required fields are checked: an absent optional field, a {@code null} optional one and an
 * unknown field are all tolerated, so the receivers stay tolerant readers of everything the schemas leave open.
 * The catch-up path does not use this: it reads the canonical event from the log, and there is no better copy to wait
 * for.
 */
public final class SimulatorEventSchemas {
    static final List<String> DOCUMENTS = List.of("simulator-openapi/exchange-simulator/openapi-3.0.json",
                                                  "simulator-openapi/payment-gateway/openapi-3.0.json");
    private static final int MAX_DEPTH = 32;

    /**
     * A required or optional field; {@code object} when its value is an object with fields to check, {@code items}
     * when it is an array of such objects.
     */
    private record Field(boolean required, boolean nullable, Shape object, Shape items) {
    }

    private record Shape(Map<String, Field> fields) {
    }

    private final Map<String, Shape> byEventType;

    private SimulatorEventSchemas(Map<String, Shape> byEventType) {
        this.byEventType = Map.copyOf(byEventType);
    }

    /**
     * Reads both simulators' documents from the classpath.
     */
    public static SimulatorEventSchemas load(ObjectMapper objectMapper) {
        requireNonNull(objectMapper, "No objectMapper provided");
        var byEventType = new HashMap<String, Shape>();
        for (var document : DOCUMENTS) {
            try (var in = SimulatorEventSchemas.class.getClassLoader().getResourceAsStream(document)) {
                if (in == null) {
                    throw new IllegalStateException("The simulator OpenAPI document '" + document + "' is not on the classpath");
                }
                byEventType.putAll(eventShapes(objectMapper.readTree(in).path("components").path("schemas")));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read '" + document + "'", e);
            }
        }
        return new SimulatorEventSchemas(byEventType);
    }

    /**
     * @return the event types a schema is known for
     */
    public Set<String> eventTypes() {
        return byEventType.keySet();
    }

    /**
     * @param envelope a delivered event, as received
     * @return the damage, in the simulators' own notation ({@code missing:payload.price}, {@code null:simulationId});
     * empty when the event carries every required field, or when its event type has no schema here
     */
    public List<String> damage(JsonNode envelope) {
        requireNonNull(envelope, "No envelope provided");
        var shape = byEventType.get(envelope.path("eventType").asString(""));
        if (shape == null) {
            return List.of();
        }
        var damage = new ArrayList<String>();
        check(envelope, shape, "", damage);
        return damage;
    }

    private static void check(JsonNode value, Shape shape, String path, List<String> damage) {
        for (var entry : shape.fields().entrySet()) {
            var name  = entry.getKey();
            var field = entry.getValue();
            var at    = path.isEmpty() ? name : path + "." + name;
            var child = value.get(name);
            if (child == null) {
                if (field.required()) {
                    damage.add("missing:" + at);
                }
            } else if (child.isNull()) {
                if (field.required() && !field.nullable()) {
                    damage.add("null:" + at);
                }
            } else if (field.object() != null && child.isObject()) {
                check(child, field.object(), at, damage);
            } else if (field.items() != null && child.isArray()) {
                for (var i = 0; i < child.size(); i++) {
                    if (child.get(i).isObject()) {
                        check(child.get(i), field.items(), at + "." + i, damage);
                    }
                }
            }
        }
    }

    // ---- reading the OpenAPI documents

    /**
     * Every {@code <Name>Event} schema whose {@code eventType} is an enum names the event types it describes.
     */
    private static Map<String, Shape> eventShapes(JsonNode schemas) {
        var shapes = new HashMap<String, Shape>();
        for (var entry : schemas.properties()) {
            if (!entry.getKey().endsWith("Event")) {
                continue;
            }
            var resolved  = resolve(entry.getValue(), schemas, 0);
            var eventType = resolved.path("properties").path("eventType").path("enum");
            if (!eventType.isArray() || eventType.isEmpty()) {
                continue;
            }
            var shape = shapeOf(resolved, schemas, 0);
            for (var type : eventType) {
                shapes.put(type.asString(), shape);
            }
        }
        return shapes;
    }

    private static Shape shapeOf(ObjectNode schema, JsonNode schemas, int depth) {
        var required = new HashSet<String>();
        schema.path("required").forEach(name -> required.add(name.asString()));
        var fields = new LinkedHashMap<String, Field>();
        for (var property : schema.path("properties").properties()) {
            var resolved = resolve(property.getValue(), schemas, depth + 1);
            Shape object = null;
            Shape items  = null;
            if (resolved.has("properties")) {
                object = shapeOf(resolved, schemas, depth + 1);
            } else if (resolved.has("items")) {
                var item = resolve(resolved.get("items"), schemas, depth + 1);
                if (item.has("properties")) {
                    items = shapeOf(item, schemas, depth + 1);
                }
            }
            fields.put(property.getKey(), new Field(required.contains(property.getKey()), resolved.path("nullable").asBoolean(false), object, items));
        }
        return new Shape(fields);
    }

    /**
     * A schema with its {@code $ref} and {@code allOf} folded in: the referenced and combined schemas' properties and
     * required lists first, the schema's own on top, a property given in both merged key by key (so an event's
     * {@code correlationId: {nullable: true}} keeps the envelope's type). {@code oneOf}/{@code anyOf} are left alone:
     * a field under them is not checked.
     */
    private static ObjectNode resolve(JsonNode schema, JsonNode schemas, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Schema nesting deeper than " + MAX_DEPTH + ": " + schema);
        }
        var merged = schema.isObject() ? ((ObjectNode) schema).objectNode() : null;
        if (merged == null) {
            throw new IllegalStateException("Not a schema: " + schema);
        }
        var parts = new ArrayList<JsonNode>();
        if (schema.has("$ref")) {
            parts.add(resolve(schemas.path(schema.get("$ref").asString().replace("#/components/schemas/", "")), schemas, depth + 1));
        }
        schema.path("allOf").forEach(part -> parts.add(resolve(part, schemas, depth + 1)));
        parts.add(schema);
        for (var part : parts) {
            for (var entry : part.properties()) {
                switch (entry.getKey()) {
                    case "$ref", "allOf" -> {
                    }
                    case "required" -> {
                        var required = merged.has("required") ? (tools.jackson.databind.node.ArrayNode) merged.get("required") : merged.putArray("required");
                        entry.getValue().forEach(required::add);
                    }
                    case "properties" -> {
                        var properties = merged.has("properties") ? (ObjectNode) merged.get("properties") : merged.putObject("properties");
                        for (var property : entry.getValue().properties()) {
                            var existing = properties.get(property.getKey());
                            if (existing instanceof ObjectNode base && property.getValue().isObject()) {
                                var combined = base.deepCopy();
                                combined.setAll((ObjectNode) property.getValue());
                                properties.set(property.getKey(), combined);
                            } else {
                                properties.set(property.getKey(), property.getValue());
                            }
                        }
                    }
                    default -> merged.set(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }
}
