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
package dk.trustworks.essentials.examples.trading._demo_harness.load_driver;

import tools.jackson.core.*;

import java.util.*;

/**
 * Fault injection on the simulators' webhook deliveries (design phase 6 §10, F-1): every subscription of both
 * simulators, found by name, gets a delivery fault policy for the run, and the run's evidence - each subscription's
 * attempts by fault applied and by the answer we gave, and its deliveries as the source sees them - is read back at the
 * end. The policy damages delivered copies only, never the canonical event, so the brokerage must end the run exactly
 * as it would without it; the evidence proves the faults happened.
 */
final class SimulatorFaults {
    record Subscription(String simulatorUrl, String id, String name) {
    }

    private final BrokerageHttp      http;
    private final List<String>       simulatorUrls;
    private final List<Subscription> subscriptions = new ArrayList<>();

    SimulatorFaults(BrokerageHttp http, List<String> simulatorUrls) {
        this.http = http;
        this.simulatorUrls = List.copyOf(simulatorUrls);
    }

    /**
     * F-1: duplicates, missing and null fields (one random leaf each, so optional fields are damaged as well as required
     * ones) and copies held back to arrive out of order, at the rates {@code ExchangeSimulatorWebhookIT} uses. No
     * {@code delay}: it holds the simulator's delivery worker and would measure the simulator, not the brokerage.
     */
    static Map<String, Object> deliveryFaultPolicy(long seed) {
        return Map.of("duplicateProbability", 0.3,
                      "missing", Map.of("probability", 0.2),
                      "nulls", Map.of("probability", 0.1),
                      "outOfOrder", Map.of("probability", 0.3, "holdMs", 500),
                      "seed", seed);
    }

    void applyDeliveryFaults(Map<String, Object> policy) {
        for (var simulatorUrl : simulatorUrls) {
            var listed = http.plainGet("FAULTS", simulatorUrl + "/api/webhooks");
            if (!listed.ok()) {
                throw new IllegalStateException("Cannot list the webhook subscriptions of " + simulatorUrl + ": " + listed);
            }
            for (var subscription : http.json(listed)) {
                subscriptions.add(new Subscription(simulatorUrl, subscription.path("id").asString(), subscription.path("name").asString()));
            }
        }
        for (var subscription : subscriptions) {
            var set = http.plainPut("FAULTS", subscription.simulatorUrl() + "/test/webhooks/" + subscription.id() + "/faults", policy);
            if (!set.ok()) {
                throw new IllegalStateException("Cannot set the faults of " + subscription.name() + ": " + set);
            }
        }
    }

    void clearDeliveryFaults() {
        for (var subscription : subscriptions) {
            http.plainDelete("FAULTS", subscription.simulatorUrl() + "/test/webhooks/" + subscription.id() + "/faults");
        }
    }

    /**
     * Per subscription name: {@code attempts} (total, by fault kind - {@code duplicate}, {@code missing},
     * {@code null}, {@code held} ... -, by the HTTP status we answered, and {@code acceptedDamaged}: the damaged copies
     * we answered with a 2xx, by event type and damage, e.g. {@code OrderFilled missing:payload.price}) and {@code deliveries} as the source counts
     * them ({@code delivered}, {@code dead}, {@code pending}, {@code inFlight}).
     */
    Map<String, Object> evidence() {
        var evidence = new TreeMap<String, Object>();
        for (var subscription : subscriptions) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("attempts", http.plainGetStreaming(subscription.simulatorUrl() + "/test/attempts?subscriptionId=" + subscription.id(),
                                                         SimulatorFaults::countAttempts));
            var detail = http.plainGet("FAULTS", subscription.simulatorUrl() + "/api/webhooks/" + subscription.id());
            entry.put("deliveries", detail.ok() ? http.json(detail).path("deliveries") : detail.toString());
            evidence.put(subscription.name(), entry);
        }
        return evidence;
    }

    /**
     * The total of one fault kind over every subscription.
     */
    @SuppressWarnings("unchecked")
    static long total(Map<String, Object> evidence, String faultKind) {
        var total = 0L;
        for (var entry : evidence.values()) {
            var attempts = (Map<String, Object>) ((Map<String, Object>) entry).get("attempts");
            total += ((Map<String, Long>) attempts.get("byFault")).getOrDefault(faultKind, 0L);
        }
        return total;
    }

    private static Map<String, Object> countAttempts(JsonParser parser) {
        var byFault          = new TreeMap<String, Long>();
        var byStatus         = new TreeMap<String, Long>();
        var acceptedDamaged  = new TreeMap<String, Long>();
        var total    = 0L;
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new IllegalStateException("Expected an array of attempts");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            total++;
            String status  = "none";
            String outcome = null;
            String eventType = "?";
            var    damage  = new ArrayList<String>();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                var field = parser.currentName();
                var value = parser.nextToken();
                switch (field) {
                    case "faults" -> {
                        while (parser.nextToken() == JsonToken.VALUE_STRING) {
                            // "missing:payload.price", "duplicate", "held:500ms" -> the kind before the colon
                            var fault = parser.getString();
                            var kind  = fault.contains(":") ? fault.substring(0, fault.indexOf(':')) : fault;
                            byFault.merge(kind, 1L, Long::sum);
                            if (kind.equals("missing") || kind.equals("null") || kind.equals("invalid")) {
                                damage.add(fault);
                            }
                        }
                    }
                    case "httpStatus" -> status = value == JsonToken.VALUE_NULL ? "none" : Integer.toString(parser.getIntValue());
                    case "outcome" -> outcome = parser.getString();
                    case "eventType" -> eventType = parser.getString();
                    default -> parser.skipChildren();
                }
            }
            byStatus.merge("HELD".equals(outcome) ? "held" : status, 1L, Long::sum);
            if (status.startsWith("2")) {
                // A damaged copy we took is recorded as the event, and the intact copy that follows is a duplicate
                var type = eventType;
                damage.forEach(fault -> acceptedDamaged.merge(type + " " + fault, 1L, Long::sum));
            }
        }
        var counts = new LinkedHashMap<String, Object>();
        counts.put("total", total);
        counts.put("byFault", byFault);
        counts.put("byStatus", byStatus);
        counts.put("acceptedDamaged", acceptedDamaged);
        return counts;
    }
}
