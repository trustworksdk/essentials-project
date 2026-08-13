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

import dk.trustworks.essentials.types.spring.web.controllers.WebMvcController;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Spring Boot 4 / Jackson 3 test application, wired the way a consumer is meant to wire one: import the
 * <em>shipped</em> {@link EssentialsWebMvcConfigurer} rather than hand-rolling a {@code WebMvcConfigurer}.
 * <p>
 * There is deliberately no local configurer class here any more. One used to exist and the module's documentation
 * described it as something {@code types-spring-web} shipped, which it was not - it was test scope, invisible to
 * consumers. Keeping the test on the shipped class is what stops that drifting apart again.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({EssentialsWebMvcConfigurer.class, WebMvcController.class})
class WebMvcJackson3SpringWebApplication {
}
