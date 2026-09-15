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

package dk.trustworks.essentials.components.foundation.test.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.*;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.*;

/**
 * The construction-ergonomics guard, in the one place every implementation module can reach: no {@code Optional}
 * parameters in constructors, a five-parameter ceiling, and a named replacement behind every
 * {@code @Deprecated(forRemoval = true)} constructor. The rules themselves live in {@link EssentialsConstructionRules};
 * this class is the harness that runs them.
 * <h2>Why it is a base class rather than one test</h2>
 * The rules apply to the whole reactor, but a test can only see what is on <em>its own</em> test classpath. No single
 * module's classpath contains everything: {@code eventsourced-aggregates} reaches the core chain and the PostgreSQL
 * event store but not the queue and fenced-lock implementations, and nothing at all reaches the MongoDB ones. The
 * Spring Boot starters are the natural vantage points, because a starter exists precisely to pull one complete
 * implementation stack onto a single classpath — so extending this class from
 * {@code spring-boot-starter-postgresql} and {@code spring-boot-starter-mongodb} covers both queue and fenced-lock
 * implementations on both databases without adding a dependency anywhere for the test's sake.
 * <p>
 * A subclass is therefore empty: what it guards is decided by its module's POM, not by any code it writes.
 * <h2>What a subclass has to supply</h2>
 * <ol>
 *     <li>{@code archunit-junit5} and {@code foundation-test}, both {@code test}-scoped. ArchUnit is
 *     <em>optional</em> in this module's POM on purpose — foundation-test is a consumer-facing test-utility jar and
 *     must not push ArchUnit onto everyone who uses it.</li>
 *     <li>{@code src/test/resources/archunit.properties} pointing {@code freeze.store.default.path} at the module's
 *     own store directory.</li>
 *     <li>The store directory itself, <strong>committed</strong>. It is the rule's input, not build output: with
 *     {@code allowStoreCreation=true} and no store in the repository, a clean checkout writes a fresh baseline from
 *     whatever it finds and passes, which silently disables the guard on every CI run.</li>
 * </ol>
 * <h2>Overlapping stores are expected</h2>
 * Where two modules' classpaths overlap, a violation in the shared part is recorded in both stores. That is
 * harmless — {@code allowStoreUpdate=true} means fixing it removes it from both on the next run — and it is the
 * price of the alternative being worse: restricting each module's import to "the packages nobody else covers" is a
 * mapping that rots the moment a dependency moves.
 * <h2>Why the rules are frozen</h2>
 * The reactor does not satisfy these rules yet; converting the classes in scope is a multi-phase effort, and a red
 * build for its duration would be useless. {@link FreezingArchRule} records today's violations in the module's store
 * and fails only on <em>new</em> ones, so each phase can land green while the guard still stops the codebase from
 * getting worse. Violations that get fixed are removed from the store automatically — its shrinking size is the
 * progress metric for the whole sweep.
 * <p>
 * When the last violation is gone everywhere, delete the stores and the {@link FreezingArchRule#freeze} wrappers;
 * the rules underneath are already absolute.
 *
 * @see EssentialsConstructionRules
 */
public abstract class AbstractEssentialsConstructionErgonomicsTest {

    private static JavaClasses essentialsProductionClasses;

    @BeforeAll
    static void importProductionClasses() {
        // No DO_NOT_INCLUDE_JARS here: upstream modules arrive as jars from the local repository when a module is
        // built on its own (-pl <module>) and as target/classes directories in a full reactor build. Excluding jars
        // would silently shrink the guard to the module under test alone in the first case.
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
     * Not frozen. Nothing in the tree is deprecated for removal without a replacement yet, so this rule starts
     * satisfied and must stay that way — the moment a phase deprecates a constructor without providing a builder or a
     * compliant constructor, this fails immediately rather than being absorbed into a store.
     */
    @Test
    void constructors_deprecated_for_removal_offer_a_replacement() {
        EssentialsConstructionRules.constructorsDeprecatedForRemovalMustOfferAReplacement()
                                   .check(essentialsProductionClasses);
    }
}
