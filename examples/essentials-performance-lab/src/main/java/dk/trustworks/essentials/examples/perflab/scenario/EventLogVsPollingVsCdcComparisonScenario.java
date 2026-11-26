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

package dk.trustworks.essentials.examples.perflab.scenario;

import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabApplication;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import org.slf4j.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * The old engine's polling, notify-polling, CDC inbox and CDC direct legs of {@link BaselinePollingVsCdcComparisonScenario},
 * plus two legs on the new event store engine ({@link EventLogBaselineScenario} with {@code EventReactor}s and with
 * {@code ViewProjection}s), each in its own child Spring context under the same load profile, in one comparison JSON
 * (design phase 8, unit 8b-E). Every non-polling leg gets a {@code delta*} against polling, with the baseline's fields.
 * <p>
 * Opt-in: registered only with {@code essentials.lab.event-log.enabled=true}; the existing scenarios are unchanged.
 */
@Component
@ConditionalOnProperty(name = "essentials.lab.event-log.enabled", havingValue = "true")
public class EventLogVsPollingVsCdcComparisonScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(EventLogVsPollingVsCdcComparisonScenario.class);

    /** Leg name in the JSON, in run order */
    public static final List<String> LEGS = List.of("polling", "notifyPolling", "cdcInbox", "cdcDirect", "eventLogReactor", "eventLogProjection");

    private static final List<String> FORWARDED = List.of(
            "spring.profiles.active", "spring.datasource.url", "spring.datasource.username", "spring.datasource.password", "spring.datasource.driver-class-name",
            "essentials.lab.event-log.pool-size",
            "essentials.eventstore.subscription-manager.notify-polling.initial-delay",
            "essentials.eventstore.subscription-manager.notify-polling.max-delay",
            "essentials.eventstore.subscription-manager.notify-polling.backoff-multiplier",
            "essentials.eventstore.cdc.mode", "essentials.eventstore.cdc.wal-parser-mode", "essentials.eventstore.cdc.cdc-event-store-backfill-batch-size",
            "essentials.eventstore.cdc.cdc-dispatcher.batch-size", "essentials.eventstore.cdc.cdc-dispatcher.poll-interval",
            "essentials.eventstore.cdc.wal2-json-tailer.poll-interval", "essentials.eventstore.cdc.wal2-json-tailer.poll-backoff-interval",
            "essentials.eventstore.cdc.wal2-json-tailer.max-poll-backoff-interval", "essentials.eventstore.cdc.wal2-json-tailer.replication-status-interval",
            "essentials.eventstore.cdc.wal2-json-tailer.max-no-message-wait");

    private final ObjectMapper objectMapper;
    private final Environment  environment;

    public EventLogVsPollingVsCdcComparisonScenario(ObjectMapper objectMapper, Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    public String name() {
        return "event-log-vs-polling-vs-cdc-compare";
    }

    @Override
    public String description() {
        return "Runs the baseline workload on the old engine (polling, notify-polling, CDC inbox, CDC direct) and on the new engine (EventReactor, ViewProjection) and emits a comparison JSON";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        System.out.println("############# [perf-lab] EVENT LOG COMPARE START, expected runtime ~= "
                                   + LEGS.size() * (properties.getWarmup().toSeconds() + properties.getDuration().toSeconds()) + "s");
        var tempDir = Files.createTempDirectory("essentials-perf-lab-event-log-");
        var runId   = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var results = new LinkedHashMap<String, Map<String, Object>>();
        for (var leg : LEGS) {
            var output = tempDir.resolve(leg + ".json");
            var child  = new LinkedHashMap<String, Object>();
            switch (leg) {
                case "polling" -> oldEngine(child, false, false, "inbox", "lab_" + runId + "_poll");
                case "notifyPolling" -> oldEngine(child, false, true, "inbox", "lab_" + runId + "_notify");
                case "cdcInbox" -> oldEngine(child, true, false, "inbox", "lab_" + runId + "_inbox");
                case "cdcDirect" -> oldEngine(child, true, false, "direct", "lab_" + runId + "_direct");
                case "eventLogReactor" -> newEngine(child, "reactor");
                case "eventLogProjection" -> newEngine(child, "projection");
                default -> throw new IllegalStateException(leg);
            }
            runChild(leg, child, output, properties);
            results.put(leg, objectMapper.readValue(output.toFile(), new TypeReference<Map<String, Object>>() {
            }));
        }

        var comparison = new LinkedHashMap<String, Object>();
        comparison.put("scenario", name());
        comparison.put("capturedAt", Instant.now().toString());
        comparison.put("config", Map.of("warmup", properties.getWarmup().toString(),
                                        "duration", properties.getDuration().toString(),
                                        "producerThreads", properties.getProducerThreads(),
                                        "subscriberCount", properties.getSubscriberCount(),
                                        "aggregateCardinality", properties.getAggregateCardinality(),
                                        "producerRateHz", properties.getProducerRateHz(),
                                        "seed", properties.getRandomSeed()));
        comparison.putAll(results);
        var polling = results.get("polling");
        for (var leg : LEGS.subList(1, LEGS.size())) {
            comparison.put("delta" + Character.toUpperCase(leg.charAt(0)) + leg.substring(1), delta(polling, results.get(leg)));
        }

        var json = objectMapper.writeValueAsString(comparison);
        log.info("Event log vs polling vs CDC comparison: {}", json);
        System.out.println("############# [perf-lab] EVENT LOG COMPARE DONE #############");
        results.forEach((leg, r) -> System.out.printf(Locale.ROOT, "############# [perf-lab] %-18s mode=%s append_eps=%s delivery_eps=%s p50_ms=%s p95_ms=%s p99_ms=%s catchup_ms=%s selects_per_s=%s%n",
                                                      leg, r.get("mode"), fmt(r.get("appendEventsPerSecond")), fmt(r.get("deliveredEventsPerSecond")),
                                                      fmt(r.get("p50LatencyMs")), fmt(r.get("p95LatencyMs")), fmt(r.get("p99LatencyMs")), r.get("timeToCatchUpMs"),
                                                      fmt(r.get("eventStoreSelectsPerSecond"))));
        if (StringUtils.hasText(properties.getMetricsOutputFile())) {
            var target = Paths.get(properties.getMetricsOutputFile()).toAbsolutePath().normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, json + System.lineSeparator());
            System.out.println("############# [perf-lab] comparison metrics file: " + target);
        }
    }

    private static void oldEngine(Map<String, Object> child, boolean cdc, boolean notifyPolling, String deliveryMode, String slotName) {
        child.put("essentials.lab.scenario", "baseline-polling-vs-cdc");
        child.put("essentials.eventstore.cdc.enabled", Boolean.toString(cdc));
        child.put("essentials.eventstore.cdc.delivery-mode", deliveryMode);
        child.put("essentials.eventstore.cdc.slot.name", slotName);
        child.put("essentials.eventstore.subscription-manager.notify-polling.enabled", Boolean.toString(notifyPolling));
    }

    /** The old engine's starter still runs (CDC off, no subscriptions), as background for every leg alike */
    private static void newEngine(Map<String, Object> child, String processor) {
        child.put("essentials.lab.scenario", "event-log-baseline");
        child.put("essentials.lab.event-log.enabled", "true");
        child.put(EventLogBaselineScenario.PROCESSOR_PROPERTY, processor);
        child.put("essentials.eventstore.cdc.enabled", "false");
        child.put("essentials.eventstore.subscription-manager.notify-polling.enabled", "false");
    }

    private void runChild(String leg, Map<String, Object> child, Path output, EssentialsPerformanceLabProperties parent) {
        System.out.println("############# [perf-lab] child start leg=" + leg);
        child.put("essentials.lab.metrics-output-file", output.toAbsolutePath().toString());
        child.put("essentials.lab.mode", parent.getMode().name().toLowerCase(Locale.ROOT));
        child.put("essentials.lab.warmup", parent.getWarmup().toString());
        child.put("essentials.lab.duration", parent.getDuration().toString());
        child.put("essentials.lab.producer-threads", Integer.toString(parent.getProducerThreads()));
        child.put("essentials.lab.subscriber-count", Integer.toString(parent.getSubscriberCount()));
        child.put("essentials.lab.aggregate-cardinality", Integer.toString(parent.getAggregateCardinality()));
        child.put("essentials.lab.random-seed", Long.toString(parent.getRandomSeed()));
        child.put("essentials.lab.producer-rate-hz", Double.toString(parent.getProducerRateHz()));
        for (var key : FORWARDED) {
            var value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                child.put(key, value);
            }
        }
        var args = child.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        new SpringApplicationBuilder(EssentialsPerformanceLabApplication.class).web(WebApplicationType.NONE).run(args).close();
        System.out.println("############# [perf-lab] child done leg=" + leg);
    }

    /** {@code leg - polling} for the baseline's comparison fields */
    private static Map<String, Object> delta(Map<String, Object> polling, Map<String, Object> leg) {
        var delta = new LinkedHashMap<String, Object>();
        for (var field : List.of("appendEventsPerSecond", "deliveredEventsPerSecond", "deliveredEvents", "p50LatencyMs", "p95LatencyMs", "p99LatencyMs",
                                 "slaUnder1000msPct", "timeToFirstDeliveryMs", "timeToCatchUpMs", "eventStoreSelectsPerSecond", "eventStoreSelectsPerSecondPerSubscriber")) {
            delta.put(field + "Diff", numeric(leg.get(field)) - numeric(polling.get(field)));
        }
        return delta;
    }

    private static double numeric(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0d;
    }

    private static String fmt(Object value) {
        return value instanceof Number number ? String.format(Locale.ROOT, "%.2f", number.doubleValue()) : String.valueOf(value);
    }
}
