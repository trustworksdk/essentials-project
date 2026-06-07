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
        long expectedSeconds = 4L * (properties.getWarmup().toSeconds() + properties.getDuration().toSeconds());
        System.out.println("############# [perf-lab] COMPARE START #############");
        System.out.println("############# [perf-lab] expected runtime ~= " + expectedSeconds + "s");

        var tempDir = Files.createTempDirectory("essentials-perf-lab-");
        var pollingFile       = tempDir.resolve("baseline-polling.json");
        var notifyPollingFile = tempDir.resolve("baseline-notify-polling.json");
        var cdcInboxFile      = tempDir.resolve("baseline-cdc-inbox.json");
        var cdcDirectFile     = tempDir.resolve("baseline-cdc-direct.json");
        var runId             = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        runBaselineChild(properties, new LegConfig(pollingFile,       false, false, "inbox",  "lab_" + runId + "_poll"));
        runBaselineChild(properties, new LegConfig(notifyPollingFile, false, true,  "inbox",  "lab_" + runId + "_notify"));
        runBaselineChild(properties, new LegConfig(cdcInboxFile,      true,  false, "inbox",  "lab_" + runId + "_inbox"));
        runBaselineChild(properties, new LegConfig(cdcDirectFile,     true,  false, "direct", "lab_" + runId + "_direct"));

        var polling       = readJson(pollingFile);
        var notifyPolling = readJson(notifyPollingFile);
        var cdcInbox      = readJson(cdcInboxFile);
        var cdcDirect     = readJson(cdcDirectFile);

        var notifyPollingMode = String.valueOf(notifyPolling.getOrDefault("mode", "unknown"));
        var cdcInboxMode      = String.valueOf(cdcInbox.getOrDefault("mode", "unknown"));
        var cdcDirectMode     = String.valueOf(cdcDirect.getOrDefault("mode", "unknown"));
        if (!"polling-notify".equals(notifyPollingMode)) {
            System.out.println("############# [perf-lab] WARNING: notify-polling run mode=" + notifyPollingMode + " (not polling-notify)");
        }
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
        comparison.put("notifyPolling", notifyPolling);
        comparison.put("cdc", cdcInbox); // Backward-compatible alias for existing scripts
        comparison.put("cdcInbox", cdcInbox);
        comparison.put("cdcDirect", cdcDirect);
        comparison.put("delta", buildDelta(polling, cdcInbox)); // Backward-compatible alias for existing scripts
        comparison.put("deltaNotifyPolling", buildDelta(polling, notifyPolling));
        comparison.put("deltaInbox", buildDelta(polling, cdcInbox));
        comparison.put("deltaDirect", buildDelta(polling, cdcDirect));

        var json = objectMapper.writeValueAsString(comparison);
        log.info("Baseline polling vs notify-polling vs CDC (inbox/direct) comparison: {}", json);
        System.out.println("############# [perf-lab] COMPARE DONE #############");
        System.out.println("############# [perf-lab] polling       append_eps=" + value(polling, "appendEventsPerSecond")
                           + " delivery_eps=" + value(polling, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(polling, "p95LatencyMs"));
        System.out.println("############# [perf-lab] notifyPolling append_eps=" + value(notifyPolling, "appendEventsPerSecond")
                           + " delivery_eps=" + value(notifyPolling, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(notifyPolling, "p95LatencyMs")
                           + " mode=" + notifyPollingMode);
        System.out.println("############# [perf-lab] cdcInbox      append_eps=" + value(cdcInbox, "appendEventsPerSecond")
                           + " delivery_eps=" + value(cdcInbox, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(cdcInbox, "p95LatencyMs")
                           + " mode=" + cdcInboxMode);
        System.out.println("############# [perf-lab] cdcDirect     append_eps=" + value(cdcDirect, "appendEventsPerSecond")
                           + " delivery_eps=" + value(cdcDirect, "deliveredEventsPerSecond")
                           + " p95_ms=" + value(cdcDirect, "p95LatencyMs")
                           + " mode=" + cdcDirectMode);
        System.out.println("############# [perf-lab] deltaNotifyPolling append_eps=" + value(comparison.get("deltaNotifyPolling"), "appendEventsPerSecondDiff")
                           + " delivery_eps=" + value(comparison.get("deltaNotifyPolling"), "deliveredEventsPerSecondDiff")
                           + " p95_ms=" + value(comparison.get("deltaNotifyPolling"), "p95LatencyMsDiff"));
        System.out.println("############# [perf-lab] deltaInbox        append_eps=" + value(comparison.get("deltaInbox"), "appendEventsPerSecondDiff")
                           + " delivery_eps=" + value(comparison.get("deltaInbox"), "deliveredEventsPerSecondDiff")
                           + " p95_ms=" + value(comparison.get("deltaInbox"), "p95LatencyMsDiff"));
        System.out.println("############# [perf-lab] deltaDirect       append_eps=" + value(comparison.get("deltaDirect"), "appendEventsPerSecondDiff")
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
                                  LegConfig leg) {
        log.info("Running child baseline scenario with cdc.enabled={}, notify-polling.enabled={}, delivery-mode={}",
                 leg.cdcEnabled(), leg.notifyPollingEnabled(), leg.deliveryMode());
        System.out.println("############# [perf-lab] child start cdc.enabled=" + leg.cdcEnabled()
                                   + " notify-polling.enabled=" + leg.notifyPollingEnabled()
                                   + " delivery-mode=" + leg.deliveryMode());

        var childProperties = new LinkedHashMap<String, Object>();
        childProperties.put("essentials.lab.scenario", "baseline-polling-vs-cdc");
        childProperties.put("essentials.eventstore.cdc.enabled", Boolean.toString(leg.cdcEnabled()));
        childProperties.put("essentials.eventstore.cdc.delivery-mode", leg.deliveryMode());
        childProperties.put("essentials.eventstore.cdc.slot.name", leg.slotName());
        childProperties.put("essentials.eventstore.subscription-manager.notify-polling.enabled",
                            Boolean.toString(leg.notifyPollingEnabled()));
        childProperties.put("essentials.lab.metrics-output-file", leg.output().toAbsolutePath().toString());
        childProperties.put("essentials.lab.mode", parentProperties.getMode().name().toLowerCase());
        childProperties.put("essentials.lab.warmup", parentProperties.getWarmup().toString());
        childProperties.put("essentials.lab.duration", parentProperties.getDuration().toString());
        childProperties.put("essentials.lab.producer-threads", Integer.toString(parentProperties.getProducerThreads()));
        childProperties.put("essentials.lab.subscriber-count", Integer.toString(parentProperties.getSubscriberCount()));
        childProperties.put("essentials.lab.aggregate-cardinality", Integer.toString(parentProperties.getAggregateCardinality()));
        childProperties.put("essentials.lab.random-seed", Long.toString(parentProperties.getRandomSeed()));
        // Quiet-workload throttle. 0 (default) preserves prior unthrottled behaviour.
        // When set, each child leg runs with the same producer rate so the comparison
        // measures wake-up latency rather than peak append/delivery throughput.
        childProperties.put("essentials.lab.producer-rate-hz", Double.toString(parentProperties.getProducerRateHz()));
        // Forward notify-polling tuning knobs (defaults: 50 ms initial / 1 s max / 2.0×
        // multiplier). Without these, every child would silently use framework defaults
        // even when the parent set a different value — making maxDelay tuning experiments
        // impossible to drive from the comparison entrypoint.
        copyIfPresent("essentials.eventstore.subscription-manager.notify-polling.initial-delay", childProperties);
        copyIfPresent("essentials.eventstore.subscription-manager.notify-polling.max-delay", childProperties);
        copyIfPresent("essentials.eventstore.subscription-manager.notify-polling.backoff-multiplier", childProperties);
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
        System.out.println("############# [perf-lab] child done cdc.enabled=" + leg.cdcEnabled()
                                   + " notify-polling.enabled=" + leg.notifyPollingEnabled()
                                   + " delivery-mode=" + leg.deliveryMode());
    }

    /**
     * Per-leg configuration for a child run of the baseline scenario. Compact record so
     * each {@link #runBaselineChild} call site reads as one line.
     *
     * @param output                where the child writes its baseline-metrics JSON
     * @param cdcEnabled            whether to set {@code essentials.eventstore.cdc.enabled}
     * @param notifyPollingEnabled  whether to set
     *                              {@code essentials.eventstore.subscription-manager.notify-polling.enabled}
     *                              — mutually exclusive with {@code cdcEnabled} for clean
     *                              measurement (the framework logs a WARN if both are on)
     * @param deliveryMode          CDC delivery mode (only meaningful when {@code cdcEnabled})
     * @param slotName              CDC slot name — must be unique per run to avoid lag/orphan
     *                              interference between sequential children
     */
    private record LegConfig(Path output,
                             boolean cdcEnabled,
                             boolean notifyPollingEnabled,
                             String deliveryMode,
                             String slotName) {
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
        // Map.of() is capped at 10 entries — once we add the DB-load deltas we exceed that,
        // so switch to an ordered map. Keep keys grouped by concern (throughput, latency,
        // DB load) so the JSON output stays scannable.
        var delta = new LinkedHashMap<String, Object>();
        // Throughput
        delta.put("appendEventsPerSecondDiff",    numeric(cdc.get("appendEventsPerSecond"))    - numeric(polling.get("appendEventsPerSecond")));
        delta.put("deliveredEventsPerSecondDiff", numeric(cdc.get("deliveredEventsPerSecond")) - numeric(polling.get("deliveredEventsPerSecond")));
        delta.put("deliveredEventsDiff",          numeric(cdc.get("deliveredEvents"))          - numeric(polling.get("deliveredEvents")));
        // Latency
        delta.put("p95LatencyMsDiff",             numeric(cdc.get("p95LatencyMs"))             - numeric(polling.get("p95LatencyMs")));
        delta.put("freshnessP95MsDiff",           numeric(cdc.get("freshnessP95Ms"))           - numeric(polling.get("freshnessP95Ms")));
        delta.put("slaUnder1000msPctDiff",        numeric(cdc.get("slaUnder1000msPct"))        - numeric(polling.get("slaUnder1000msPct")));
        delta.put("timeToFirstDeliveryMsDiff",    numeric(cdc.get("timeToFirstDeliveryMs"))    - numeric(polling.get("timeToFirstDeliveryMs")));
        delta.put("timeToCatchUpMsDiff",          numeric(cdc.get("timeToCatchUpMs"))          - numeric(polling.get("timeToCatchUpMs")));
        // DB load — negative diff = LESS DB-load than polling baseline. This is the
        // metric S1 was built to improve, and the one we couldn't measure before.
        delta.put("eventStoreSelectsPerSecondDiff",
                  numeric(cdc.get("eventStoreSelectsPerSecond")) - numeric(polling.get("eventStoreSelectsPerSecond")));
        delta.put("eventStoreSelectsPerSecondPerSubscriberDiff",
                  numeric(cdc.get("eventStoreSelectsPerSecondPerSubscriber")) - numeric(polling.get("eventStoreSelectsPerSecondPerSubscriber")));
        return delta;
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
