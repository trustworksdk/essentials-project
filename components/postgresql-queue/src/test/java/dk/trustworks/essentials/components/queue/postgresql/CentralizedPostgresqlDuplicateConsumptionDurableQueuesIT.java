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
 * Integration tests for duplicate consumption using the centralized message fetcher.
 * <p>
 * This test verifies Bug #19 fix: duplicate message consumption with multiple pods.<br>
 * <br>
 * The bug occurred when CentralizedMessageFetcher.calculateAvailableWorkerSlotsPerQueue()
 * used Math.max(1, ...) which caused over-fetching even when all workers were busy.<br>
 * This led to messages piling up in the worker pool queue. If messages waited longer
 * than messageHandlingTimeout here, they were reset as "stuck" and became available for
 * other instances to fetch, potentially causing duplicate consumption.<br>
 * <br>
 * The fix changed Math.max(1, ...) to Math.max(0, ...) to prevent over-fetching.
 * <p>
 * This test verifies that no duplicate message consumption occurs when
 * using the {@link dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher}
 * approach.
 *
 */
class CentralizedPostgresqlDuplicateConsumptionDurableQueuesIT extends PostgresqlDuplicateConsumptionDurableQueuesIT {

    @Override
    protected boolean useCentralizedMessageFetcher() {
        return true;
    }
}
