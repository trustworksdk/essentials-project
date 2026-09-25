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
import org.junit.jupiter.api.*;

import java.util.Set;

/**
 * Runs {@link EssentialsSchemaRules} against every Essentials production class on the subclass' classpath - upstream
 * modules included, as jars or as {@code target/classes}. Subclass it in a module whose classpath reaches the modules
 * to guard.
 */
public abstract class AbstractEssentialsSchemaRulesTest {

    private static JavaClasses essentialsProductionClasses;

    @BeforeAll
    static void importProductionClasses() {
        essentialsProductionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dk.trustworks.essentials");
    }

    /**
     * @return fully qualified names of classes allowed to hold DDL although they are no schema contributor
     */
    protected Set<String> allowedDdlHolders() {
        return EssentialsSchemaRules.ALLOWED_DDL_HOLDERS.keySet();
    }

    @Test
    void ddl_lives_in_schema_contributors() {
        EssentialsSchemaRules.ddlLivesInSchemaContributors(allowedDdlHolders()).check(essentialsProductionClasses);
    }
}
