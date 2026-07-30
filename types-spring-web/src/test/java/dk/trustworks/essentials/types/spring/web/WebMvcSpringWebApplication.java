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

package dk.trustworks.essentials.types.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
class WebMvcSpringWebApplication {

    @Bean
    public ObjectMapper objectMapper() {
        try {
            var moduleClass = Class.forName("dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule");
            var factoryMethod = moduleClass.getMethod("createObjectMapper", com.fasterxml.jackson.databind.Module[].class);
            var mapper = factoryMethod.invoke(null, (Object) new com.fasterxml.jackson.databind.Module[]{
                    new Jdk8Module(),
                    new JavaTimeModule()
            });
            if (mapper instanceof ObjectMapper objectMapper) {
                return objectMapper;
            }

            return new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't instantiate Essentials Jackson ObjectMapper", e);
        }
    }

}
