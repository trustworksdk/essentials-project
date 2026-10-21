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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnerSettings;
import org.junit.jupiter.api.*;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * A shared {@code ShardRuntime} WARNs a later borrower only about the values in {@code ShardOwnerSettings.RuntimeScoped}
 * ({@code ShardRuntime.acquireShared}), on the grounds that the runtime and its pumps read no other value. Every
 * {@code ShardPump} is given only {@code settings.runtimeScoped()}, so its type keeps that true for the pumps. The runtime
 * is constructed with the whole record and stores only {@code settings.runtimeScoped()}; this pins the one way around it -
 * the runtime reading any other accessor of that record - which would apply the first borrower's value to every queue on
 * the runtime with no WARN for the others.
 */
class RuntimeScopedSettingsTest {

    private static JavaClasses moduleClasses;

    @BeforeAll
    static void importModuleClasses() {
        moduleClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials.components.queue.shardowned");
    }

    @Test
    void the_runtime_and_its_pumps_read_no_setting_outside_the_runtime_scoped_values() {
        noClasses().that().haveNameMatching("dk\\.trustworks\\.essentials\\.components\\.queue\\.shardowned\\.(ShardRuntime|ShardPump)(\\$.*)?")
                   .should().callMethodWhere(anAccessorOfShardOwnerSettingsOtherThanRuntimeScoped())
                   .because("a shared ShardRuntime's WARN compares only ShardOwnerSettings.RuntimeScoped; a value the runtime or a "
                                    + "pump reads belongs in that record")
                   .check(moduleClasses);
    }

    private static DescribedPredicate<JavaMethodCall> anAccessorOfShardOwnerSettingsOtherThanRuntimeScoped() {
        return DescribedPredicate.describe("a method of ShardOwnerSettings other than runtimeScoped()",
                                           call -> call.getTargetOwner().isEquivalentTo(ShardOwnerSettings.class)
                                                   && !call.getName().equals("runtimeScoped"));
    }
}
