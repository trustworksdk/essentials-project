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

package dk.trustworks.essentials.components.queue.shardowned.node;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.queue.shardowned.*;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * One queue consumer, as its own operating-system process.
 * <p>
 * Every other test in this suite runs several engine instances inside one JVM, which shares a heap,
 * a garbage collector, a clock and a class loader. That is a model of a distributed system, not a
 * distributed system: it cannot produce a real process death, and it cannot show what happens when
 * one node's world stops while another's continues. This runs the engine where those things are real.
 * <p>
 * Deliveries are recorded into a table rather than to stdout, because a process killed with SIGKILL
 * does not flush anything — and the whole point of this harness is to kill it that way. The table's
 * serial id gives a global observation order across processes, which is what makes cross-node
 * ordering checkable at all.
 * <p>
 * Arguments: {@code jdbcUrl user password instanceId shardCount maxShards lane}
 */
public final class ShardOwnedNodeMain {

    public static void main(String[] args) throws Exception {
        var jdbcUrl = args[0];
        var user = args[1];
        var password = args[2];
        var instanceId = args[3];
        var shardCount = Integer.parseInt(args[4]);
        var maxShards = Integer.parseInt(args[5]);
        var lane = args[6];

        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        var dataSource = new HikariDataSource(config);

        var settings = new ShardOwnerSettings(500, 200, Duration.ofMillis(1), Duration.ofMillis(2),
                                              Duration.ofMillis(300), Duration.ofMillis(100),
                                              1_000, 8, Duration.ofMillis(50), Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofMillis(1000));

        var queue = new ShardOwnedQueue(dataSource, (short) 1, shardCount, instanceId);
        if ("ordered".equals(lane)) {
            queue.startConsumingOrdered((key, payload, payloadType) -> record(dataSource, instanceId, key,
                                                                 ByteBuffer.wrap(payload).getLong()),
                                        settings, maxShards);
        } else {
            queue.startConsuming((payload, payloadType) -> record(dataSource, instanceId, null,
                                                   ByteBuffer.wrap(payload).getLong()),
                                 settings, maxShards);
        }
        System.out.println("READY " + instanceId);
        System.out.flush();

        // Run until killed. A clean shutdown is deliberately not offered: this process exists to be
        // terminated abruptly.
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void record(HikariDataSource dataSource, String instanceId, String key, long value) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO shard_queue_observed (instance_id, msg_key, value) VALUES (?, ?, ?)")) {
            statement.setString(1, instanceId);
            statement.setString(2, key);
            statement.setLong(3, value);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to record delivery", e);
        }
    }
}
