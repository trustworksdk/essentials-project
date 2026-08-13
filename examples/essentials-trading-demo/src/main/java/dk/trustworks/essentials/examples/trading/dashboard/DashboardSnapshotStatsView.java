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

package dk.trustworks.essentials.examples.trading.dashboard;

/**
 * Snapshot summary for the dashboard, totalled across every snapshotting aggregate type the demo tracks.
 *
 * @param aggregateTypes comma-separated list of the aggregate types the counts below are summed over
 */
public record DashboardSnapshotStatsView(String aggregateTypes,
                                         long loadCount,
                                         long saveCount,
                                         long serializeCount,
                                         long deserializeCount,
                                         double totalObservedSnapshotTimeMs) {
}
