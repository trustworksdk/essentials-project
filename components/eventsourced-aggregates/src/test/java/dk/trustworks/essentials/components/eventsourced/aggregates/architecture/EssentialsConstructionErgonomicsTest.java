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

package dk.trustworks.essentials.components.eventsourced.aggregates.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import dk.trustworks.essentials.components.foundation.test.architecture.EssentialsConstructionRules;
import org.junit.jupiter.api.*;

/**
 * The construction-ergonomics guard: no {@code Optional} parameters in constructors, a five-parameter ceiling, and
 * a named replacement behind every {@code @Deprecated(forRemoval = true)} constructor. The rules themselves live in
 * {@link EssentialsConstructionRules}; this class only decides <em>which classes</em> they run against.
 * <p>
 * It lives in {@code eventsourced-aggregates} for the same reason {@link EssentialsArchitectureTest} does: this
 * module sits at the downstream end of the core chain ({@code shared → types/reactive → foundation-types →
 * foundation → postgresql-event-store → eventsourced-aggregates}), so importing the
 * {@code dk.trustworks.essentials} package here pulls in every upstream module's production classes from the test
 * classpath. Modules that are <em>not</em> on this classpath — the queue and fenced-lock implementations, and the
 * Spring Boot starters — carry their own thin copy of this test.
 * <h2>Why the rules are frozen</h2>
 * The reactor does not satisfy these rules yet; converting ~70 classes is a multi-phase effort, and a red build for
 * its duration would be useless. {@link FreezingArchRule} records today's violations in the checked-in
 * {@code archunit_store/} and fails only on <em>new</em> ones, so each phase can land green while the guard still
 * stops the codebase from getting worse. Violations that get fixed are removed from the store automatically — its
 * shrinking size is the progress metric for the whole sweep.
 * <p>
 * When the last violation is gone, delete the store and the {@code FreezingArchRule.freeze(...)} wrapper; the rules
 * underneath are already absolute.
 */
class EssentialsConstructionErgonomicsTest {

    private static JavaClasses essentialsProductionClasses;

    @BeforeAll
    static void importProductionClasses() {
        // No DO_NOT_INCLUDE_JARS here: upstream modules arrive as jars from the local repository when this module is
        // built on its own (-pl components/eventsourced-aggregates) and as target/classes directories in a full
        // reactor build. Excluding jars would silently shrink the guard to this module alone in the first case.
        essentialsProductionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials");
    }

    @Test
    void constructors_do_not_declare_optional_parameters() {
        FreezingArchRule.freeze(EssentialsConstructionRules.constructorsMustNotDeclareOptionalParametersUnlessDeprecatedForRemoval())
                        .check(essentialsProductionClasses);
    }

    @Test
    void constructors_stay_within_the_parameter_ceiling() {
        FreezingArchRule.freeze(EssentialsConstructionRules.constructorsMustStayWithinTheParameterCeilingUnlessDeprecatedForRemoval())
                        .check(essentialsProductionClasses);
    }

    /**
     * Not frozen. Nothing in the tree is deprecated for removal yet, so this rule starts satisfied and must stay
     * that way — the moment a phase deprecates a constructor without providing a builder or a compliant
     * constructor, this fails immediately rather than being absorbed into a store.
     */
    @Test
    void constructors_deprecated_for_removal_offer_a_replacement() {
        EssentialsConstructionRules.constructorsDeprecatedForRemovalMustOfferAReplacement()
                                   .check(essentialsProductionClasses);
    }
}
