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

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.Jackson3JSONEventSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;

/**
 * What an application with only Jackson 3 on its classpath does with this starter. Loaded and run by
 * {@link Jackson3OnlyClasspathTest} through a class loader that cannot see Jackson 2.
 */
public final class Jackson3OnlyScenario {
    private Jackson3OnlyScenario() {
    }

    /** What Spring does with the auto-configuration classes this starter brings, before it creates any bean. */
    public static void introspectTheAutoConfiguration() {
        EssentialsComponentsConfiguration.class.getDeclaredMethods();
        EventStoreConfiguration.class.getDeclaredMethods();
    }

    /** The event serializer bean the starter defines by default, used for a value type. */
    public static String serializeWithTheDefaultSerializer() {
        var serializer = new EventStoreConfiguration().jsonSerializer();
        if (!(serializer instanceof Jackson3JSONEventSerializer)) {
            throw new IllegalStateException("Expected the Jackson 3 serializer, got " + serializer.getClass().getName());
        }
        return serializer.serialize(QueueName.of("orders"));
    }
}
