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

package dk.trustworks.essentials.examples.webshop.config

import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule
import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Makes the demo's semantic types usable at the HTTP boundary, so an endpoint can declare
 * `@PathVariable ProductId` and `@RequestBody AddProduct` instead of taking a `String` and converting it by hand.
 *
 * Two registrations are needed, and neither happens on its own - this is module-level infrastructure and
 * deliberately sits outside every bounded context.
 *
 * - **Path variables and request params** - [EssentialsWebMvcConfigurer] registers `SingleValueTypeConverter` with
 *   the `FormatterRegistry`. The `types-spring-web` dependency alone does nothing: the configurer is a plain
 *   `@Configuration`, not auto-configuration, so it has to be imported. A typed `@PathVariable` without it fails
 *   as an *HTTP 500*, not a 400.
 * - **Request and response bodies** - the converter above is not consulted for JSON, so the web `ObjectMapper`
 *   needs [EssentialTypesJacksonModule] as well. The Essentials starters configure the *persistence* mapper only.
 *
 * The module bean is flavour-neutral on purpose: `types-jackson` and `types-jackson3` ship the same FQCN and both
 * extend `SimpleModule`, so this compiles and binds under the Jackson 3 default and under `-Pjackson2`.
 */
@Configuration
@Import(EssentialsWebMvcConfigurer::class)
class WebshopDemoWebConfiguration {

    @Bean
    fun essentialTypesJacksonModule(): EssentialTypesJacksonModule = EssentialTypesJacksonModule()
}
