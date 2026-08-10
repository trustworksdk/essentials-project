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

package dk.trustworks.essentials.spring.examples.postgresql.messaging.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Pulls the Essentials JPA attribute converters into the context.
 *
 * <p>{@code types-springdata-jpa} ships ready-made {@code AttributeConverter}s for the {@code SingleValueType}
 * families -- {@code CharSequenceType}, the numeric types, the JSR-310 types -- but they live in a package outside
 * this application's own scan root, so Hibernate would never see them. Scanning that package registers them all,
 * including the {@code autoApply} ones.
 */
@Configuration
@ComponentScan("dk.trustworks.essentials.types.springdata.jpa.converters")
public class JpaConfig {
}
