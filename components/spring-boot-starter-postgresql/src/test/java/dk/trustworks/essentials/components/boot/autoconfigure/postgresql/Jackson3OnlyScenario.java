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

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;

/**
 * What an application with only Jackson 3 on its classpath does with this starter. Loaded and run by
 * {@link Jackson3OnlyClasspathTest} through a class loader that cannot see Jackson 2, so every class touched here links
 * against a Jackson 3-only classpath - which a plain unit test, with both majors on the test classpath, cannot show.
 */
public final class Jackson3OnlyScenario {
    private Jackson3OnlyScenario() {
    }

    /** What Spring does with an auto-configuration class before it creates any bean from it. */
    public static void introspectTheAutoConfiguration() {
        EssentialsComponentsConfiguration.class.getDeclaredMethods();
        EssentialsComponentsConfiguration.Jackson3OnlyJsonSerializerConfiguration.class.getDeclaredMethods();
    }

    /** The serializer bean the starter defines when Jackson 3 is the only Jackson, used for a value type. */
    public static String serializeWithTheDefaultSerializer() {
        var serializer = new EssentialsComponentsConfiguration.Jackson3OnlyJsonSerializerConfiguration().jsonSerializer();
        if (!(serializer instanceof Jackson3JSONSerializer)) {
            throw new IllegalStateException("Expected the Jackson 3 serializer, got " + serializer.getClass().getName());
        }
        return serializer.serialize(QueueName.of("orders"));
    }

    /** The notification listener the starter wires with that serializer. Creating it does not connect. */
    public static void createTheMultiTableChangeListener() {
        new MultiTableChangeListener<>(Jdbi.create("jdbc:postgresql://localhost:1/never-connected"),
                                       Duration.ofSeconds(1),
                                       EssentialsObjectMappers.createJSONSerializer(),
                                       LocalEventBus.builder().busName("jackson3-only").build(),
                                       true);
    }
}
