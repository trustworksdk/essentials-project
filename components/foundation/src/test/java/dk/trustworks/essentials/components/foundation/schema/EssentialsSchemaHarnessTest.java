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
package dk.trustworks.essentials.components.foundation.schema;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class EssentialsSchemaHarnessTest {

    @Test
    void change_sets_are_ordered_by_order_then_module_id_regardless_of_registration_order() {
        var applier = new RecordingApplier();
        var harness = new EssentialsSchemaHarness(applier,
                                                  SchemaContext.empty(),
                                                  List.of(contributor("queues", SchemaOrder.ORDER_QUEUES, change("q")),
                                                          contributor("locks", SchemaOrder.ORDER_INFRASTRUCTURE, change("l")),
                                                          contributor("jobs", SchemaOrder.ORDER_INFRASTRUCTURE, change("j")),
                                                          contributor("app", SchemaOrder.ORDER_APPLICATION, change("a"))));

        harness.apply();

        assertThat(applier.applied).extracting(SchemaChangeSet::moduleId).containsExactly("jobs", "locks", "queues", "app");
    }

    @Test
    void two_instances_of_one_module_may_contribute_for_different_objects_but_not_for_the_same() {
        var applier = new RecordingApplier();
        new EssentialsSchemaHarness(applier,
                                    SchemaContext.empty(),
                                    List.of(contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "orders_queue", "SELECT 1")),
                                            contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "billing_queue", "SELECT 1"))))
                .apply();
        assertThat(applier.applied).hasSize(2);

        var clash = new EssentialsSchemaHarness(new RecordingApplier(),
                                                SchemaContext.empty(),
                                                List.of(contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "orders_queue", "SELECT 1")),
                                                        contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "orders_queue", "SELECT 1"))));
        assertThatThrownBy(clash::apply).isInstanceOf(IllegalStateException.class).hasMessageContaining("'orders_queue'");
    }

    @Test
    void a_change_may_not_be_contributed_twice_for_one_object() {
        var harness = new EssentialsSchemaHarness(new RecordingApplier(),
                                                  SchemaContext.empty(),
                                                  List.of(contributor("queues", SchemaOrder.ORDER_QUEUES, change("table"), change("table"))));

        assertThatThrownBy(harness::apply).isInstanceOf(IllegalStateException.class).hasMessageContaining("'table'");
    }

    @Test
    void one_change_may_apply_to_several_objects() {
        var applier = new RecordingApplier();
        var harness = new EssentialsSchemaHarness(applier,
                                                  SchemaContext.empty(),
                                                  List.of(contributor("event-store", SchemaOrder.ORDER_EVENT_STORE,
                                                                      SchemaChange.repeatable("stream-table", "orders_events", "SELECT 1"),
                                                                      SchemaChange.repeatable("stream-table", "products_events", "SELECT 1"))));

        harness.apply();

        assertThat(applier.applied.getFirst().changes()).hasSize(2);
    }

    @Test
    void contributors_receive_the_context() {
        var context = SchemaContext.empty().with(String.class, "tenant_id");
        var seen    = new ArrayList<Optional<String>>();
        var harness = new EssentialsSchemaHarness(new RecordingApplier(), context, List.of(new EssentialsSchemaContributor() {
            @Override
            public String moduleId() {
                return "m";
            }

            @Override
            public int order() {
                return SchemaOrder.ORDER_APPLICATION;
            }

            @Override
            public List<SchemaChange> contribute(SchemaContext schemaContext) {
                seen.add(schemaContext.find(String.class));
                return List.of();
            }
        }));

        harness.apply();

        assertThat(seen).containsExactly(Optional.of("tenant_id"));
    }

    @Test
    void a_change_needs_an_id_an_object_and_non_blank_statements() {
        assertThatThrownBy(() -> SchemaChange.once(" ", "t", "SELECT 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaChange.once("c", "", "SELECT 1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaChange.once("c", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SchemaChange.once("c", "t", "SELECT 1", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_checksum_follows_the_statements_exactly() {
        var original = SchemaChange.once("c", "t", "CREATE TABLE t (id INT)", "CREATE INDEX t_idx ON t (id)");

        assertThat(SchemaChange.once("other-id", "other_table", "CREATE TABLE t (id INT)", "CREATE INDEX t_idx ON t (id)").checksum())
                .as("identity is not part of the checksum").isEqualTo(original.checksum());
        assertThat(SchemaChange.once("c", "t", "CREATE TABLE t (id BIGINT)", "CREATE INDEX t_idx ON t (id)").checksum()).isNotEqualTo(original.checksum());
        assertThat(SchemaChange.once("c", "t", "CREATE TABLE t (id INT)CREATE INDEX t_idx ON t (id)").checksum())
                .as("statement boundaries count").isNotEqualTo(original.checksum());
    }

    private static SchemaChange change(String changeId) {
        return SchemaChange.repeatable(changeId, "t_" + changeId, "SELECT 1");
    }

    private static EssentialsSchemaContributor contributor(String moduleId, int order, SchemaChange... changes) {
        return new EssentialsSchemaContributor() {
            @Override
            public String moduleId() {
                return moduleId;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public List<SchemaChange> contribute(SchemaContext context) {
                return List.of(changes);
            }
        };
    }

    private static final class RecordingApplier implements SchemaApplier {
        private final List<SchemaChangeSet> applied = new ArrayList<>();

        @Override
        public void apply(List<SchemaChangeSet> changeSets) {
            applied.addAll(changeSets);
        }
    }
}
