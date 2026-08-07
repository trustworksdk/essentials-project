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

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation gate: the generated contract must parse cleanly, with every {@code $ref} resolvable and no structural
 * complaints. This is a JVM-only replacement for the previous {@code redocly lint} step — the project deliberately
 * carries no Node or JavaScript build dependencies.
 */
class OpenApiSpecValidationTest {

    @Test
    void the_generated_contract_parses_without_validation_messages() {
        var options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);
        options.setValidateInternalRefs(true);

        var result = new OpenAPIV3Parser().readContents(OpenApiSpecGenerator.generateYaml(), null, options);

        assertThat(result.getMessages())
                .as("The generated contract has validation problems")
                .isEmpty();
        assertThat(result.getOpenAPI()).isNotNull();
    }

    @Test
    void every_schema_reference_resolves_to_a_declared_schema() {
        var yaml    = OpenApiSpecGenerator.generateYaml();
        var schemas = OpenApiSpecGenerator.buildOpenApi().getComponents().getSchemas().keySet();

        yaml.lines()
            .filter(line -> line.contains("#/components/schemas/"))
            .map(line -> line.substring(line.indexOf("#/components/schemas/") + "#/components/schemas/".length())
                             .replace("\"", "")
                             .trim())
            .forEach(referenced -> assertThat(schemas)
                    .as("dangling schema reference: %s", referenced)
                    .contains(referenced));
    }
}
