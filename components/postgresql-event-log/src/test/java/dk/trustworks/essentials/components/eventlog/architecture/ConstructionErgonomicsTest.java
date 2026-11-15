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
import dk.trustworks.essentials.components.foundation.test.architecture.EssentialsConstructionRules;
import org.junit.jupiter.api.*;

/**
 * The project's construction-ergonomics rules over this module only, unfrozen, as the shard-owned queue does.
 */
class ConstructionErgonomicsTest {
    private static JavaClasses moduleClasses;

    @BeforeAll
    static void importModuleClasses() {
        moduleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials.components.eventlog");
    }

    @Test
    void constructors_do_not_declare_optional_parameters() {
        EssentialsConstructionRules.constructorsMustNotDeclareOptionalParametersUnlessDeprecatedForRemoval().check(moduleClasses);
    }

    @Test
    void constructors_stay_within_the_parameter_ceiling() {
        EssentialsConstructionRules.constructorsMustStayWithinTheParameterCeilingUnlessDeprecatedForRemoval().check(moduleClasses);
    }
}
