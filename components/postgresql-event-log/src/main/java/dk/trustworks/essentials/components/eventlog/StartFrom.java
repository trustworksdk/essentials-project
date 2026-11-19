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
package dk.trustworks.essentials.components.eventlog;

/**
 * Where a subscription whose progress row does not exist yet starts. An existing row always
 * resumes from its stored point.
 */
public sealed interface StartFrom permits StartFrom.Head, StartFrom.GlobalOrder {

    /** @return {@code global_order} 1: every event */
    static StartFrom beginning() {
        return new GlobalOrder(1);
    }

    /**
     * @return the event log's head when the progress row is created: {@code max(global_order) + 1} of the aggregate
     * type's event table, resolved in the statement that creates the row
     */
    static StartFrom head() {
        return Head.INSTANCE;
    }

    /** @return {@code globalOrder}, at least 1 */
    static StartFrom globalOrder(long globalOrder) {
        return new GlobalOrder(globalOrder);
    }

    /** See {@link StartFrom#head()} */
    enum Head implements StartFrom {
        INSTANCE
    }

    /** A fixed first {@code global_order} */
    record GlobalOrder(long value) implements StartFrom {
        public GlobalOrder {
            if (value < 1) {
                throw new IllegalArgumentException("A subscription starts at global_order >= 1, not " + value);
            }
        }
    }
}
