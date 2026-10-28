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

package dk.trustworks.essentials.components.foundation.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.introspect.VisibilityChecker;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EssentialsObjectMappers#findWireFormatDeviations(ObjectMapper)} must pass every mapper Essentials builds and
 * name each way a hand-built one differs. Each single-setting test starts from the Essentials configuration and flips
 * exactly one setting, so it proves that its probe, and only its probe, catches that drift.
 * <p>
 * The value-type probe is not exercised here: {@code types-jackson3} may not be on this module's classpath (see
 * {@code foundation/CLAUDE.md}); the Spring Boot starters' tests cover it.
 */
class EssentialsObjectMappersWireFormatDeviationsTest {

    @Test
    void a_mapper_essentials_built_has_no_deviations() {
        assertThat(EssentialsObjectMappers.findWireFormatDeviations(EssentialsObjectMappers.createJackson3ObjectMapper())).isEmpty();
    }

    @Test
    void a_default_jackson_3_mapper_reports_each_way_it_differs() {
        var deviations = EssentialsObjectMappers.findWireFormatDeviations(JsonMapper.builder().build());

        assertThat(deviations).extracting(WireFormatDeviation::aspect)
                              .contains("Field access",
                                        "Getter visibility",
                                        "is-getter visibility",
                                        "Duration format (WRITE_DURATIONS_AS_TIMESTAMPS enabled)",
                                        "A missing primitive reads as its default (FAIL_ON_NULL_FOR_PRIMITIVES disabled)",
                                        "An untyped floating-point number reads as BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS enabled)");
    }

    @Test
    void getters_detected_as_properties_are_reported() {
        assertSingleDeviation(builder -> builder.changeDefaultVisibility(visibility -> essentialsVisibility().withGetterVisibility(Visibility.PUBLIC_ONLY)),
                              "Getter visibility",
                              "{\"derived\":\"derived\",\"id\":\"probe-1\"}");
    }

    @Test
    void is_getters_detected_as_properties_are_reported() {
        assertSingleDeviation(builder -> builder.changeDefaultVisibility(visibility -> essentialsVisibility().withIsGetterVisibility(Visibility.PUBLIC_ONLY)),
                              "is-getter visibility",
                              "{\"express\":true,\"isExpress\":true}");
    }

    @Test
    void dates_written_as_timestamps_are_reported() {
        assertSingleDeviation(builder -> builder.enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS),
                              "Date/time format (WRITE_DATES_AS_TIMESTAMPS disabled)",
                              null);
    }

    @Test
    void durations_written_as_iso_8601_are_reported() {
        assertSingleDeviation(builder -> builder.disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS),
                              "Duration format (WRITE_DURATIONS_AS_TIMESTAMPS enabled)",
                              "{\"delay\":\"PT30S\"}");
    }

    @Test
    void failing_on_a_missing_primitive_is_reported() {
        assertSingleDeviation(builder -> builder.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES),
                              "A missing primitive reads as its default (FAIL_ON_NULL_FOR_PRIMITIVES disabled)",
                              null);
    }

    @Test
    void failing_on_an_unknown_property_is_reported() {
        assertSingleDeviation(builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                              "An unknown property is ignored (FAIL_ON_UNKNOWN_PROPERTIES disabled)",
                              null);
    }

    @Test
    void untyped_floats_read_as_double_are_reported() {
        assertSingleDeviation(builder -> builder.disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                              "An untyped floating-point number reads as BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS enabled)",
                              "java.lang.Double 12.75");
    }

    @Test
    void private_fields_not_detected_are_reported() {
        assertSingleDeviation(builder -> builder.changeDefaultVisibility(visibility -> essentialsVisibility().withFieldVisibility(Visibility.PUBLIC_ONLY)),
                              "Field access",
                              null);
    }

    /**
     * @param actual the exact output expected from the drifted mapper, or {@code null} where it is an exception message
     *               whose wording belongs to Jackson
     */
    private static void assertSingleDeviation(UnaryOperator<JsonMapper.Builder> drift, String aspect, String actual) {
        var mapper     = drift.apply(((JsonMapper) EssentialsObjectMappers.createJackson3ObjectMapper()).rebuild()).build();
        var deviations = EssentialsObjectMappers.findWireFormatDeviations(mapper);

        assertThat(deviations).extracting(WireFormatDeviation::aspect).containsExactly(aspect);
        if (actual != null) {
            assertThat(deviations.getFirst().actual()).isEqualTo(actual);
        }
    }

    private static VisibilityChecker essentialsVisibility() {
        return VisibilityChecker.defaultInstance()
                                .withGetterVisibility(Visibility.NONE)
                                .withIsGetterVisibility(Visibility.NONE)
                                .withSetterVisibility(Visibility.NONE)
                                .withFieldVisibility(Visibility.ANY)
                                .withCreatorVisibility(Visibility.ANY);
    }
}
