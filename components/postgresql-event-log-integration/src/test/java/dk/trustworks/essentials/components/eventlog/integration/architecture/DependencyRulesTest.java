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

package dk.trustworks.essentials.components.eventlog.integration.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The integration module's dependency rules (design phase 8 §3.1, §11.2): it re-uses the old engine's value types and
 * serializers, never its subscription model; it serializes only through the application's {@code JSONEventSerializer};
 * Spring stays confined to the classes that are about Spring, so the module runs without it.
 */
class DependencyRulesTest {
    private static JavaClasses moduleClasses;

    @BeforeAll
    static void importModuleClasses() {
        moduleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials.components.eventlog.integration");
    }

    @Test
    void the_old_subscription_model_and_reactor_are_not_used() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("reactor..",
                                                                     "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription..",
                                                                     "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor..",
                                                                     "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap..",
                                                                     "dk.trustworks.essentials.components.foundation.fencedlock..",
                                                                     "dk.trustworks.essentials.components.foundation.messaging..")
                   .check(moduleClasses);
    }

    @Test
    void no_mapper_is_built_here() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson.databind..")
                   .check(moduleClasses);
    }

    @Test
    void spring_is_confined_to_the_spring_classes() {
        noClasses().that().haveNameNotMatching(".*\\.(SpringPageTransactions|EventLogCommits|UnitOfWorkPageTransactions)(\\$.*)?")
                   .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                   .check(moduleClasses);
    }
}
