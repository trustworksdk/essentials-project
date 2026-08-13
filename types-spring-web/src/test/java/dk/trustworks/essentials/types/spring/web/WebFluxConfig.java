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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.*;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Jackson <b>2</b> codec support for the {@code -Pjackson2} test runs, and nothing else.
 * <p>
 * This class is the reason the shipped {@link EssentialsWebFluxConfigurer} implements
 * {@code addFormatters} and nothing more. Overriding {@code configureHttpMessageCodecs} the way this class does
 * <em>replaces</em> the application's JSON codecs - on Spring Boot 4 that would swap Jackson 3 for Jackson 2 - so it
 * can only ever be a deliberate, flavour-specific choice made by the application. Shipping it would silently break
 * every Boot 4 consumer that merely wanted a typed {@code @PathVariable}.
 * <p>
 * Registering {@link SingleValueTypeConverter} is not this class's job either; that comes from
 * {@link EssentialsWebFluxConfigurer}.
 */
@Configuration
public class WebFluxConfig implements WebFluxConfigurer {
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("removal") // Jackson 2 codecs; this config only ever runs under -Pjackson2
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().jackson2JsonEncoder(
                new Jackson2JsonEncoder(objectMapper));
        configurer.defaultCodecs().jackson2JsonDecoder(
                new Jackson2JsonDecoder(objectMapper));
    }
}
