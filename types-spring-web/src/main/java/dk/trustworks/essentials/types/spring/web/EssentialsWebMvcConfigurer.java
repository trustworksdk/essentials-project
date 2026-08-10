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
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link SingleValueType} converters with Spring WebMvc, so that a semantic type may be used directly
 * as a {@code @PathVariable} or {@code @RequestParam}:
 * <pre>{@code
 * @GetMapping("/orders/{orderId}")
 * public Order getOrder(@PathVariable OrderId orderId) { ... }
 * }</pre>
 * <p>
 * <b>This is not auto-configuration.</b> Declaring the {@code types-spring-web} dependency changes nothing on its own;
 * import this class explicitly:
 * <pre>{@code
 * @SpringBootApplication
 * @Import(EssentialsWebMvcConfigurer.class)
 * public class Application { ... }
 * }</pre>
 * <p>
 * <b>It deliberately only implements {@link #addFormatters(FormatterRegistry)}.</b> Nothing here touches the HTTP
 * message converters, so it cannot interfere with whichever Jackson major the application serialises request and
 * response <em>bodies</em> with. Bodies are a separate concern handled by {@code EssentialTypesJacksonModule} from
 * {@code types-jackson}/{@code types-jackson3} - registered on the <em>web</em> {@code ObjectMapper}, which no
 * Essentials starter does for you.
 *
 * @see EssentialsWebFluxConfigurer the WebFlux equivalent
 * @see SingleValueTypeConverter the Java {@link SingleValueType} hierarchy
 */
@Configuration
public class EssentialsWebMvcConfigurer implements WebMvcConfigurer {
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new SingleValueTypeConverter());
        KotlinValueTypeConverterRegistrar.addTo(registry);
    }
}
