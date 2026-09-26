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
    void two_instances_of_one_module_may_contribute_for_different_objects() {
        var applier = new RecordingApplier();
        new EssentialsSchemaHarness(applier,
                                    SchemaContext.empty(),
                                    List.of(contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "orders_queue", "SELECT 1")),
                                            contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "billing_queue", "SELECT 1"))))
                .apply();
        assertThat(applier.applied).hasSize(2);
    }

    @Test
    void the_same_change_described_identically_twice_is_applied_once() {
        var applier = new RecordingApplier();
        new EssentialsSchemaHarness(applier,
                                    SchemaContext.empty(),
                                    List.of(contributor("snapshots", SchemaOrder.ORDER_AGGREGATES, SchemaChange.repeatable("snapshot-table", "snapshots", "SELECT 1")),
                                            contributor("snapshots", SchemaOrder.ORDER_AGGREGATES, SchemaChange.repeatable("snapshot-table", "snapshots", "SELECT 1"))))
                .apply();

        assertThat(applier.applied).as("the second, now empty, set is dropped").hasSize(1);
        assertThat(applier.applied.getFirst().changes()).hasSize(1);
    }

    @Test
    void the_same_change_described_differently_twice_is_rejected() {
        var clash = new EssentialsSchemaHarness(new RecordingApplier(),
                                                SchemaContext.empty(),
                                                List.of(contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "orders_queue", "SELECT 1")),
                                                        contributor("queues", SchemaOrder.ORDER_QUEUES, SchemaChange.repeatable("queue-table", "orders_queue", "SELECT 2"))));

        assertThatThrownBy(clash::apply).isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("'orders_queue', with different statements");
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

    @Test
    void a_dynamic_contributor_is_attached_before_the_sweep_and_its_later_registrations_reach_the_applier_as_its_own_change_set() {
        var applier     = new RecordingApplier();
        var contributor = new RegisteringContributor();
        contributor.register("orders_events");
        var harness = new EssentialsSchemaHarness(applier, SchemaContext.empty(), List.of(contributor));

        harness.apply();
        assertThat(contributor.attachedBeforeSweep).isTrue();
        assertThat(applier.applied).hasSize(1);
        assertThat(applier.applied.getFirst().changes()).extracting(SchemaChange::objectName).containsExactly("orders_events");

        contributor.register("products_events");
        assertThat(applier.applied).hasSize(2);
        var later = applier.applied.get(1);
        assertThat(later.moduleId()).isEqualTo("event-store");
        assertThat(later.order()).isEqualTo(SchemaOrder.ORDER_EVENT_STORE);
        assertThat(later.changes()).extracting(SchemaChange::objectName).containsExactly("products_events");
    }

    /**
     * Registers objects the way a table-per-type store does: remembered always, handed to the sink once there is one
     */
    private static final class RegisteringContributor implements DynamicSchemaContributor {
        private final List<String>     registered = new ArrayList<>();
        private       SchemaChangeSink sink;
        private       boolean          attachedBeforeSweep;

        void register(String table) {
            registered.add(table);
            if (sink != null) {
                sink.apply(changesFor(table));
            }
        }

        private static List<SchemaChange> changesFor(String table) {
            return List.of(SchemaChange.repeatable("stream-table", table, "SELECT 1"));
        }

        @Override
        public void attach(SchemaChangeSink sink) {
            this.sink = sink;
        }

        @Override
        public String moduleId() {
            return "event-store";
        }

        @Override
        public int order() {
            return SchemaOrder.ORDER_EVENT_STORE;
        }

        @Override
        public List<SchemaChange> contribute(SchemaContext context) {
            attachedBeforeSweep = sink != null;
            return registered.stream().flatMap(table -> changesFor(table).stream()).toList();
        }
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
