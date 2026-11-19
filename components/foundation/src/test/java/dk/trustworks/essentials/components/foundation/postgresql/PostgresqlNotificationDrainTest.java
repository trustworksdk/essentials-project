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

package dk.trustworks.essentials.components.foundation.postgresql;

import org.junit.jupiter.api.Test;
import org.postgresql.core.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upgrade guard of {@link PostgresqlNotificationDrain.Mode#PINNED_PEEK}'s reflection: a pgjdbc rename or retype of
 * either field fails the build here, instead of silently dropping every listener to the slower query poll.
 */
class PostgresqlNotificationDrainTest {
    @Test
    void pgjdbc_still_has_the_fields_the_pinned_peek_writes() throws Exception {
        assertThat(QueryExecutorBase.class.getDeclaredField("pgStream").getType()).isEqualTo(PGStream.class);
        assertThat(PostgresqlNotificationDrain.nextCheckField().getType()).isEqualTo(long.class);
        assertThat(PostgresqlNotificationDrain.nextCheckField().getName()).isEqualTo("nextStreamAvailableCheckTime");
    }
}
