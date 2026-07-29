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
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the module-coupling invariants that Essentials documents but that no
 * compiler check guarantees on its own. These are <em>package-access</em> rules:
 * they fail the build the moment production code reaches across a module boundary
 * in the wrong direction, with a message that names the offending class.
 * <p>
 * This test lives in {@code eventsourced-aggregates} on purpose: it is the
 * downstream end of the core dependency chain
 * ({@code shared → types/reactive → foundation-types → foundation →
 * postgresql-event-store → eventsourced-aggregates}), so its test classpath
 * transitively contains every upstream module. That lets ArchUnit import the
 * whole graph and verify direction in one place rather than scattering a partial
 * rule into each module (where the upstream-only classpath makes most rules
 * vacuous).
 * <p>
 * It is a {@code *Test} (not {@code *IT}): it analyses bytecode only, needs no
 * Docker, and runs in {@code mvn test}.
 * <h2>What is intentionally NOT enforced here</h2>
 * The {@code postgresql-event-store} boundary is left to artifact-level tooling
 * (maven-enforcer {@code bannedDependencies}). Reason: the event-store
 * <em>type primitives</em> ({@code AggregateType}, {@code EventOrder},
 * {@code GlobalEventOrder}, …) are declared by the {@code foundation-types}
 * module under the same {@code ...eventsourced.eventstore.postgresql...} package
 * that {@code postgresql-event-store} uses for its implementation. The package
 * namespace is therefore shared between two modules, so it cannot serve as a
 * clean ArchUnit proxy for "the event-store module" without false positives
 * (e.g. {@code foundation} legitimately depends on those primitives). The exact
 * module-to-module rule belongs at the POM/artifact level.
 */
class EssentialsArchitectureTest {

    // --- Module → package roots (only the unambiguous ones are used as rule targets) ---
    private static final String ESSENTIALS       = "dk.trustworks.essentials..";
    private static final String SHARED           = "dk.trustworks.essentials.shared..";
    private static final String TYPES            = "dk.trustworks.essentials.types..";
    private static final String REACTIVE         = "dk.trustworks.essentials.reactive..";
    private static final String IMMUTABLE        = "dk.trustworks.essentials.immutable..";
    private static final String COMPONENTS       = "dk.trustworks.essentials.components..";
    private static final String FOUNDATION       = "dk.trustworks.essentials.components.foundation..";
    private static final String FOUNDATION_TYPES = "dk.trustworks.essentials.components.foundation.types..";
    private static final String AGGREGATES       = "dk.trustworks.essentials.components.eventsourced.aggregates..";

    private static JavaClasses essentialsClasses;

    @BeforeAll
    static void importProductionClasses() {
        essentialsClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials");
    }

    /**
     * {@code shared} is the zero-dependency foundation: it must not reach into any
     * other Essentials module (only the JDK).
     */
    @Test
    void shared_has_no_dependencies_on_other_essentials_modules() {
        noClasses().that().resideInAPackage(SHARED)
                   .should().dependOnClassesThat().resideInAnyPackage(TYPES, REACTIVE, IMMUTABLE, COMPONENTS)
                   .because("shared/ is the zero-dependency base of the library — it may depend only on the JDK")
                   .allowEmptyShould(true)
                   .check(essentialsClasses);
    }

    /**
     * {@code types} sits just above {@code shared}; it must not depend on
     * {@code reactive}, {@code immutable}, or any {@code components} module.
     */
    @Test
    void types_depends_only_on_shared() {
        noClasses().that().resideInAPackage(TYPES)
                   .should().dependOnClassesThat().resideInAnyPackage(REACTIVE, IMMUTABLE, COMPONENTS)
                   .because("types/ may depend only on shared/")
                   .allowEmptyShould(true)
                   .check(essentialsClasses);
    }

    /**
     * {@code reactive} (EventBus/CommandBus) sits just above {@code shared}; it
     * must not depend on {@code types}, {@code immutable}, or any {@code components} module.
     */
    @Test
    void reactive_depends_only_on_shared() {
        noClasses().that().resideInAPackage(REACTIVE)
                   .should().dependOnClassesThat().resideInAnyPackage(TYPES, IMMUTABLE, COMPONENTS)
                   .because("reactive/ may depend only on shared/")
                   .allowEmptyShould(true)
                   .check(essentialsClasses);
    }

    /**
     * {@code immutable} sits just above {@code shared} (its only production
     * dependency). Its {@code types} dependency is <em>test-scope only</em>, so
     * production code must not reach into {@code types}, {@code reactive}, or any
     * {@code components} module either.
     */
    @Test
    void immutable_depends_only_on_shared() {
        noClasses().that().resideInAPackage(IMMUTABLE)
                   .should().dependOnClassesThat().resideInAnyPackage(TYPES, REACTIVE, COMPONENTS)
                   .because("immutable/ production code may depend only on shared/ (its dependency on types/ is test-scope only)")
                   .allowEmptyShould(true)
                   .check(essentialsClasses);
    }

    /**
     * {@code foundation-types} is upstream of {@code foundation} and the event
     * store: it may depend only on {@code types} (and {@code shared} transitively).
     * It must not reach "down" into the {@code foundation} implementation,
     * {@code reactive}, {@code immutable}, or {@code eventsourced-aggregates}.
     * <p>
     * Only the unambiguous {@code ...components.foundation.types..} package is used
     * as the rule <em>target</em>: {@code foundation-types} also declares event-store
     * type primitives under the shared {@code ...eventsourced.eventstore.postgresql...}
     * namespace (see class Javadoc), which cannot serve as a clean ArchUnit proxy.
     * The artifact-level "{@code foundation-types} POM must not depend on
     * {@code postgresql-event-store}" rule is enforced separately via maven-enforcer
     * {@code bannedDependencies}.
     */
    @Test
    void foundation_types_does_not_depend_on_foundation_or_downstream_modules() {
        noClasses().that().resideInAPackage(FOUNDATION_TYPES)
                   .should().dependOnClassesThat(
                           resideInAnyPackage(REACTIVE, IMMUTABLE, AGGREGATES)
                                   .or(resideInAPackage(FOUNDATION).and(not(resideInAPackage(FOUNDATION_TYPES)))))
                   .because("foundation-types is upstream of foundation — dependencies point foundation-types → foundation, never back")
                   .allowEmptyShould(true)
                   .check(essentialsClasses);
    }

    /**
     * {@code eventsourced-aggregates} is the top of the core chain: no module
     * below it may depend on it. This is the strongest "direction" guard — it
     * catches an upstream module (foundation, event-store, types, shared, …)
     * accidentally importing an aggregate type.
     */
    @Test
    void no_upstream_module_depends_on_eventsourced_aggregates() {
        noClasses().that().resideInAPackage(ESSENTIALS)
                   .and().resideOutsideOfPackage(AGGREGATES)
                   .should().dependOnClassesThat().resideInAPackage(AGGREGATES)
                   .because("eventsourced-aggregates is downstream of every other core module — dependencies point toward it, never away from it")
                   .allowEmptyShould(true)
                   .check(essentialsClasses);
    }

    /**
     * No dependency cycles between the {@code components} sub-areas (foundation,
     * eventsourced, distributed, queue, …) — fails the JVM build directly the
     * moment two component areas form an import cycle.
     */
    @Test
    void components_are_free_of_cycles() {
        slices().matching("dk.trustworks.essentials.components.(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true)
                .check(essentialsClasses);
    }
}
