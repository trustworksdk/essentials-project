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

import dk.trustworks.essentials.components.boot.autoconfigure.mongodb.AdditionalCharSequenceTypesSupported;
import dk.trustworks.essentials.components.boot.autoconfigure.mongodb.AdditionalConverters;
import dk.trustworks.essentials.spring.examples.mongodb.messaging.shipping.types.OrderId;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.convert.Jsr310Converters;

/**
 * Entry point for the MongoDB flavour of the Inbox/Outbox example.
 *
 * <p>Beyond starting Spring Boot it contributes the two pieces of wiring the Essentials MongoDB auto-configuration
 * cannot infer: {@link AdditionalCharSequenceTypesSupported} tells the document store how to read and write
 * {@code OrderId} as a plain string rather than a nested object, and {@link AdditionalConverters} adds the JSR-310
 * {@code Duration} converters that the durable-queue and Inbox/Outbox documents need.
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

    @Bean
    AdditionalCharSequenceTypesSupported additionalCharSequenceTypesSupported() {
        return new AdditionalCharSequenceTypesSupported(OrderId.class);
    }

    @Bean
    AdditionalConverters additionalGenericConverters() {
        return new AdditionalConverters(Jsr310Converters.StringToDurationConverter.INSTANCE,
                                        Jsr310Converters.DurationToStringConverter.INSTANCE);
    }
}
