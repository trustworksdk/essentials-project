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

package dk.trustworks.essentials.components.queue.shardowned.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import dk.trustworks.essentials.components.foundation.test.architecture.EssentialsConstructionRules;
import org.junit.jupiter.api.*;

/**
 * The project's construction-ergonomics rules, over this module only.
 * <p>
 * <b>Scoped and unfrozen, deliberately</b>, where the rest of the reactor uses
 * {@code AbstractEssentialsConstructionErgonomicsTest} with a frozen baseline. That shape exists
 * because those modules have violations they are working through, and a red build for the duration
 * would be useless. This module has none — its wide constructors belong to package-private engine
 * internals, which the rules do not reach — so a frozen store here would do the opposite of its job:
 * it would import every upstream module's known violations into a baseline this module did not cause
 * and cannot fix, and it would let a new violation here pass unnoticed if it happened to land in a
 * package another store already covers.
 * <p>
 * Absolute rules over one package is the stronger guarantee, and it is available precisely because
 * the module is compliant today. If that ever stops being true, the honest response is to fix the
 * constructor rather than to freeze it.
 */
class ConstructionErgonomicsTest {

    private static JavaClasses moduleClasses;

    @BeforeAll
    static void importModuleClasses() {
        moduleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials.components.queue.shardowned");
    }

    @Test
    void constructors_do_not_declare_optional_parameters() {
        EssentialsConstructionRules.constructorsMustNotDeclareOptionalParametersUnlessDeprecatedForRemoval()
                                   .check(moduleClasses);
    }

    @Test
    void constructors_stay_within_the_parameter_ceiling() {
        EssentialsConstructionRules.constructorsMustStayWithinTheParameterCeilingUnlessDeprecatedForRemoval()
                                   .check(moduleClasses);
    }

    @Test
    void constructors_deprecated_for_removal_offer_a_replacement() {
        EssentialsConstructionRules.constructorsDeprecatedForRemovalMustOfferAReplacement()
                                   .check(moduleClasses);
    }
}
