/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.queue.postgresql;

/**
 * Integration test class for measuring the latency of traditional durable queue sql
 * <p>
 * Key features include:
 * - Benchmarking multiple queues with ordered message delivery.
 * - Measuring latency (average, 95th percentile) during queue operations.
 * - Execution of load tests with a configurable number of queues, messages per test, and batch size.
 */
public class TraditionalPostgresqlDurableQueuesLatencyIT extends PostgresqlDurableQueuesLatencyIT {

    @Override
    protected long targetQueriesToMeasure() {
        return 1000;
    }

    @Override
    protected long targetQueriesToMeasurePerQueue() {
        return 200; // There is 5 test queues in test data
    }
}
