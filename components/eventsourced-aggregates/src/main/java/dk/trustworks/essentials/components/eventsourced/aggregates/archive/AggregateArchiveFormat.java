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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

/**
 * Enum representing the format types supported for aggregate archives.
 * <p>
 * The formats define the encoding or structuring mechanism used to
 * store archived aggregate data. Supported formats are:
 * <ul>
 * - JSONL: Represents newline-delimited JSON format, where each line
 *   contains a valid JSON object.
 * - PARQUET: Represents the Parquet format, which is a columnar storage
 *   file format designed for efficient data retrieval.
 * <p>
 * Primarily used in conjunction with aggregate archiving processes to
 * specify the desired output structure.
 */
public enum AggregateArchiveFormat {
    JSONL,
    PARQUET
}
