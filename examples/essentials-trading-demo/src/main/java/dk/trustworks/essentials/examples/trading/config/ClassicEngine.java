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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.*;

/**
 * A bean that exists only on the classic event store engine: {@code trading-demo.engine=classic}, the default
 * (absent counts as classic). Carried by every processor built on the old processor classes and by the classic
 * wrapper of a ported processor; its twin on the new engine carries {@link EventLogEngine}. See this package's
 * {@code CLAUDE.md}, "One handler body, two engine wrappers".
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "trading-demo.engine", havingValue = "classic", matchIfMissing = true)
public @interface ClassicEngine {
}
