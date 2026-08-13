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

package dk.trustworks.essentials.types.spring.web;

import dk.trustworks.essentials.types.spring.web.model.CustomerId;
import dk.trustworks.essentials.types.spring.web.model.DueDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.http.codec.EncoderHttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assertion that {@link EssentialsWebFluxConfigurer} is safe to put on a Spring Boot 4 reactive application.
 * <p>
 * This exists because the opposite was believed, acted on, and cost a correct change: the module's documentation
 * described a Jackson 2 {@code WebFluxConfig} as something {@code types-spring-web} shipped and auto-registered, so
 * adding the dependency looked like it might displace Boot 4's Jackson 3 codecs at startup. It never could - that
 * class is test scope - but nothing proved it either way. Now something does.
 * <p>
 * Two things are checked, and the second is the one that matters:
 * <ol>
 *     <li>a semantic type binds as a {@code @PathVariable} on the reactive stack;</li>
 *     <li>the application's JSON codecs are <b>untouched</b> - no Jackson 2 encoder or decoder appears among them.</li>
 * </ol>
 */
@SpringBootTest(classes = EssentialsWebFluxConfigurerJackson3Test.ReactiveApplication.class,
                webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.main.web-application-type=reactive")
@EnabledIfSystemProperty(named = "essentials.jackson.flavor", matches = "jackson3")
class EssentialsWebFluxConfigurerJackson3Test {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({EssentialsWebFluxConfigurer.class, ReactiveOrderController.class})
    static class ReactiveApplication {
    }

    // WebTestClient is not auto-configured here: spring-webmvc is also on this module's test classpath, so Boot's
    // test support sees a servlet application and contributes a TestRestTemplate instead. The context itself is
    // reactive because of spring.main.web-application-type above, so binding to the running port by hand is both
    // correct and unambiguous about which stack is under test.
    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ServerCodecConfigurer codecConfigurer;

    private WebTestClient testClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void a_CharSequenceType_binds_as_a_path_variable_on_the_reactive_stack() {
        var customerId = CustomerId.random();
        testClient().get()
                  .uri("/reactive/customers/{customerId}", customerId.toString())
                  .exchange()
                  .expectStatus().isOk()
                  .expectBody(String.class).isEqualTo(customerId.toString());
    }

    @Test
    void a_JSR310SingleValueType_binds_as_a_path_variable_on_the_reactive_stack() {
        var dueDate = DueDate.now();
        testClient().get()
                  .uri("/reactive/orders/by-due-date/{dueDate}", dueDate.toString())
                  .exchange()
                  .expectStatus().isOk()
                  .expectBody(String.class).isEqualTo(dueDate.toString());
    }

    @Test
    void the_configurer_leaves_the_applications_json_codecs_alone() {
        // The readers/writers are wrappers - DecoderHttpMessageReader, EncoderHttpMessageWriter - so their own class
        // names say nothing about Jackson. The codec that matters is the one inside, and unwrapping is what makes
        // this assertion able to fail at all.
        var decoderClassNames = codecConfigurer.getReaders().stream()
                                               .filter(DecoderHttpMessageReader.class::isInstance)
                                               .map(reader -> ((DecoderHttpMessageReader<?>) reader).getDecoder()
                                                                                                    .getClass()
                                                                                                    .getName());
        var encoderClassNames = codecConfigurer.getWriters().stream()
                                               .filter(EncoderHttpMessageWriter.class::isInstance)
                                               .map(writer -> ((EncoderHttpMessageWriter<?>) writer).getEncoder()
                                                                                                    .getClass()
                                                                                                    .getName());

        var jsonCodecs = Stream.concat(decoderClassNames, encoderClassNames)
                               .filter(name -> name.contains("Jackson"))
                               .toList();

        // The point of the whole test. If EssentialsWebFluxConfigurer overrode configureHttpMessageCodecs the way
        // the test-scope WebFluxConfig does, Jackson2JsonEncoder/Jackson2JsonDecoder would appear here and the
        // application would silently be serialising with the wrong Jackson major.
        //
        // isNotEmpty() is not decoration: without it the two noneMatch assertions would pass on an empty list, and
        // this test would prove nothing - which is exactly what an earlier version of it did.
        assertThat(jsonCodecs).isNotEmpty()
                              .noneMatch(name -> name.contains("Jackson2Json"))
                              // Jackson 3 codecs live under tools.jackson; Jackson 2's under com.fasterxml.jackson.
                              .noneMatch(name -> name.startsWith("com.fasterxml.jackson"));
    }
}
