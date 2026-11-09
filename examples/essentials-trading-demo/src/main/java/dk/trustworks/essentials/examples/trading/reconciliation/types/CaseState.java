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

package dk.trustworks.essentials.examples.trading.reconciliation.types;

/**
 * A case's life: open, assigned to an operator, then resolved by them - or cleared, because the break went away by
 * itself (a late projection, a late statement) before anyone resolved it. Resolved and cleared are final.
 */
public enum CaseState {
    OPEN,
    ASSIGNED,
    RESOLVED,
    CLEARED;

    public boolean isClosed() {
        return this == RESOLVED || this == CLEARED;
    }
}
