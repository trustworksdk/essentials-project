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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json;

import dk.trustworks.essentials.components.foundation.json.EssentialsJacksonModules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the credibility of "the suite passes under both profiles".
 * <p>
 * Tests that hard-code {@code new JacksonJSONEventSerializer(new ObjectMapper())} pass under {@code -Pjackson3} while
 * exercising only the Jackson 2 path — green, and proving nothing. This test fails instead of passing quietly if the
 * flavor-neutral factory ever stops tracking the flavor actually on the classpath.
 * <p>
 * The expectation is derived independently of {@link EssentialsJacksonModules}: it inspects which Jackson major the
 * Essentials module on the classpath was compiled against, rather than asking the same helper the factory asks.
 */
class ActiveJacksonFlavorTest {

    @Test
    void the_factory_returns_the_serializer_for_the_jackson_flavor_actually_on_the_classpath() throws Exception {
        var moduleSuperclass = Class.forName(EssentialsJacksonModules.TYPES_MODULE_CLASS_NAME)
                                    .getSuperclass()
                                    .getName();
        var classpathIsJackson3 = moduleSuperclass.startsWith("tools.jackson.");

        var serializer = EssentialsJSONEventSerializers.createForActiveJacksonFlavor();

        if (classpathIsJackson3) {
            assertThat(serializer)
                    .as("Essentials module on the classpath is Jackson 3 (%s), so CDC and the event store must use the "
                                + "Jackson 3 serializer", moduleSuperclass)
                    .isInstanceOf(Jackson3JSONEventSerializer.class);
        } else {
            assertThat(serializer)
                    .as("Essentials module on the classpath is Jackson 2 (%s), so the Jackson 2 serializer must be used",
                        moduleSuperclass)
                    .isInstanceOf(JacksonJSONEventSerializer.class);
        }
    }

    /** {@link EssentialsJacksonModules#isJackson3Flavor()} must agree with the classpath, not merely with itself. */
    @Test
    void the_flavor_detection_agrees_with_the_classpath() throws Exception {
        var moduleSuperclass = Class.forName(EssentialsJacksonModules.TYPES_MODULE_CLASS_NAME)
                                    .getSuperclass()
                                    .getName();

        assertThat(EssentialsJacksonModules.isJackson3Flavor())
                .as("flavor detection vs the Essentials module's actual Jackson major (%s)", moduleSuperclass)
                .isEqualTo(moduleSuperclass.startsWith("tools.jackson."));
    }
}
