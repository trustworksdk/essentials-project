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

import dk.trustworks.essentials.types.SingleValueType;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Registers the {@link SingleValueType} converters with Spring WebFlux, so that a semantic type may be used directly
 * as a {@code @PathVariable} or {@code @RequestParam}:
 * <pre>{@code
 * @GetMapping("/orders/{orderId}")
 * public Mono<Order> getOrder(@PathVariable OrderId orderId) { ... }
 * }</pre>
 * <p>
 * <b>This is not auto-configuration.</b> Declaring the {@code types-spring-web} dependency changes nothing on its own;
 * import this class explicitly with {@code @Import(EssentialsWebFluxConfigurer.class)}.
 * <p>
 * <b>It deliberately only implements {@link #addFormatters(FormatterRegistry)}.</b> In particular it does <em>not</em>
 * override {@code configureHttpMessageCodecs}, so it cannot displace the codecs the application already has - which on
 * Spring Boot 4 are Jackson 3. Request and response <em>bodies</em> are a separate concern, handled by
 * {@code EssentialTypesJacksonModule} from {@code types-jackson}/{@code types-jackson3} registered on the codecs'
 * {@code ObjectMapper}.
 *
 * @see EssentialsWebMvcConfigurer the WebMvc equivalent
 * @see SingleValueTypeConverter the Java {@link SingleValueType} hierarchy
 */
@Configuration
public class EssentialsWebFluxConfigurer implements WebFluxConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
        KotlinValueTypeConverterRegistrar.addTo(registry);
    }
}
