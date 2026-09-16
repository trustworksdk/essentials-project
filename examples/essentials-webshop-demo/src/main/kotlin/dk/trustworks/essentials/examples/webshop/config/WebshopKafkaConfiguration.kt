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

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonSerializer
import tools.jackson.databind.json.JsonMapper

/**
 * Module-level Kafka wiring for the one thing this application publishes.
 *
 * This is application infrastructure and sits outside the bounded contexts - `shipping` asks for a
 * `KafkaTemplate`, not for a broker configuration.
 *
 * The producer is declared explicitly rather than left to auto-configuration for two reasons: the value
 * serializer has to be JSON rather than the default `StringSerializer`, and the template's type parameters have
 * to be `<String, Any>` so the publisher can inject it without a generics mismatch.
 *
 * Spring for Apache Kafka 4 deprecated its Jackson 2 `JsonSerializer` in favour of [JacksonJsonSerializer],
 * which binds against Jackson 3 - the mapper Spring Boot 4 auto-configures. That is independent of the
 * Essentials Jackson flavour: `-Pjackson2` switches which `types-jackson` artifact serializes *persisted*
 * events, and Spring Boot brings Jackson 3 for the web and broker edges either way.
 */
@Configuration
class WebshopKafkaConfiguration {

    @Bean
    fun webshopProducerFactory(
        @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
        jsonMapper: JsonMapper
    ): ProducerFactory<String, Any> =
        DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to JacksonJsonSerializer::class.java
            ),
            StringSerializer(),
            JacksonJsonSerializer<Any>(jsonMapper)
        )

    @Bean
    fun webshopKafkaTemplate(producerFactory: ProducerFactory<String, Any>): KafkaTemplate<String, Any> =
        KafkaTemplate(producerFactory)
}
