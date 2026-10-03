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

import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The shard-owned engine's schema as an {@link EssentialsSchemaHarness} contribution. The engine itself depends on
 * nothing but {@code shared}, so it exposes its DDL as plain statements - {@link ShardOwnedSchema#schemaStatements()}
 * and {@link ShardOwnedSchema#queueSequenceStatements(short, int)} - and this class carries them into the harness.
 * <p>
 * The fixed schema - tables, views, the fixed sequences - is one change, and goes wherever the harness' applier takes
 * it. Each queue's sequences are different: their names contain the id the registry assigns when the queue registers,
 * so they cannot be described up front, nor be part of a script written before the queue exists. Register queues
 * through {@link #registerQueue} / {@link #growShardCount} (or pass {@link #queueDdl()} to {@link ShardOwnedSchema}'s
 * registration methods yourself), and:
 * <ul>
 *     <li>when the harness' applier creates the schema, each queue's sequences become a change of this contributor,
 *     applied and recorded like any other;</li>
 *     <li>when it does not - validate, emit, external - the engine creates them itself as the queue registers, under
 *     the bootstrap lock, as it does without a harness. <b>The database user then needs the right to create
 *     sequences at runtime</b>, and a warning says so when the harness attaches.</li>
 * </ul>
 * Registration writes to the registry table, which is part of the fixed schema, so queues are registered once the
 * harness has run.
 * <p>
 * Without a harness, keep calling {@link ShardOwnedSchema#initialize(DataSource)} and
 * {@link ShardOwnedSchema#registerQueue(DataSource, QueueName, int, int)}; they create everything themselves, as
 * before.
 */
public final class ShardOwnedSchemaContributor implements DynamicSchemaContributor {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedSchemaContributor.class);

    /**
     * The {@link #moduleId()} the engine's schema is recorded under
     */
    public static final String MODULE_ID = "postgresql-queue-shard-owned";

    private final DataSource                                 dataSource;
    private final ConcurrentSkipListMap<Short, List<String>> queueSequences = new ConcurrentSkipListMap<>();
    private final AtomicReference<SchemaChangeSink>          sink           = new AtomicReference<>();
    private final AtomicBoolean                              warned         = new AtomicBoolean();

    /**
     * @param dataSource the engine's database - queues are registered against it, and it is where the queue
     *                   sequences are created when the harness' applier does not create the schema
     */
    public ShardOwnedSchemaContributor(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public int order() {
        return SchemaOrder.ORDER_QUEUES;
    }

    /**
     * The engine's fixed schema, and - when the applier creates the schema - the sequences of every queue registered
     * through {@link #queueDdl()} so far.
     */
    @Override
    public List<SchemaChange> contribute(SchemaContext context) {
        var changes = new ArrayList<SchemaChange>(queueSequences.size() + 1);
        changes.add(SchemaChange.repeatable("engine-schema",
                                            ShardOwnedSchema.REGISTRY_TABLE,
                                            ShardOwnedSchema.schemaStatements().toArray(String[]::new)));
        queueSequences.forEach((queueId, statements) -> changes.add(queueSequencesChange(queueId, statements)));
        return changes;
    }

    @Override
    public void attach(SchemaChangeSink sink) {
        requireNonNull(sink, "No sink provided");
        this.sink.set(sink);
        if (!sink.createsSchema() && warned.compareAndSet(false, true)) {
            log.warn("The schema harness is not creating the schema in this mode, but the shard-owned queue engine creates each queue's " +
                             "sequences itself when the queue registers: their names contain the queue id the registry assigns at registration, " +
                             "so they cannot be part of a script written beforehand. The database user this application connects as therefore " +
                             "needs the right to create sequences at runtime. The engine's tables, views and fixed sequences are covered by the " +
                             "harness as usual.");
        }
        if (!sink.createsSchema()) {
            // Registered before the harness ran: nobody else will create them now
            for (var queueId : new ArrayList<>(queueSequences.keySet())) {
                var statements = queueSequences.remove(queueId);
                createDirectly(queueId, statements);
            }
        }
    }

    /**
     * @return the executor to hand {@link ShardOwnedSchema}'s registration methods, so a queue's sequences go through
     * the harness - or are created directly, when the harness' applier does not create the schema
     */
    public ShardOwnedSchema.QueueDdlExecutor queueDdl() {
        return (queueId, statements) -> {
            var current = sink.get();
            if (current != null && !current.createsSchema()) {
                createDirectly(queueId, statements);
                return;
            }
            // Replaced, not merged: after growShardCount the statements cover every shard, old and new, and that
            // latest set is the one the ledger should record
            queueSequences.put(queueId, List.copyOf(statements));
            if (current != null) {
                current.apply(List.of(queueSequencesChange(queueId, statements)));
            }
        };
    }

    /**
     * {@link ShardOwnedSchema#registerQueue(DataSource, QueueName, int, int, ShardOwnedSchema.QueueDdlExecutor)} with
     * {@link #queueDdl()}.
     */
    public ShardOwnedSchema.RegisteredQueue registerQueue(QueueName name, int shardCount, int orderedUnits) throws SQLException {
        return ShardOwnedSchema.registerQueue(dataSource, name, shardCount, orderedUnits, queueDdl());
    }

    /**
     * {@link ShardOwnedSchema#growShardCount(DataSource, QueueName, int, ShardOwnedSchema.QueueDdlExecutor)} with
     * {@link #queueDdl()}.
     */
    public ShardOwnedSchema.RegisteredQueue growShardCount(QueueName name, int newShardCount) throws SQLException {
        return ShardOwnedSchema.growShardCount(dataSource, name, newShardCount, queueDdl());
    }

    private void createDirectly(short queueId, List<String> statements) {
        try {
            ShardOwnedSchema.lockedQueueDdl(dataSource).execute(queueId, statements);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the sequences of shard-owned queue " + queueId +
                                                    " - the database user needs the right to create sequences", e);
        }
        log.info("Created the sequences of shard-owned queue {} directly, as the schema harness is not creating the schema", queueId);
    }

    private static SchemaChange queueSequencesChange(short queueId, List<String> statements) {
        return SchemaChange.repeatable("queue-sequences", "shard_queue_q" + queueId, statements.toArray(String[]::new));
    }
}
