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

package dk.trustworks.essentials.spring.examples.mongodb.messaging.config;

import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Lets a controller take a semantic type directly as a {@code @PathVariable}, e.g.
 * {@code getOrderStatus(@PathVariable OrderId orderId)} rather than a {@code String} the endpoint has to wrap
 * itself.
 * <p>
 * The {@code types-spring-web} dependency alone does nothing - {@link EssentialsWebMvcConfigurer} is a plain
 * {@code @Configuration}, not auto-configuration, so it has to be imported. It registers a converter with the
 * {@code FormatterRegistry} and touches nothing else; in particular it leaves the HTTP message converters alone,
 * so it has no bearing on which Jackson major serialises request and response bodies.
 * <p>
 * This is module-level infrastructure and deliberately sits outside the bounded context.
 * <p>
 * Note the boundary it does <em>not</em> cross: the Kafka DTOs in
 * {@code shipping/external_systems/order_management} still carry a plain {@code String} id. That is the
 * anti-corruption boundary doing its job, and it is unrelated to HTTP binding.
 */
@Configuration
@Import(EssentialsWebMvcConfigurer.class)
public class WebConfiguration {
}
