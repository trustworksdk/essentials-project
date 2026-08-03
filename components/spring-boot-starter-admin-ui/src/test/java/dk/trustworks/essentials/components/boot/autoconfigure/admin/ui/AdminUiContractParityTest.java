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

package dk.trustworks.essentials.components.boot.autoconfigure.admin.ui;

import org.junit.jupiter.api.*;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity gate between the UI and the contract, in both directions.
 * <p>
 * The UI's whole reason for existing is to demonstrate that the published contract is sufficient to build
 * an admin console on. That claim decays silently: an endpoint gets added and nothing surfaces it, or the
 * UI starts calling a path that was renamed. Both are build failures here.
 * <p>
 * Paths are compared as templates. The UI interpolates real values, so
 * {@code /durable-queues/messages/${id}} is normalised back to the contract's
 * {@code /durable-queues/messages/{queueEntryId}} shape by replacing every path segment that contains an
 * expression with a placeholder, then comparing structure rather than literal text.
 */
class AdminUiContractParityTest {

    private static final String CONTRACT_RESOURCE = "/openapi/essentials-admin-api.yaml";
    private static final String ADMIN_JS          = "/static/essentials-admin/admin.js";

    /** Matches a quoted path passed to api(...), template expressions included. */
    private static final Pattern API_CALL = Pattern.compile("api\\(`([^`]+)`|api\\('([^']+)'");

    /**
     * Contract paths the default console deliberately does not surface.
     * <p>
     * The aggregate lifecycle and archive operations were added to the contract and the HTTP adapter without a
     * console view. They are read-only inspection endpoints for the snapshot, closing-books and archive subsystems,
     * and building views for them is tracked as separate work — recorded here rather than left to fail the gate,
     * which is the escape hatch {@link #every_contract_path_is_surfaced_by_the_ui} documents.
     * <p>
     * Deliberately enumerated rather than matched by prefix, so a *further* addition under the same prefixes still
     * fails the gate.
     */
    private static final Set<String> NOT_SURFACED_BY_THE_UI = Set.of(
            "/aggregate-lifecycle/snapshot-policies",
            "/aggregate-lifecycle/closing-books-policies",
            "/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations",
            "/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/current",
            "/aggregate-lifecycle/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/closing-books-generations/{generation}/event-stream",
            "/aggregate-lifecycle/aggregate-types/{aggregateType}/aggregates/{aggregateId}/snapshots",
            "/aggregate-lifecycle-statistics/snapshots",
            "/aggregate-lifecycle-statistics/closing-books",
            "/aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations",
            "/aggregate-archive/aggregate-types/{aggregateType}/logical-aggregates/{logicalAggregateId}/archived-generations/{generation}",
            "/aggregate-archive-statistics");

    private static Set<String> contractPaths;
    private static String      adminJs;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream contract = AdminUiContractParityTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(contract).as("contract must be on the test classpath at %s", CONTRACT_RESOURCE).isNotNull();
            var document = (Map<String, Object>) new Yaml().load(contract);
            contractPaths = new TreeSet<>(((Map<String, Object>) document.get("paths")).keySet());
        }
        try (InputStream js = AdminUiContractParityTest.class.getResourceAsStream(ADMIN_JS)) {
            assertThat(js).as("admin.js must be packaged at %s", ADMIN_JS).isNotNull();
            adminJs = new String(js.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Keeps the comparisons below from passing vacuously. */
    @Test
    void both_sides_are_discovered() {
        assertThat(contractPaths).hasSize(36);
        assertThat(calledPaths()).isNotEmpty();
    }

    /**
     * An exclusion that no longer names a real contract path is a stale exclusion, and would silently widen the
     * gate's blind spot as paths are renamed.
     */
    @Test
    void every_recorded_exclusion_still_names_a_contract_path() {
        assertThat(contractPaths).containsAll(NOT_SURFACED_BY_THE_UI);
    }

    @Test
    void every_path_the_ui_calls_is_declared_by_the_contract() {
        assertThat(contractPaths)
                .as("""
                    admin.js calls a path the contract does not declare. Either the call is wrong, or the \
                    contract needs regenerating from the SPIs.""")
                .containsAll(calledPaths());
    }

    @Test
    void every_contract_path_is_surfaced_by_the_ui() {
        var uncovered = new TreeSet<>(contractPaths);
        uncovered.removeAll(calledPaths());
        uncovered.removeAll(NOT_SURFACED_BY_THE_UI);

        assertThat(uncovered)
                .as("""
                    The contract declares paths the UI never calls, so the API has grown a capability the \
                    default console does not expose. Add it to a view, or record a deliberate exclusion here.""")
                .isEmpty();
    }

    /**
     * The UI must not reach the SPI beans directly — its only data source is the HTTP contract. A server-side
     * shortcut would create a second path to the same state that could drift from the contract.
     */
    @Test
    void the_ui_module_talks_only_to_the_api() {
        assertThat(adminJs).doesNotContain("DurableQueuesApi", "DBFencedLockApi", "EventStoreApi");
        assertThat(AdminUiController.class.getDeclaredFields())
                .as("the shell controller holds only properties and the authenticated user")
                .hasSize(3);
    }

    /** Normalises every {@code api(...)} call in the UI to the contract's path-template form. */
    private static SortedSet<String> calledPaths() {
        var paths   = new TreeSet<String>();
        var matcher = API_CALL.matcher(adminJs);
        while (matcher.find()) {
            var raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            paths.add(templatise(raw));
        }
        return paths;
    }

    private static String templatise(String rawPath) {
        var path = rawPath.split("\\?")[0];                         // drop the query string
        var segments = new ArrayList<String>();
        for (String segment : path.split("/", -1)) {
            // A segment carrying an interpolated value stands in for a contract path variable.
            segments.add(segment.contains("${") ? "{}" : segment);
        }
        var templated = String.join("/", segments);
        // Re-attach the contract's variable names positionally, so comparison is structural.
        for (String contractPath : contractPaths) {
            if (contractPath.replaceAll("\\{[^}]+}", "{}").equals(templated)) {
                return contractPath;
            }
        }
        return templated;
    }
}
