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

package dk.trustworks.essentials.examples.perflab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.examples.perflab.scenario.ScenarioRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(EssentialsPerformanceLabProperties.class)
public class EssentialsPerformanceLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(EssentialsPerformanceLabApplication.class, args);
    }

    /**
     * Jackson 2 {@link ObjectMapper} used by the scenarios to write their metrics JSON.
     * <p>
     * Spring Boot 4 auto-configures a Jackson 3 {@code tools.jackson.databind.ObjectMapper}, so the
     * Jackson 2 mapper the scenarios inject is no longer contributed by {@code spring-boot-starter-json}
     * and is declared here instead.
     */
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new Jdk8Module())
                                 .registerModule(new JavaTimeModule())
                                 .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    CommandLineRunner run(ScenarioRunner scenarioRunner) {
        return args -> scenarioRunner.run();
    }
}
