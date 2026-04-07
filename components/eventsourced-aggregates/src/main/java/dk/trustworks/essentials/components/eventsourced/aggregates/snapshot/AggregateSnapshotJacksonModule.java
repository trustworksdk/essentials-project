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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregate;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.classic.state.AggregateState;

import java.util.List;
import java.util.Set;

/**
 * This class is a custom Jackson module designed for serializing and deserializing aggregates
 * by omitting certain framework-specific bookkeeping properties. These properties, which are
 * typically internal to the aggregate's runtime behavior, are excluded from the serialized output
 * to provide a cleaner representation of the aggregate's state.
 * <p>
 * The module utilizes a {@link BeanSerializerModifier} to filter out properties
 * that are identified as framework bookkeeping fields during the serialization process.
 */
final class AggregateSnapshotJacksonModule extends SimpleModule {
    private static final Set<String> FRAMEWORK_BOOKKEEPING_PROPERTIES = Set.of("invoker",
                                                                               "uncommittedChanges",
                                                                               "uncommittedEvents",
                                                                               "hasBeenRehydrated",
                                                                               "beenRehydrated",
                                                                               "rehydrating");

    AggregateSnapshotJacksonModule() {
        setSerializerModifier(new AggregateSnapshotSerializerModifier());
    }

    private static final class AggregateSnapshotSerializerModifier extends BeanSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                         BeanDescription beanDesc,
                                                         List<BeanPropertyWriter> beanProperties) {
            if (!isFrameworkAggregateType(beanDesc.getBeanClass())) {
                return beanProperties;
            }
            return beanProperties.stream()
                                 .filter(property -> !FRAMEWORK_BOOKKEEPING_PROPERTIES.contains(property.getName()))
                                 .toList();
        }

        private boolean isFrameworkAggregateType(Class<?> type) {
            return StatefulAggregate.class.isAssignableFrom(type) ||
                    AggregateState.class.isAssignableFrom(type);
        }
    }
}
