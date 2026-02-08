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

package dk.trustworks.essentials.components.queue.postgresql;

import org.junit.jupiter.api.Disabled;

import java.time.Duration;

@Disabled("Performance tests are disabled by default")
public class TraditionalPostgresqlDurableQueuesPerformanceIT extends PostgresqlDurableQueuesPerformanceIT {

    @Override
    protected boolean useCentralizedMessageFetcher() {
        return false;
    }

    @Override
    protected boolean useOrderedUnorderedQuery() {
        return false;
    }

    @Override
    protected long totalMessagesConsumedTarget() {
        return 500L;
    }

    @Override
    protected Duration consumerPollInterval() {
        return Duration.ofMillis(20);
    }

    @Override
    protected Duration timeToWait() {
        return Duration.ofSeconds(60 * 5);
    }

    @Override
    protected boolean logMessagesReceivedDuringProcessing() {
        return false;
    }
}
