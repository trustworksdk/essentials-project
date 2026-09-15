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

package dk.trustworks.essentials.spring.examples.mongodb.messaging;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static dk.trustworks.essentials.spring.examples.mongodb.messaging.ExampleTestImages.*;

/**
 * Shared container and context setup for this module's integration tests. Kafka is started even for tests that do not
 * use it, because {@code KafkaConfiguration} needs {@code spring.kafka.bootstrap-servers} to build the application
 * context at all.
 */
@SpringBootTest(classes = TestApplication.class)
@Testcontainers
@DirtiesContext
public abstract class AbstractIntegrationTest {
    @Container
    protected static MongoDBContainer mongoDBContainer = new MongoDBContainer(MONGO_IMAGE);

    @Container
    protected static org.testcontainers.kafka.KafkaContainer kafkaContainer = new org.testcontainers.kafka.KafkaContainer(KAFKA_IMAGE)
            .withStartupAttempts(2);

    @DynamicPropertySource
    protected static void setProperties(DynamicPropertyRegistry registry) {
        // Spring Boot 4 moved the MongoDB *connection* properties from spring.data.mongodb.* to spring.mongodb.*
        registry.add("spring.mongodb.uri", mongoDBContainer::getReplicaSetUrl);

        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }

    @Autowired
    protected CommandBus commandBus;
}
