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
package dk.trustworks.essentials.components.eventlog.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The engine is raw pgjdbc: no JDBI, no Reactor, no Jackson, and nothing of the current event store.
 */
class DependencyRulesTest {
    private static JavaClasses moduleClasses;

    @BeforeAll
    static void importModuleClasses() {
        moduleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials.components.eventlog");
    }

    @Test
    void the_engine_does_not_depend_on_jdbi_reactor_jackson_or_the_current_event_store() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.jdbi..",
                                                                     "reactor..",
                                                                     "com.fasterxml.jackson..",
                                                                     "tools.jackson..",
                                                                     "dk.trustworks.essentials.components.eventsourced..")
                   .check(moduleClasses);
    }
}
