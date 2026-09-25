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

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dk.trustworks.essentials.components.foundation.schema.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Proves the rule sees what it claims to - otherwise the passing module guards prove nothing.
 */
class EssentialsSchemaRulesTest {

    @Test
    void a_literal_a_concatenated_statement_and_a_text_block_are_all_seen() {
        var result = EssentialsSchemaRules.ddlLivesInSchemaContributors(Set.of())
                                          .evaluate(new ClassFileImporter().importClasses(RunsItsOwnDdl.class));

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anyMatch(line -> line.contains("DROP INDEX IF EXISTS legacy_idx"))
                .anyMatch(line -> line.contains("CREATE TABLE IF NOT EXISTS ? ("))
                .anyMatch(line -> line.contains("ALTER TABLE orders ADD COLUMN"))
                .hasSize(3);
    }

    @Test
    void a_log_message_is_not_ddl() {
        var result = EssentialsSchemaRules.ddlLivesInSchemaContributors(Set.of())
                                          .evaluate(new ClassFileImporter().importClasses(OnlyLogs.class));

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void a_contributor_a_class_nested_in_one_and_an_allowed_class_may_hold_ddl() {
        var classes = new ClassFileImporter().importClasses(Contributor.class, Contributor.Statements.class, RunsItsOwnDdl.class);

        assertThat(EssentialsSchemaRules.ddlLivesInSchemaContributors(Set.of(RunsItsOwnDdl.class.getName())).evaluate(classes).hasViolation()).isFalse();
    }

    static final class RunsItsOwnDdl {
        // Separate methods: within one expression javac folds all three into a single concatenation recipe
        static String literal() {
            return "DROP INDEX IF EXISTS legacy_idx";
        }

        static String concatenated(String table) {
            return "CREATE TABLE IF NOT EXISTS " + table + " (id INT)";
        }

        static String textBlock() {
            return """
                   ALTER TABLE orders ADD COLUMN total NUMERIC
                   """;
        }
    }

    static final class OnlyLogs {
        static String message(String table) {
            return "Creating table " + table + " - create the index later";
        }
    }

    static final class Contributor implements EssentialsSchemaContributor {
        @Override
        public String moduleId() {
            return "test";
        }

        @Override
        public int order() {
            return SchemaOrder.ORDER_APPLICATION;
        }

        @Override
        public List<SchemaChange> contribute(SchemaContext context) {
            return List.of(SchemaChange.repeatable("t", "t", Statements.CREATE));
        }

        static final class Statements {
            static final String CREATE = "CREATE TABLE IF NOT EXISTS t (id INT)";
        }
    }
}
