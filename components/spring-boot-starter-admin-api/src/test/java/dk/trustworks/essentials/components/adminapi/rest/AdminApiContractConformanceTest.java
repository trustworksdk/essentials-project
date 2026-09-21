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

package dk.trustworks.essentials.components.adminapi.rest;

import org.junit.jupiter.api.*;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformance gate: the set of {verb, path} pairs this module serves must equal the set the contract declares.
 * <p>
 * The contract is read from the classpath, published there by the {@code admin-api-spec} module. Both directions are
 * checked — an unimplemented contract operation and an endpoint that exists outside the contract are equally a
 * failure, because both make the published contract a lie.
 */
class AdminApiContractConformanceTest {

    /** Every controller that makes up the admin API surface. */
    private static final List<Class<?>> CONTROLLERS = List.of(
            FencedLocksController.class,
            SchedulerController.class,
            PostgresqlQueryStatisticsController.class,
            DurableQueuesController.class,
            EventStoreController.class,
            CdcController.class,
            EventStoreStatisticsController.class,
            AggregateLifecycleController.class,
            AggregateLifecycleStatisticsController.class,
            AggregateArchiveController.class,
            AggregateArchiveStatisticsController.class);

    private static final String CONTRACT_RESOURCE = "/openapi/essentials-admin-api.yaml";

    private static Map<String, Object> contract;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadContract() throws Exception {
        try (InputStream contractYaml = AdminApiContractConformanceTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(contractYaml)
                    .as("The contract must be on the test classpath at %s — it is published there by admin-api-spec",
                        CONTRACT_RESOURCE)
                    .isNotNull();
            contract = new Yaml().load(contractYaml);
        }
    }

    /** Keeps the comparisons below from passing vacuously if either side fails to be discovered. */
    @Test
    void both_sides_of_the_comparison_are_actually_discovered() {
        assertThat(contractOperations()).hasSize(40);
        assertThat(implementedOperations()).hasSize(40);
    }

    @Test
    void every_contract_operation_is_implemented_by_a_controller() {
        assertThat(implementedOperations())
                .as("Operations declared in the contract but not served by any controller")
                .containsAll(contractOperations());
    }

    @Test
    void no_controller_serves_an_endpoint_the_contract_does_not_declare() {
        assertThat(contractOperations())
                .as("Endpoints served by a controller but absent from the contract")
                .containsAll(implementedOperations());
    }

    @Test
    void controllers_are_mounted_on_the_configurable_base_path() {
        for (Class<?> controller : CONTROLLERS) {
            var mapping = controller.getAnnotation(RequestMapping.class);
            assertThat(mapping)
                    .as("%s must declare a class-level @RequestMapping", controller.getSimpleName())
                    .isNotNull();
            assertThat(mapping.value())
                    .as("%s must be mounted on the configurable base path", controller.getSimpleName())
                    .containsExactly(AdminApiPaths.BASE_PATH_PLACEHOLDER);
        }
    }

    /**
     * The contract's declared paths are relative to {@code servers[0].url}, matching what the controllers map below
     * their class-level base path — so the two sets are directly comparable.
     */
    @SuppressWarnings("unchecked")
    private static SortedSet<String> contractOperations() {
        var operations = new TreeSet<String>();
        var paths      = (Map<String, Map<String, Object>>) contract.get("paths");
        paths.forEach((path, verbs) -> verbs.keySet()
                                            .forEach(verb -> operations.add(verb.toUpperCase(Locale.ROOT) + " " + path)));
        return operations;
    }

    private static SortedSet<String> implementedOperations() {
        var operations = new TreeSet<String>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                mappingOf(method).ifPresent(operations::add);
            }
        }
        return operations;
    }

    private static Optional<String> mappingOf(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            var mapping = switch (annotation) {
                case GetMapping get -> "GET " + single(get.value(), method);
                case PostMapping post -> "POST " + single(post.value(), method);
                case PutMapping put -> "PUT " + single(put.value(), method);
                case PatchMapping patch -> "PATCH " + single(patch.value(), method);
                case DeleteMapping delete -> "DELETE " + single(delete.value(), method);
                default -> null;
            };
            if (mapping != null) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    private static String single(String[] paths, Method method) {
        assertThat(paths)
                .as("%s.%s must map exactly one path, so it maps to exactly one contract operation",
                    method.getDeclaringClass().getSimpleName(), method.getName())
                .hasSize(1);
        return paths[0];
    }
}
