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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.convert.ApplicationConversionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CdcProperties} lives in {@code postgresql-event-store}, so {@code spring-boot-configuration-processor} only
 * sees it because {@link EssentialsEventStoreProperties#getCdc()} is a {@code @NestedConfigurationProperty}, and it has
 * no source to take descriptions or defaults from: those come from
 * {@code additional-spring-configuration-metadata.json}. This pins both halves, and that the hand-written defaults
 * have not drifted from the real ones.
 */
class CdcConfigurationMetadataTest {
    private static final String PREFIX = "essentials.eventstore.cdc.";

    @Test
    void every_bound_cdc_property_has_metadata_with_description_and_matching_default() throws Exception {
        var metadata = new HashMap<String, JsonNode>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
            assertThat(in).as("generated META-INF/spring-configuration-metadata.json").isNotNull();
            for (var property : JsonMapper.builder().build().readTree(in.readAllBytes()).get("properties")) {
                metadata.put(property.get("name").asString(), property);
            }
        }

        var expected = new TreeMap<String, Object>();
        collectLeaves(new BeanWrapperImpl(new CdcProperties()), PREFIX, expected);
        assertThat(expected).as("leaf properties found on CdcProperties").hasSizeGreaterThan(40);

        var conversion = ApplicationConversionService.getSharedInstance();
        for (var entry : expected.entrySet()) {
            var name = entry.getKey();
            var property = metadata.get(name);
            assertThat(property).as("metadata for %s", name).isNotNull();
            assertThat(property.path("description").asString("")).as("description of %s", name).isNotBlank();
            var actualDefault = entry.getValue();
            if (actualDefault != null) {
                assertThat(property.has("defaultValue")).as("defaultValue of %s", name).isTrue();
                var declared = property.get("defaultValue").asString();
                assertThat(conversion.convert(declared, actualDefault.getClass()))
                        .as("defaultValue of %s", name)
                        .isEqualTo(actualDefault);
            }
        }
    }

    private static void collectLeaves(BeanWrapperImpl wrapper, String prefix, Map<String, Object> into) {
        for (PropertyDescriptor pd : wrapper.getPropertyDescriptors()) {
            if (pd.getReadMethod() == null || pd.getName().equals("class")) {
                continue;
            }
            var type = pd.getPropertyType();
            var key = prefix + kebab(pd.getName());
            var value = wrapper.getPropertyValue(pd.getName());
            if (type.getEnclosingClass() != null && !type.isEnum()) {
                collectLeaves(new BeanWrapperImpl(value), key + ".", into);
            } else if (pd.getWriteMethod() != null) {
                into.put(key, value);
            }
        }
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
