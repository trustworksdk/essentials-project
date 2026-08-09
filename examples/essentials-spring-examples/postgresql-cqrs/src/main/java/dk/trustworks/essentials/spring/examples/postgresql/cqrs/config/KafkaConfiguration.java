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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.config;

import dk.trustworks.essentials.shared.concurrent.ThreadFactoryBuilder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.incoming.OrderEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.external_systems.order_management.outgoing.ExternalOrderShippingEvent;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.shipping.types.OrderId;
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
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.Executors;

@Configuration
@EnableKafka
public class KafkaConfiguration {
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
                                                         // Both directions of the order_management translation slice, plus the shipping
                                                         // types its payloads carry. Outgoing is needed as well as incoming because this
                                                         // one ConsumerFactory also backs the integration test's consumer on the
                                                         // shipping-events topic.
                                                         // No ".*" suffix -- that form trusts strict subpackages only, so it would
                                                         // exclude the very packages these classes live in.
                                                         .trustedPackages(OrderEvent.class.getPackageName(),
                                                                          ExternalOrderShippingEvent.class.getPackageName(),
                                                                          OrderId.class.getPackageName()));
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
        var executorService = Executors.newCachedThreadPool(ThreadFactoryBuilder.builder()
                                                                                .daemon(true)
                                                                                .nameFormat("Kafka-Listener-Task-Executor-%d")
                                                                                .build());
        var taskExecutor = new ConcurrentTaskExecutor(executorService);
        factory.getContainerProperties().setListenerTaskExecutor(taskExecutor);

        var kafkaTaskScheduler = new ThreadPoolTaskScheduler();
        kafkaTaskScheduler.setPoolSize(1);
        kafkaTaskScheduler.setThreadNamePrefix("kafka-scheduler-");
        kafkaTaskScheduler.setThreadFactory(ThreadFactoryBuilder.builder()
                                                                .daemon(true)
                                                                .nameFormat("Kafka-Task-Scheduler-%d")
                                                                .build());
        kafkaTaskScheduler.initialize();
        factory.getContainerProperties().setScheduler(kafkaTaskScheduler);

        return factory;
    }
}
