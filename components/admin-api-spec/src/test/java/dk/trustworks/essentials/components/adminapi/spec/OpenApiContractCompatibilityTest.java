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

import org.junit.jupiter.api.Test;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backwards-compatibility gate for the current major. The freshly generated contract is diffed against the
 * checked-in baseline (the last released {@code v1} contract). Removing/renaming an endpoint or field, tightening
 * a type, or any other breaking change within the same major fails this test.
 * <p>
 * Additive changes (new endpoints, new optional fields) are compatible and pass; promote them into the baseline
 * at release time by copying {@code essentials-admin-api.yaml} over {@code baseline/essentials-admin-api-v1.yaml}.
 * A genuinely breaking change must instead be introduced under a new {@code /v2} contract served side-by-side.
 */
class OpenApiContractCompatibilityTest {

    private static final Path BASELINE = Path.of("openapi", "baseline", "essentials-admin-api-v1.yaml");

    @Test
    void current_contract_is_backwards_compatible_with_the_released_baseline() throws IOException {
        assertThat(Files.exists(BASELINE))
                .as("Baseline %s is missing — seed it from the released contract.", BASELINE)
                .isTrue();

        String baseline = Files.readString(BASELINE, StandardCharsets.UTF_8);
        String current  = OpenApiSpecGenerator.generateYaml();

        ChangedOpenApi diff = OpenApiCompare.fromContents(baseline, current);

        assertThat(diff.isIncompatible())
                .as("The admin API contract introduces a BREAKING change versus the released v1 baseline:%n%s%n"
                            + "Either restore compatibility, or introduce the change under a new /v2 contract. "
                            + "If this change is additive and intended for the next release, promote it by copying%n"
                            + "  components/admin-api-spec/openapi/essentials-admin-api.yaml%n"
                            + "over%n"
                            + "  components/admin-api-spec/openapi/baseline/essentials-admin-api-v1.yaml",
                    describeBreakingChanges(diff))
                .isFalse();
    }

    private static String describeBreakingChanges(ChangedOpenApi diff) {
        var parts = new java.util.ArrayList<String>();
        if (!diff.getMissingEndpoints().isEmpty()) {
            parts.add("  removed endpoints: " + diff.getMissingEndpoints().stream()
                                                    .map(e -> e.getMethod() + " " + e.getPathUrl())
                                                    .collect(Collectors.joining(", ")));
        }
        var incompatibleOps = diff.getChangedOperations().stream()
                                  .filter(Changed::isIncompatible)
                                  .map(op -> op.getHttpMethod() + " " + op.getPathUrl())
                                  .collect(Collectors.joining(", "));
        if (!incompatibleOps.isEmpty()) {
            parts.add("  incompatible operations: " + incompatibleOps);
        }
        var incompatibleSchemas = diff.getChangedSchemas().stream()
                                      .filter(Changed::isIncompatible)
                                      .map(ChangedSchema::getNewSchema)
                                      .filter(java.util.Objects::nonNull)
                                      .map(s -> String.valueOf(s.getName()))
                                      .collect(Collectors.joining(", "));
        if (!incompatibleSchemas.isEmpty()) {
            parts.add("  incompatible schemas: " + incompatibleSchemas);
        }
        return parts.isEmpty() ? "  (see openapi-diff output)" : String.join("\n", parts);
    }
}
