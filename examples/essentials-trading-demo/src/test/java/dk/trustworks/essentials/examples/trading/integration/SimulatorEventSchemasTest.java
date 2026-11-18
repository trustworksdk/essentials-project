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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SimulatorEventSchemas} against canonical events captured from both simulators' logs
 * ({@code simulator-events/canonical-events.json}, arrays cut to three items): an event as the simulator sent it is never
 * damaged - otherwise every copy would be refused and only the catch-up path would deliver - and a removed or nulled
 * required field is reported where it was removed, nowhere else.
 */
class SimulatorEventSchemasTest {
    private static final JsonMapper            json    = JsonMapper.builder().build();
    private static final SimulatorEventSchemas schemas = SimulatorEventSchemas.load(json);

    @Test
    void every_canonical_event_is_complete() {
        var events = canonicalEvents();
        assertThat(events).hasSizeGreaterThanOrEqualTo(15);
        for (var event : events) {
            assertThat(schemas.damage(event)).as(event.path("eventType").asString()).isEmpty();
        }
    }

    @Test
    void every_event_type_the_demo_subscribes_to_has_a_schema() {
        assertThat(schemas.eventTypes()).contains("OrderAccepted", "OrderRejected", "OrderPartiallyFilled", "OrderFilled", "OrderCancelled", "OrderExpired",
                                                  "SettlementPending", "SettlementDelayed", "SettlementCompleted", "SettlementFailed", "SettlementCancelled",
                                                  "SettlementRejected", "SettlementCorrected", "InstrumentListed", "InstrumentUpdated", "ClosingPriceIssued",
                                                  "BusinessDayClosed", "ClearingStatementIssued", "PaymentPending", "PaymentCompleted", "PaymentFailed",
                                                  "PaymentReturned", "BankStatementIssued");
    }

    @Test
    void removing_any_one_field_is_reported_at_that_field_or_not_at_all() {
        var detected = new HashSet<String>();
        for (var event : canonicalEvents()) {
            for (var path : leafPaths(event, "")) {
                var damaged = (ObjectNode) event.deepCopy();
                remove(damaged, path);
                var damage = schemas.damage(damaged);
                assertThat(damage).as(event.path("eventType").asString() + " without " + path).isSubsetOf("missing:" + path);
                if (!damage.isEmpty()) {
                    detected.add(event.path("eventType").asString() + " " + path);
                }
            }
        }
        assertThat(detected).contains("OrderFilled payload.side",
                                      "OrderFilled payload.instrumentId",
                                      "OrderFilled eventId",
                                      "OrderFilled simulationId",
                                      "PaymentCompleted payload.paymentId",
                                      "SettlementCompleted payload.quantity");
        // Through array items: some field of the first obligation of a clearing statement is required
        assertThat(detected).anyMatch(path -> path.startsWith("ClearingStatementIssued payload.") && path.contains(".0."));
    }

    @Test
    void null_counts_only_where_the_schema_does_not_allow_it() {
        var filled = event("OrderFilled");
        filled.put("correlationId", (String) null);   // nullable in the envelope
        filled.put("simulationId", (String) null);    // nullable in the envelope
        assertThat(schemas.damage(filled)).isEmpty();

        ((ObjectNode) filled.get("payload")).putNull("price");      // nullable: a market order has none
        assertThat(schemas.damage(filled)).isEmpty();

        ((ObjectNode) filled.get("payload")).putNull("side");
        assertThat(schemas.damage(filled)).containsExactly("null:payload.side");
    }

    @Test
    void unknown_fields_and_unknown_event_types_are_tolerated() {
        var filled = event("OrderFilled");
        filled.put("addedLater", "x");
        ((ObjectNode) filled.get("payload")).put("addedLater", 1);
        assertThat(schemas.damage(filled)).isEmpty();

        var unknown = event("OrderFilled");
        unknown.put("eventType", "SomethingNew");
        unknown.remove("payload");
        assertThat(schemas.damage(unknown)).isEmpty();
    }

    // ---- helpers

    private static List<JsonNode> canonicalEvents() {
        try (var in = SimulatorEventSchemasTest.class.getClassLoader().getResourceAsStream("simulator-events/canonical-events.json")) {
            var events = new ArrayList<JsonNode>();
            json.readTree(in).forEach(simulator -> simulator.forEach(events::add));
            return events;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static ObjectNode event(String eventType) {
        return (ObjectNode) canonicalEvents().stream().filter(event -> event.path("eventType").asString().equals(eventType)).findFirst().orElseThrow().deepCopy();
    }

    private static List<String> leafPaths(JsonNode node, String path) {
        var paths = new ArrayList<String>();
        if (node.isObject()) {
            for (var entry : node.properties()) {
                var at = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                paths.add(at);
                paths.addAll(leafPaths(entry.getValue(), at));
            }
        } else if (node.isArray()) {
            for (var i = 0; i < node.size(); i++) {
                paths.addAll(leafPaths(node.get(i), path + "." + i));
            }
        }
        return paths;
    }

    private static void remove(ObjectNode root, String path) {
        var parts  = path.split("\\.");
        JsonNode parent = root;
        for (var i = 0; i < parts.length - 1; i++) {
            parent = parent.isArray() ? parent.get(Integer.parseInt(parts[i])) : parent.get(parts[i]);
        }
        var last = parts[parts.length - 1];
        if (parent instanceof ObjectNode object) {
            object.remove(last);
        } else if (parent instanceof ArrayNode array) {
            array.remove(Integer.parseInt(last));
        }
    }
}
