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

package dk.trustworks.essentials.components.eventlog.migration.cli;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.migration.*;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Reads a {@link MigrationConfig} from YAML (SnakeYAML, safe constructor: plain maps, lists and scalars only). An unknown
 * key is refused, so that a misspelt setting never silently falls back to its default. The shape:
 * <pre>{@code
 * connection:
 *   url: jdbc:postgresql://localhost:5432/app
 *   user: app
 *   passwordEnv: MIGRATION_PASSWORD      # or password: ...
 * schemaOwnership: COMPONENT             # optional; COMPONENT (default) or HARNESS
 * tables:
 *   - aggregateType: Orders
 *     table: orders_events
 *     columns:                           # optional; any subset, the rest are the old engine's defaults
 *       tenant: tenant
 * settings:                              # optional; any subset, the rest are CopySettings.defaults()
 *   batchSize: 10000
 *   epochWaitTimeout: 10m                # ISO-8601 (PT10M) or <n>ms|s|m|h
 *   epochWaitPoll: 20ms
 *   lockTimeout: 10s
 *   maxEventsPerSecond: 0
 *   verifyWindow: 100000
 * import:                                # optional
 *   durableSubscriptionsTable: durable_subscriptions   # optional, the old engine's defaults
 *   transientGapsTable: transient_subscriber_gaps
 *   permanentGapsTable: permanent_gaps
 *   subscriptions:
 *     - oldSubscriber: orders-projector
 *       subscriber: orders-view
 *       aggregateType: Orders
 *       model: PERIODIC                  # or IN_HANDLER_TRANSACTION
 *       consistency: FRESH               # optional; FRESH (default) or CONTIGUOUS
 *       instances: [node-a, node-b]      # optional; absent for an exclusive subscription
 *       eventTypes: [OrderPlaced]        # optional; absent for all
 *       tenant: acme                     # optional
 * }</pre>
 */
public final class MigrationConfigYaml {
    private static final Pattern SHORT_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)");

    private MigrationConfigYaml() {
    }

    /**
     * Read {@code file}, resolving {@code passwordEnv} from the process environment
     */
    public static MigrationConfig read(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(requireNonNull(file, "No file provided"), StandardCharsets.UTF_8)) {
            return parse(reader, System::getenv);
        }
    }

    /**
     * @param environment resolves {@code passwordEnv}
     * @throws IllegalArgumentException if the document is not a valid configuration
     */
    public static MigrationConfig parse(Reader reader, Function<String, String> environment) {
        requireNonNull(reader, "No reader provided");
        requireNonNull(environment, "No environment provided");
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The configuration is not valid YAML: " + e.getMessage(), e);
        }
        var root = map(document, "the configuration");
        keys(root, "the configuration", "connection", "schemaOwnership", "tables", "settings", "import");
        return new MigrationConfig(connection(map(required(root, "connection", "the configuration"), "connection"), environment),
                                   root.containsKey("schemaOwnership") ? enumValue(SchemaOwnership.class, root.get("schemaOwnership"), "schemaOwnership")
                                                                       : SchemaOwnership.COMPONENT,
                                   tables(list(required(root, "tables", "the configuration"), "tables")),
                                   settings(root.containsKey("settings") ? map(root.get("settings"), "settings") : Map.of()),
                                   imports(root.containsKey("import") ? map(root.get("import"), "import") : null));
    }

    private static MigrationConfig.ConnectionSettings connection(Map<String, Object> map, Function<String, String> environment) {
        keys(map, "connection", "url", "user", "password", "passwordEnv");
        if (map.containsKey("password") && map.containsKey("passwordEnv")) {
            throw new IllegalArgumentException("connection: give either 'password' or 'passwordEnv', not both");
        }
        String password = optionalString(map, "password", "connection");
        if (map.containsKey("passwordEnv")) {
            var variable = string(map.get("passwordEnv"), "connection.passwordEnv");
            password = environment.apply(variable);
            if (password == null) {
                throw new IllegalArgumentException("connection.passwordEnv: the environment variable '" + variable + "' is not set");
            }
        }
        return new MigrationConfig.ConnectionSettings(string(required(map, "url", "connection"), "connection.url"), optionalString(map, "user", "connection"), password);
    }

    private static List<SourceTable> tables(List<Object> list) {
        var tables = new ArrayList<SourceTable>();
        for (var i = 0; i < list.size(); i++) {
            var where = "tables[" + i + "]";
            var map   = map(list.get(i), where);
            keys(map, where, "aggregateType", "table", "columns");
            var columns = SourceColumns.defaults();
            if (map.containsKey("columns")) {
                columns = columns(map(map.get("columns"), where + ".columns"), where + ".columns");
            }
            tables.add(new SourceTable(string(required(map, "aggregateType", where), where + ".aggregateType"), string(required(map, "table", where), where + ".table"),
                                       columns));
        }
        return tables;
    }

    private static SourceColumns columns(Map<String, Object> map, String where) {
        keys(map, where, "globalOrder", "aggregateId", "eventOrder", "eventId", "causedByEventId", "correlationId", "eventType", "eventRevision", "timestamp",
             "eventPayload", "eventMetaData", "tenant");
        var d = SourceColumns.defaults();
        Function<String, String> or = key -> optionalString(map, key, where);
        return new SourceColumns(Objects.requireNonNullElse(or.apply("globalOrder"), d.globalOrder()),
                                 Objects.requireNonNullElse(or.apply("aggregateId"), d.aggregateId()),
                                 Objects.requireNonNullElse(or.apply("eventOrder"), d.eventOrder()),
                                 Objects.requireNonNullElse(or.apply("eventId"), d.eventId()),
                                 Objects.requireNonNullElse(or.apply("causedByEventId"), d.causedByEventId()),
                                 Objects.requireNonNullElse(or.apply("correlationId"), d.correlationId()),
                                 Objects.requireNonNullElse(or.apply("eventType"), d.eventType()),
                                 Objects.requireNonNullElse(or.apply("eventRevision"), d.eventRevision()),
                                 Objects.requireNonNullElse(or.apply("timestamp"), d.timestamp()),
                                 Objects.requireNonNullElse(or.apply("eventPayload"), d.eventPayload()),
                                 Objects.requireNonNullElse(or.apply("eventMetaData"), d.eventMetaData()),
                                 Objects.requireNonNullElse(or.apply("tenant"), d.tenant()));
    }

    private static CopySettings settings(Map<String, Object> map) {
        keys(map, "settings", "batchSize", "epochWaitTimeout", "epochWaitPoll", "lockTimeout", "maxEventsPerSecond", "verifyWindow");
        var d = CopySettings.defaults();
        return new CopySettings(map.containsKey("batchSize") ? (int) number(map.get("batchSize"), "settings.batchSize") : d.batchSize(),
                                map.containsKey("epochWaitTimeout") ? duration(map.get("epochWaitTimeout"), "settings.epochWaitTimeout") : d.epochWaitTimeout(),
                                map.containsKey("epochWaitPoll") ? duration(map.get("epochWaitPoll"), "settings.epochWaitPoll") : d.epochWaitPoll(),
                                map.containsKey("lockTimeout") ? duration(map.get("lockTimeout"), "settings.lockTimeout") : d.lockTimeout(),
                                map.containsKey("maxEventsPerSecond") ? number(map.get("maxEventsPerSecond"), "settings.maxEventsPerSecond") : d.maxEventsPerSecond(),
                                map.containsKey("verifyWindow") ? (int) number(map.get("verifyWindow"), "settings.verifyWindow") : d.verifyWindow());
    }

    private static MigrationConfig.Imports imports(Map<String, Object> map) {
        var none = MigrationConfig.Imports.none();
        if (map == null) {
            return none;
        }
        keys(map, "import", "durableSubscriptionsTable", "transientGapsTable", "permanentGapsTable", "subscriptions");
        var subscriptions = new ArrayList<MigrationConfig.Subscription>();
        var list          = map.containsKey("subscriptions") ? list(map.get("subscriptions"), "import.subscriptions") : List.of();
        for (var i = 0; i < list.size(); i++) {
            var where = "import.subscriptions[" + i + "]";
            var s     = map(list.get(i), where);
            keys(s, where, "oldSubscriber", "subscriber", "aggregateType", "model", "consistency", "instances", "eventTypes", "tenant");
            subscriptions.add(new MigrationConfig.Subscription(string(required(s, "oldSubscriber", where), where + ".oldSubscriber"),
                                                               string(required(s, "subscriber", where), where + ".subscriber"),
                                                               string(required(s, "aggregateType", where), where + ".aggregateType"),
                                                               enumValue(ProgressModel.class, required(s, "model", where), where + ".model"),
                                                               s.containsKey("consistency") ? enumValue(ConsistencyMode.class, s.get("consistency"), where + ".consistency")
                                                                                            : ConsistencyMode.FRESH,
                                                               s.containsKey("instances") ? strings(s.get("instances"), where + ".instances") : List.of(),
                                                               s.containsKey("eventTypes") ? new LinkedHashSet<>(strings(s.get("eventTypes"), where + ".eventTypes")) : Set.of(),
                                                               optionalString(s, "tenant", where)));
        }
        return new MigrationConfig.Imports(Objects.requireNonNullElse(optionalString(map, "durableSubscriptionsTable", "import"), none.durableSubscriptionsTable()),
                                           Objects.requireNonNullElse(optionalString(map, "transientGapsTable", "import"), none.transientGapsTable()),
                                           Objects.requireNonNullElse(optionalString(map, "permanentGapsTable", "import"), none.permanentGapsTable()),
                                           subscriptions);
    }

    // ------------------------------------------------------------------ scalars

    static Duration duration(Object value, String where) {
        var text = string(value, where).trim();
        try {
            if (text.startsWith("P") || text.startsWith("p")) {
                return Duration.parse(text);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(where + ": '" + text + "' is not an ISO-8601 duration", e);
        }
        var m = SHORT_DURATION.matcher(text);
        if (!m.matches()) {
            throw new IllegalArgumentException(where + ": '" + text + "' is not a duration (ISO-8601 such as PT10S, or <n>ms|s|m|h such as 10s)");
        }
        var n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String where) {
        var text = string(value, where);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": '" + text + "' is not one of " + Arrays.toString(type.getEnumConstants()), e);
        }
    }

    private static long number(Object value, String where) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException(where + ": expected a whole number, got '" + value + "'");
    }

    private static String string(Object value, String where) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        throw new IllegalArgumentException(where + ": expected a text value, got " + (value == null ? "nothing" : "'" + value + "'"));
    }

    private static String optionalString(Map<String, Object> map, String key, String where) {
        return map.containsKey(key) ? string(map.get(key), where + "." + key) : null;
    }

    private static List<String> strings(Object value, String where) {
        var list   = list(value, where);
        var result = new ArrayList<String>();
        for (var i = 0; i < list.size(); i++) {
            result.add(string(list.get(i), where + "[" + i + "]"));
        }
        return result;
    }

    private static Object required(Map<String, Object> map, String key, String where) {
        var value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(where + ": '" + key + "' is required");
        }
        return value;
    }

    private static void keys(Map<String, Object> map, String where, String... allowed) {
        var unknown = new TreeSet<>(map.keySet());
        unknown.removeAll(List.of(allowed));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(where + ": unknown key(s) " + unknown + "; allowed: " + List.of(allowed));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            for (var key : map.keySet()) {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException(where + ": key '" + key + "' is not text");
                }
            }
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException(where + ": expected a mapping, got " + (value == null ? "nothing" : "'" + value + "'"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String where) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException(where + ": expected a list, got " + (value == null ? "nothing" : "'" + value + "'"));
    }
}
