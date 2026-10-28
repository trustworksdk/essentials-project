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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql;

import dk.trustworks.essentials.components.foundation.boot.EssentialsStartupChecksAutoConfiguration;
import dk.trustworks.essentials.components.foundation.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter's startup check of persistence {@link JSONSerializer} beans ({@code essentials.json.wire-format-check}).
 * No database: the check runs as the serializer bean is created, so the shared check configuration and a serializer
 * bean are all it needs.
 */
@ExtendWith(OutputCaptureExtension.class)
class WireFormatCheckTest {

    private static final String BANNER_HEADLINE = "Essentials wire-format check: the JSON serializer bean 'jsonSerializer'";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(EssentialsStartupChecksAutoConfiguration.class));

    @Test
    void the_starter_registers_the_shared_startup_checks() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(EssentialsComponentsConfiguration.class.getName(), EssentialsStartupChecksAutoConfiguration.class.getName());
    }

    @Test
    void the_serializer_essentials_builds_passes_silently(CapturedOutput output) {
        contextRunner.withBean("jsonSerializer", JSONSerializer.class, EssentialsObjectMappers::createJSONSerializer)
                     .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(output).doesNotContain(BANNER_HEADLINE);
    }

    @Test
    void a_serializer_on_a_hand_built_mapper_logs_an_error_banner_naming_each_deviation_and_starts(CapturedOutput output) {
        contextRunner.withBean("jsonSerializer", JSONSerializer.class, () -> new Jackson3JSONSerializer(JsonMapper.builder().build()))
                     .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(output).contains("ERROR", BANNER_HEADLINE)
                          .contains("Getter visibility", "is-getter visibility", "Duration format", "FAIL_ON_NULL_FOR_PRIMITIVES",
                                    "Essentials value type as a JSON primitive")
                          .contains("EssentialsObjectMappers.createJackson3ObjectMapper");
    }

    @Test
    void fail_mode_stops_the_application(CapturedOutput output) {
        contextRunner.withPropertyValues("essentials.json.wire-format-check=fail")
                     .withBean("jsonSerializer", JSONSerializer.class, () -> new Jackson3JSONSerializer(JsonMapper.builder().build()))
                     .run(ctx -> assertThat(ctx).getFailure()
                                                .rootCause()
                                                .isInstanceOf(IllegalStateException.class)
                                                .hasMessageContaining(BANNER_HEADLINE));
    }

    @Test
    void off_mode_skips_the_check(CapturedOutput output) {
        contextRunner.withPropertyValues("essentials.json.wire-format-check=off")
                     .withBean("jsonSerializer", JSONSerializer.class, () -> new Jackson3JSONSerializer(JsonMapper.builder().build()))
                     .run(ctx -> assertThat(ctx).hasNotFailed());

        assertThat(output).doesNotContain(BANNER_HEADLINE);
    }

    @Test
    void a_serializer_essentials_built_with_extra_modules_passes() {
        contextRunner.withPropertyValues("essentials.json.wire-format-check=fail")
                     .withBean("jsonSerializer", JSONSerializer.class,
                               () -> new Jackson3JSONSerializer(EssentialsObjectMappers.createJackson3ObjectMapper(new tools.jackson.databind.module.SimpleModule("app-module"))))
                     .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
