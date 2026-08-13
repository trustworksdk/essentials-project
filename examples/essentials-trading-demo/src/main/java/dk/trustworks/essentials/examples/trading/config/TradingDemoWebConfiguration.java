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

package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Makes the demo's semantic types usable at the HTTP boundary, so an endpoint can declare
 * {@code @PathVariable TradingAccountId} and {@code @RequestBody PlaceTrade} instead of taking a
 * {@code String} and converting it by hand.
 * <p>
 * Two registrations are needed, and neither happens on its own — this is module-level infrastructure and
 * deliberately sits outside both bounded contexts.
 * <ul>
 *     <li><b>Path variables and request params</b> — {@link EssentialsWebMvcConfigurer} registers
 *     {@code SingleValueTypeConverter} with the {@code FormatterRegistry}. The {@code types-spring-web}
 *     dependency alone does nothing: the configurer is a plain {@code @Configuration}, not
 *     auto-configuration, so it has to be imported. It was on this module's classpath and unimported
 *     until now, which is why every controller used to take a {@code String} — a typed
 *     {@code @PathVariable} without it fails as an <em>HTTP 500</em>, not a 400.</li>
 *     <li><b>Request and response bodies</b> — the converter above is not consulted for JSON, so the web
 *     {@code ObjectMapper} needs {@link EssentialTypesJacksonModule} as well. The Essentials starters
 *     configure the <em>persistence</em> mapper only; nothing registers this module on the web one.
 *     Without it a {@code CharSequenceType} has no serializer ({@code CharSequenceType} carries no
 *     {@code @JsonValue}) and a command body carrying one cannot round-trip.</li>
 * </ul>
 * The module bean is flavour-neutral on purpose: {@code types-jackson} and {@code types-jackson3} ship the
 * same FQCN and both extend {@code SimpleModule}, so this compiles and binds under the Jackson 3 default
 * and under {@code -Pjackson2}. Only one of the two is ever on the classpath.
 */
@Configuration
@Import(EssentialsWebMvcConfigurer.class)
public class TradingDemoWebConfiguration {
    @Bean
    public EssentialTypesJacksonModule essentialTypesJacksonModule() {
        return new EssentialTypesJacksonModule();
    }
}
