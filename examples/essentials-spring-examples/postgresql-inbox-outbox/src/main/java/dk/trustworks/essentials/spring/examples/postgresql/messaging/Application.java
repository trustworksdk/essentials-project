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

package dk.trustworks.essentials.spring.examples.postgresql.messaging;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the PostgreSQL/JPA flavour of the Inbox/Outbox example.
 *
 * <p>Almost everything is auto-configured: the Essentials Spring Boot starters supply the {@code DurableQueues},
 * {@code Inboxes}/{@code Outboxes}, the {@code DurableLocalCommandBus} and the {@code EventBus}, and
 * {@code ReactiveHandlersBeanPostProcessor} registers every {@code AnnotatedCommandHandler} and
 * {@code AnnotatedEventHandler} bean without an explicit wiring step.
 *
 * <p>This class also anchors the component scan, which is why {@code config/KafkaConfiguration} derives its Kafka
 * trusted-package prefix from this package rather than from a type inside a slice.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

}
