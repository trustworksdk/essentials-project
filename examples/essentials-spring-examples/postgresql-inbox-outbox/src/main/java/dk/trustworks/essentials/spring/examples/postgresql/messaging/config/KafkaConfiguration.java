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

import dk.trustworks.essentials.spring.examples.postgresql.messaging.Application;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Module-level Kafka wiring: the producer, consumer and listener-container factories that the {@code shipping}
 * bounded context's translation slice uses in both directions.
 *
 * <p>This is application infrastructure and deliberately sits outside the bounded context. It knows that Kafka
 * payloads live somewhere under the {@code shipping} package, but not which slice owns them.
 */
@Configuration
@EnableKafka
public class KafkaConfiguration {
    /**
     * Kafka payload types all live under the {@code shipping} bounded context. Anchoring on the application's own
     * package rather than on a type inside a slice keeps this module-level wiring from reaching into slice internals.
     */
    private static final String SHIPPING_TRUSTED_PACKAGES = Application.class.getPackageName() + ".shipping.*";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * The Jackson 3 mapper Spring Boot 4 auto-configures. Spring for Apache Kafka 4 deprecated its Jackson 2
     * {@code JsonSerializer}/{@code JsonDeserializer} for removal in favour of the {@code JacksonJson*} pair below,
     * which bind against Jackson 3.
     */
    @Autowired
    JsonMapper jsonMapper;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                                            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                                            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(config,
                                                 new StringSerializer(),
                                                 new JacksonJsonSerializer<>(jsonMapper));
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(config,
                                                 new StringDeserializer(),
                                                 new JacksonJsonDeserializer<>(jsonMapper)
                                                         .trustedPackages(SHIPPING_TRUSTED_PACKAGES));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        var kafkaTemplate = new KafkaTemplate<>(producerFactory());
        kafkaTemplate.setObservationEnabled(true);
        return kafkaTemplate;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
