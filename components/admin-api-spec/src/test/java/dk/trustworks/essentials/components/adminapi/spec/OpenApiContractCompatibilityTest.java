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
 * Backwards-compatibility gate for the major currently under development. The freshly generated contract is
 * diffed against {@link #BASELINE}. Removing/renaming an endpoint or field, tightening a type, or any other
 * breaking change within that major fails this test.
 * <p>
 * Additive changes (new endpoints, new optional fields) are compatible and pass; promote them into the baseline
 * at release time by copying {@code essentials-admin-api.yaml} over the baseline file.
 * <h2>Why the baseline is {@code v2}</h2>
 * 0.60 removes admin operations, which is breaking against the released {@code v1} contract by construction.
 * Leaving the gate pointed at {@code v1} would have left it failing for the whole release, and a permanently
 * red gate catches nothing — so it was re-seeded to {@code v2} once 0.60's first breaking change landed.
 * {@code baseline/essentials-admin-api-v1.yaml} is kept unchanged as the record of the last released contract.
 * <p>
 * A further deliberate breaking change during 0.60 therefore means re-seeding {@code v2} as a reviewed act, in
 * the same commit as the change. Do not re-seed it to silence a failure you did not intend.
 * <p>
 * Note that {@code EssentialsAdminApiSpec.BASE_PATH} and {@code CONTRACT_VERSION} still say {@code v1} /
 * {@code 1.0.0}. Bumping those changes the URL every consumer calls and is a release decision, not part of
 * moving this gate.
 */
class OpenApiContractCompatibilityTest {

    private static final Path BASELINE = Path.of("openapi", "baseline", "essentials-admin-api-v2.yaml");

    @Test
    void current_contract_is_backwards_compatible_with_the_baseline() throws IOException {
        assertThat(Files.exists(BASELINE))
                .as("Baseline %s is missing — seed it from the current contract.", BASELINE)
                .isTrue();

        String baseline = Files.readString(BASELINE, StandardCharsets.UTF_8);
        String current  = OpenApiSpecGenerator.generateYaml();

        ChangedOpenApi diff = OpenApiCompare.fromContents(baseline, current);

        assertThat(diff.isIncompatible())
                .as("The admin API contract introduces a BREAKING change versus the current baseline:%n%s%n"
                            + "Either restore compatibility, or — if the break is deliberate and part of this major — "
                            + "re-seed the baseline in the same commit by copying%n"
                            + "  components/admin-api-spec/openapi/essentials-admin-api.yaml%n"
                            + "over%n"
                            + "  components/admin-api-spec/openapi/baseline/essentials-admin-api-v2.yaml",
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
