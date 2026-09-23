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

package dk.trustworks.essentials.components.boot.autoconfigure.mongodb;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a Spring Boot DevTools restart, application classes come from a new class loader, and the {@code JSONSerializer}
 * has to resolve payload types through it - otherwise it deserializes into the previous generation's classes, the
 * classic "cannot cast X to X". The listener used to update only the Jackson 2 {@code JacksonJSONSerializer}, so with
 * the default Jackson 3 serializer it did nothing.
 */
class DevToolsClassLoaderListenerTest {

    @Test
    void the_serializer_follows_the_restarted_contexts_class_loader() {
        var serializer         = EssentialsObjectMappers.createJSONSerializer();
        var listener           = new EssentialsComponentsConfiguration().contextRefreshedListener(serializer);
        var restartClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        var restartedContext = new GenericApplicationContext();
        restartedContext.setClassLoader(restartClassLoader);

        listener.handleContextRefresh(new ContextRefreshedEvent(restartedContext));

        assertThat(serializer.getClassLoader()).isSameAs(restartClassLoader);
    }
}
