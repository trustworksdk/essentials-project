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

import java.sql.*;
import java.util.List;

/**
 * A step of the cut-over transaction that runs under the {@code SHARE} locks, after the
 * delta is copied, the heads are written and the identity sequences are set, and before the migration state becomes
 * {@code CUT_OVER}, and after the built-in delta verification. The extension point for the import of resume points. A
 * step runs on the cut-over's connection and must neither commit nor roll back; an exception rolls the
 * whole cut-over back to the state after the last epoch.
 */
@FunctionalInterface
public interface CutoverStep {
    void run(Connection connection, List<CutoverReport.TypeCutover> copied) throws SQLException;
}
