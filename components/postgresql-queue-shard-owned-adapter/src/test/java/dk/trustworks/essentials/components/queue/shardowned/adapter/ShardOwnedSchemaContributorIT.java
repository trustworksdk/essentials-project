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
package dk.trustworks.essentials.components.queue.shardowned.adapter;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlCreateSchemaApplier;
import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's schema applied by the harness: the fixed tables in the sweep, each queue's sequences as it registers.
 */
@Testcontainers
class ShardOwnedSchemaContributorIT {
    private static final QueueName ORDERS = QueueName.of("orders");

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm");

    private HikariDataSource            dataSource;
    private Jdbi                        jdbi;
    private ShardOwnedSchemaContributor contributor;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        contributor = new ShardOwnedSchemaContributor();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void the_harness_creates_the_fixed_schema_and_each_registered_queue_gets_its_sequences_through_it() throws Exception {
        harness().apply();
        assertThat(exists(ShardOwnedSchema.REGISTRY_TABLE)).isTrue();
        assertThat(exists(ShardOwnedSchema.DLQ_VIEW)).isTrue();
        assertThat(ledger("engine-schema")).containsExactly(ShardOwnedSchema.REGISTRY_TABLE);

        var queue = contributor.registerQueue(dataSource, ORDERS, 3, ShardOwnedSchema.ORDERED_UNITS);

        assertThat(exists(ShardOwnedSchema.orderedSequenceName(queue.queueId()))).isTrue();
        for (var shard = 0; shard < 3; shard++) {
            assertThat(exists(ShardOwnedSchema.sequenceName(queue.queueId(), shard))).isTrue();
        }
        assertThat(ledger("queue-sequences")).containsExactly("shard_queue_q" + queue.queueId());
    }

    @Test
    void growing_a_queue_creates_the_new_shards_sequences_and_re_records_the_same_change() throws Exception {
        harness().apply();
        var queue    = contributor.registerQueue(dataSource, ORDERS, 2, ShardOwnedSchema.ORDERED_UNITS);
        var original = checksum("shard_queue_q" + queue.queueId());

        contributor.growShardCount(dataSource, ORDERS, 4);

        assertThat(exists(ShardOwnedSchema.sequenceName(queue.queueId(), 3))).isTrue();
        assertThat(ledger("queue-sequences")).containsExactly("shard_queue_q" + queue.queueId());
        assertThat(checksum("shard_queue_q" + queue.queueId())).isNotEqualTo(original);
    }

    @Test
    void a_schema_created_by_the_engine_itself_is_adopted_and_the_contribution_matches_it() throws Exception {
        // What an installation without the harness has: the engine created everything
        ShardOwnedSchema.initialize(dataSource);
        var queue = ShardOwnedSchema.registerQueue(dataSource, ORDERS, 2);

        // Re-registering is a no-op for the registry and re-runs only CREATE ... IF NOT EXISTS
        harness().apply();
        contributor.registerQueue(dataSource, ORDERS, 2, ShardOwnedSchema.ORDERED_UNITS);

        assertThat(ledger("engine-schema")).containsExactly(ShardOwnedSchema.REGISTRY_TABLE);
        assertThat(ledger("queue-sequences")).containsExactly("shard_queue_q" + queue.queueId());
    }

    @Test
    void the_default_registration_creates_exactly_the_sequences_the_statements_describe() throws Exception {
        ShardOwnedSchema.initialize(dataSource);
        var queue = ShardOwnedSchema.registerQueue(dataSource, ORDERS, 2);

        var described = ShardOwnedSchema.queueSequenceStatements(queue.queueId(), 2);
        assertThat(described).hasSize(3);
        List<String> created = jdbi.withHandle(handle -> handle.createQuery("SELECT sequencename FROM pg_sequences WHERE sequencename LIKE :pattern ORDER BY sequencename")
                                                               .bind("pattern", "%_q" + queue.queueId() + "%")
                                                               .mapTo(String.class)
                                                               .list());
        assertThat(created).containsExactlyInAnyOrder(ShardOwnedSchema.orderedSequenceName(queue.queueId()),
                                                      ShardOwnedSchema.sequenceName(queue.queueId(), 0),
                                                      ShardOwnedSchema.sequenceName(queue.queueId(), 1));
    }

    private EssentialsSchemaHarness harness() {
        return new EssentialsSchemaHarness(new PostgresqlCreateSchemaApplier(jdbi), SchemaContext.empty(), List.of(contributor));
    }

    private boolean exists(String relation) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT to_regclass(:name) IS NOT NULL").bind("name", relation).mapTo(Boolean.class).one());
    }

    private List<String> ledger(String changeId) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT object_name FROM essentials_schema_history WHERE module_id = :module AND change_id = :change")
                                               .bind("module", ShardOwnedSchemaContributor.MODULE_ID)
                                               .bind("change", changeId)
                                               .mapTo(String.class)
                                               .list());
    }

    private String checksum(String objectName) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT checksum FROM essentials_schema_history WHERE module_id = :module AND object_name = :object")
                                               .bind("module", ShardOwnedSchemaContributor.MODULE_ID)
                                               .bind("object", objectName)
                                               .mapTo(String.class)
                                               .one());
    }
}
