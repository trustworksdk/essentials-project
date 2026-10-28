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

package dk.trustworks.essentials.components.boot.autoconfigure.mongodb;

import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule;
import dk.trustworks.essentials.shared.network.Network;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires the whole MongoDB starter against a real replica set (Essentials' Mongo queues and fenced locks run in
 * transactions, which a standalone server does not support). The fenced lock manager starts during context refresh,
 * so a context that comes up has a working lock manager.
 */
@Testcontainers
class StarterAutoConfigurationIT {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer(EssentialsTestContainers.MONGO_IMAGE).withReplicaSet();

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class,
                                                             DataMongoAutoConfiguration.class,
                                                             EssentialsComponentsConfiguration.class))
                    .withPropertyValues("spring.mongodb.uri=" + mongoDBContainer.getReplicaSetUrl("starter-test-db"),
                                        // Keep the context free of background consumers: only the wiring is under test
                                        "essentials.life-cycles.start-life-cycles=false");

    /**
     * {@code essentials.immutable-jackson-module-enabled} defaults to {@code true} for the persistence mapper only (N-11,
     * 0.60.1). Spring Boot's web {@code JsonMapper} keeps the 0.60.0 behaviour: the module bean - and with it the web
     * mapper's Objenesis fallback - exists only when the property is set to {@code true} explicitly, because on the web
     * mapper it changes HTTP request binding (a body Jackson cannot construct gets null fields instead of a 400).
     */
    @Test
    void by_default_the_immutable_jackson_module_is_on_for_the_persistence_mapper_only() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EssentialsImmutableJacksonModule.class);
            assertThat(persistenceMapperOf(ctx).registeredModules()).hasAtLeastOneElementOfType(EssentialsImmutableJacksonModule.class);
        });
    }

    @Test
    void an_explicit_true_puts_the_immutable_jackson_module_on_both_the_web_and_the_persistence_mapper() {
        contextRunner
                .withPropertyValues("essentials.immutable-jackson-module-enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(EssentialsImmutableJacksonModule.class);
                    assertThat(persistenceMapperOf(ctx).registeredModules()).hasAtLeastOneElementOfType(EssentialsImmutableJacksonModule.class);
                });
    }

    @Test
    void an_explicit_false_removes_the_immutable_jackson_module_from_both_and_leaves_the_wire_format_alone() {
        contextRunner
                .withPropertyValues("essentials.immutable-jackson-module-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(EssentialsImmutableJacksonModule.class);
                    var persistenceMapper = persistenceMapperOf(ctx);
                    assertThat(persistenceMapper.registeredModules()).noneMatch(EssentialsImmutableJacksonModule.class::isInstance);
                    assertThat(EssentialsObjectMappers.findWireFormatDeviations(persistenceMapper)).isEmpty();
                });
    }

    private static tools.jackson.databind.ObjectMapper persistenceMapperOf(ApplicationContext ctx) {
        return ((Jackson3JSONSerializer) ctx.getBean(JSONSerializer.class)).getObjectMapper();
    }
}
