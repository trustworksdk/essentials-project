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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This engine's bootstrap advisory-lock key must equal the framework's.
 * <p>
 * The key is duplicated rather than imported, because the canonical helper takes a JDBI
 * {@code Handle} and lives in {@code foundation}, which this module deliberately does not depend on
 * — it speaks plain JDBC and depends on {@code shared} alone. A duplicated constant that drifts is
 * worse than no lock at all: both sides would still take a lock, both would look correct in review,
 * and neither would exclude the other. An application bootstrapping the event store and this queue
 * at the same time is exactly when that shows up, as a `pg_type_typname_nsp_index` violation on
 * whichever component lost.
 * <p>
 * {@code foundation} is on this module's <em>test</em> classpath already (via {@code
 * foundation-test}, for the construction-ergonomics rules), so the two can be compared here without
 * the production dependency the duplication exists to avoid.
 */
class BootstrapLockKeyTest {

    @Test
    void matches_the_framework_wide_bootstrap_lock_key() {
        assertThat(ShardOwnedSchema.ESSENTIALS_BOOTSTRAP_LOCK_KEY)
                .as("a DDL lock on a different key excludes nobody — keep this in step with "
                    + "PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY")
                .isEqualTo(PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY);
    }
}
