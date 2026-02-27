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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabApplication;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class BaselinePollingVsCdcComparisonScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(BaselinePollingVsCdcComparisonScenario.class);

    private final ObjectMapper objectMapper;
    private final Environment  environment;

    public BaselinePollingVsCdcComparisonScenario(ObjectMapper objectMapper,
                                                  Environment environment) {
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    @Override
    public String name() {
        return "baseline-polling-vs-cdc-compare";
    }

    @Override
    public String description() {
        return "Runs baseline scenario in polling, CDC inbox, and CDC direct modes and emits a comparison JSON";
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        long expectedSeconds = 3L * (properties.getWarmup().toSeconds() + properties.getDuration().toSeconds());
        System.out.println("############# [perf-lab] COMPARE START #############");
        System.out.println("############# [perf-lab] expected runtime ~= " + expectedSeconds + "s");

        var tempDir = Files.createTempDirectory("essentials-perf-lab-");
        var pollingFile = tempDir.resolve("baseline-polling.json");
        var cdcInboxFile = tempDir.resolve("baseline-cdc-inbox.json");
        var cdcDirectFile = tempDir.resolve("baseline-cdc-direct.json");
        var runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        runBaselineChild(properties, pollingFile, false, "inbox", "lab_" + runId + "_poll");
        runBaselineChild(properties, cdcInboxFile, true, "inbox", "lab_" + runId + "_inbox");
        runBaselineChild(properties, cdcDirectFile, true, "direct", "lab_" + runId + "_direct");

        var polling = readJson(pollingFile);
        var cdcInbox = readJson(cdcInboxFile);
        var cdcDirect = readJson(cdcDirectFile);

        var cdcInboxMode = String.valueOf(cdcInbox.getOrDefault("mode", "unknown"));
        var cdcDirectMode = String.valueOf(cdcDirect.getOrDefault("mode", "unknown"));
        if (!"cdc-active".equals(cdcInboxMode)) {
            System.out.println("############# [perf-lab] WARNING: CDC inbox run mode=" + cdcInboxMode + " (not cdc-active)");
        }
        if (!"cdc-active".equals(cdcDirectMode)) {
            System.out.println("############# [perf-lab] WARNING: CDC direct run mode=" + cdcDirectMode + " (not cdc-active)");
        }

        var comparison = new LinkedHashMap<String, Object>();
        comparison.put("scenario", name());
        comparison.put("capturedAt", Instant.now().toString());
        comparison.put("config", Map.of(
                "warmup", properties.getWarmup().toString(),
                "duration", properties.getDuration().toString(),
                "producerThreads", properties.getProducerThreads(),
                "subscriberCount", properties.getSubscriberCount(),
                "aggregateCardinality", properties.getAggregateCardinality(),
                "seed", properties.getRandomSeed()
        ));
        comparison.put("polling", polling);
        comparison.put("cdc", cdcInbox); // Backward-compatible alias for existing scripts
        comparison.put("cdcInbox", cdcInbox);
        comparison.put("cdcDirect", cdcDirect);
        comparison.put("delta", buildDelta(polling, cdcInbox)); // Backward-compatible alias for existing scripts
        comparison.put("deltaInbox", buildDelta(polling, cdcInbox));
        comparison.put("deltaDirect", buildDelta(polling, cdcDirect));

        var json = objectMapper.writeValueAsString(comparison);
        log.info("Baseline polling vs CDC (inbox/direct) comparison: {}", json);
        System.out.println("############# [perf-lab] COMPARE DONE #############");
        System.out.println("############# [perf-lab] polling append_eps=" + value(polling, "appendEventsPerSecond")
                           + " delivery_eps=" + value(polling, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(polling, "p95LatencyMs"));
        System.out.println("############# [perf-lab] cdcInbox append_eps=" + value(cdcInbox, "appendEventsPerSecond")
                           + " delivery_eps=" + value(cdcInbox, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(cdcInbox, "p95LatencyMs")
                           + " mode=" + cdcInboxMode);
        System.out.println("############# [perf-lab] cdcDirect append_eps=" + value(cdcDirect, "appendEventsPerSecond")
                           + " delivery_eps=" + value(cdcDirect, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(cdcDirect, "p95LatencyMs")
                           + " mode=" + cdcDirectMode);
        System.out.println("############# [perf-lab] delta   append_eps=" + value(comparison.get("delta"), "appendEventsPerSecondDiff")
                           + " delivery_eps=" + value(comparison.get("delta"), "deliveredEventsPerSecondDiff")
                           + " p95_ms=" + value(comparison.get("delta"), "p95LatencyMsDiff"));
        System.out.println("############# [perf-lab] deltaDirect append_eps=" + value(comparison.get("deltaDirect"), "appendEventsPerSecondDiff")
                           + " delivery_eps=" + value(comparison.get("deltaDirect"), "deliveredEventsPerSecondDiff")
                           + " p95_ms=" + value(comparison.get("deltaDirect"), "p95LatencyMsDiff"));
        System.out.println("############# [perf-lab] ################################");

        if (StringUtils.hasText(properties.getMetricsOutputFile())) {
            var target = Paths.get(properties.getMetricsOutputFile()).toAbsolutePath().normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target,
                              json + System.lineSeparator(),
                              StandardOpenOption.CREATE,
                              StandardOpenOption.TRUNCATE_EXISTING,
                              StandardOpenOption.WRITE);
            log.info("Wrote comparison metrics to {}", target);
            System.out.println("############# [perf-lab] comparison metrics file: " + target);
        }
    }

    private void runBaselineChild(EssentialsPerformanceLabProperties parentProperties,
                                  Path output,
                                  boolean cdcEnabled,
                                  String deliveryMode,
                                  String slotName) {
        log.info("Running child baseline scenario with cdc.enabled={}, delivery-mode={}", cdcEnabled, deliveryMode);
        System.out.println("############# [perf-lab] child start cdc.enabled=" + cdcEnabled + " delivery-mode=" + deliveryMode);

        var childProperties = new LinkedHashMap<String, Object>();
        childProperties.put("essentials.lab.scenario", "baseline-polling-vs-cdc");
        childProperties.put("essentials.eventstore.cdc.enabled", Boolean.toString(cdcEnabled));
        childProperties.put("essentials.eventstore.cdc.delivery-mode", deliveryMode);
        childProperties.put("essentials.eventstore.cdc.slot.name", slotName);
        childProperties.put("essentials.lab.metrics-output-file", output.toAbsolutePath().toString());
        childProperties.put("essentials.lab.mode", parentProperties.getMode().name().toLowerCase());
        childProperties.put("essentials.lab.warmup", parentProperties.getWarmup().toString());
        childProperties.put("essentials.lab.duration", parentProperties.getDuration().toString());
        childProperties.put("essentials.lab.producer-threads", Integer.toString(parentProperties.getProducerThreads()));
        childProperties.put("essentials.lab.subscriber-count", Integer.toString(parentProperties.getSubscriberCount()));
        childProperties.put("essentials.lab.aggregate-cardinality", Integer.toString(parentProperties.getAggregateCardinality()));
        childProperties.put("essentials.lab.random-seed", Long.toString(parentProperties.getRandomSeed()));
        copyIfPresent("spring.profiles.active", childProperties);
        copyIfPresent("spring.datasource.url", childProperties);
        copyIfPresent("spring.datasource.username", childProperties);
        copyIfPresent("spring.datasource.password", childProperties);
        copyIfPresent("spring.datasource.driver-class-name", childProperties);
        copyIfPresent("essentials.eventstore.cdc.mode", childProperties);
        copyIfPresent("essentials.eventstore.cdc.wal-parser-mode", childProperties);
        copyIfPresent("essentials.eventstore.cdc.cdc-event-store-backfill-batch-size", childProperties);
        copyIfPresent("essentials.eventstore.cdc.cdc-dispatcher.batch-size", childProperties);
        copyIfPresent("essentials.eventstore.cdc.cdc-dispatcher.poll-interval", childProperties);
        copyIfPresent("essentials.eventstore.cdc.wal2-json-tailer.poll-interval", childProperties);
        copyIfPresent("essentials.eventstore.cdc.wal2-json-tailer.poll-backoff-interval", childProperties);
        copyIfPresent("essentials.eventstore.cdc.wal2-json-tailer.max-poll-backoff-interval", childProperties);
        copyIfPresent("essentials.eventstore.cdc.wal2-json-tailer.replication-status-interval", childProperties);
        copyIfPresent("essentials.eventstore.cdc.wal2-json-tailer.max-no-message-wait", childProperties);

        String[] args = childProperties.entrySet().stream()
                                       .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                                       .toArray(String[]::new);

        new SpringApplicationBuilder(EssentialsPerformanceLabApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)
                .close();
        System.out.println("############# [perf-lab] child done cdc.enabled=" + cdcEnabled + " delivery-mode=" + deliveryMode);
    }

    private void copyIfPresent(String key, Map<String, Object> target) {
        var value = environment.getProperty(key);
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private Map<String, Object> readJson(Path path) throws IOException {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {
        });
    }

    private Map<String, Object> buildDelta(Map<String, Object> polling,
                                           Map<String, Object> cdc) {
        return Map.of(
                "appendEventsPerSecondDiff", numeric(cdc.get("appendEventsPerSecond")) - numeric(polling.get("appendEventsPerSecond")),
                "deliveredEventsPerSecondDiff", numeric(cdc.get("deliveredEventsPerSecond")) - numeric(polling.get("deliveredEventsPerSecond")),
                "p95LatencyMsDiff", numeric(cdc.get("p95LatencyMs")) - numeric(polling.get("p95LatencyMs")),
                "deliveredEventsDiff", numeric(cdc.get("deliveredEvents")) - numeric(polling.get("deliveredEvents")),
                "freshnessP95MsDiff", numeric(cdc.get("freshnessP95Ms")) - numeric(polling.get("freshnessP95Ms")),
                "slaUnder1000msPctDiff", numeric(cdc.get("slaUnder1000msPct")) - numeric(polling.get("slaUnder1000msPct")),
                "timeToFirstDeliveryMsDiff", numeric(cdc.get("timeToFirstDeliveryMs")) - numeric(polling.get("timeToFirstDeliveryMs")),
                "timeToCatchUpMsDiff", numeric(cdc.get("timeToCatchUpMs")) - numeric(polling.get("timeToCatchUpMs"))
        );
    }

    private static double numeric(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }

    @SuppressWarnings("unchecked")
    private static String value(Object mapObject, String key) {
        if (!(mapObject instanceof Map<?, ?> map)) return "n/a";
        var raw = ((Map<String, Object>) map).get(key);
        if (raw == null) return "n/a";
        if (raw instanceof Number number) {
            return String.format(java.util.Locale.ROOT, "%.2f", number.doubleValue());
        }
        return raw.toString();
    }
}
