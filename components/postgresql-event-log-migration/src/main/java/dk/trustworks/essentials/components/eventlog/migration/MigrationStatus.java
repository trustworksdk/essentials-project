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

package dk.trustworks.essentials.components.eventlog.migration;

import java.time.OffsetDateTime;

/**
 * One aggregate type's {@code el_migration} row.
 *
 * @param state         {@code COPYING} or {@code CUT_OVER}
 * @param copiedThrough every old row with {@code global_order <= copiedThrough} is copied
 * @param epochs        the online epochs run so far
 * @param cutOverAt     the cut-over's commit time, or {@code null} while copying
 */
public record MigrationStatus(String aggregateType, State state, String sourceTable, long copiedThrough, int epochs, OffsetDateTime startedAt,
                              OffsetDateTime cutOverAt) {
    public enum State {
        COPYING,
        CUT_OVER
    }
}
